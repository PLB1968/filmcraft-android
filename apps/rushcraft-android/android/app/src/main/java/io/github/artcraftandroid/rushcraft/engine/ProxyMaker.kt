package io.github.artcraftandroid.rushcraft.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import io.github.artcraftandroid.rushcraft.core.ProxyWork
import java.io.File

/**
 * Makes proxies with the phone's own video hardware (Media3 Transformer): many times faster than
 * the engine, which decodes every 4K frame in software. The proxy is the picture only (the sound
 * still comes from the original), 540 pixels on its short side, with a keyframe every half
 * second so scrubbing stays quick.
 *
 * HDR video stays HDR (10-bit HEVC) when this phone's encoder can do it, else it is converted
 * to standard colour (H.264). When neither works the engine makes the proxy instead.
 *
 * Used on the main thread only.
 */
@OptIn(UnstableApi::class)
class ProxyMaker(private val context: Context) {
    private var transformer: Transformer? = null
    private val holder = ProgressHolder()

    /** The original whose proxy is being made (until [forget]), else null. */
    var media: String? = null
        private set
    private var out: String? = null

    private enum class Plan { KeepHdr, ToneMap, Sdr }

    /** Make [w]'s proxy; [onDone] says whether it worked (then call [forget]). */
    fun start(w: ProxyWork, onDone: (Boolean) -> Unit) {
        cancel()
        media = w.media
        out = w.out
        attempt(w, if (isHdr(w.media)) Plan.KeepHdr else Plan.Sdr, onDone)
    }

    private fun attempt(w: ProxyWork, plan: Plan, onDone: (Boolean) -> Unit) {
        File(w.out).delete()
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(w.media))))
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(SHORT_SIDE))))
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence(item))
            .setHdrMode(if (plan == Plan.ToneMap) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL else Composition.HDR_MODE_KEEP_HDR)
            .build()
        val encoder = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(BITRATE).setiFrameIntervalSeconds(0.5f).build())
            .setEnableFallback(true)
            .build()
        // a cancelled attempt's transformer is no longer [transformer]: it reports nothing
        lateinit var t: Transformer
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (transformer !== t) return
                transformer = null
                onDone(true)
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                if (transformer !== t) return
                transformer = null
                android.util.Log.w("Rushcraft", "proxy of ${w.media} ($plan): ${exportException.message}")
                // this phone can't keep the HDR: convert it to standard colour instead
                if (plan == Plan.KeepHdr) attempt(w, Plan.ToneMap, onDone) else onDone(false)
            }
        }
        t = Transformer.Builder(context)
            .setVideoMimeType(if (plan == Plan.KeepHdr) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            .setEncoderFactory(encoder)
            .addListener(listener)
            .build()
        transformer = t
        t.start(composition, w.out)
    }

    /** How far along it is (0..1), when the converter knows. */
    fun progress(): Double? {
        val t = transformer ?: return null
        return if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress / 100.0 else null
    }

    /** The finished proxy has been reported: ready for the next. */
    fun forget() {
        if (transformer == null) {
            media = null
            out = null
        }
    }

    /** Stop (its unfinished file is deleted). */
    fun cancel() {
        transformer?.let {
            transformer = null
            it.cancel()
        }
        out?.let { File(it).delete() }
        media = null
        out = null
    }

    private companion object {
        /** A quarter of 4K's 2160 lines. */
        const val SHORT_SIDE = 540
        const val BITRATE = 3_000_000

        /** HDR video: HLG or PQ (HDR10, HDR10+), or Dolby Vision. */
        fun isHdr(path: String): Boolean {
            val x = MediaExtractor()
            return try {
                x.setDataSource(path)
                (0 until x.trackCount).any { i ->
                    val f = x.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                    val transfer = if (f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) f.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
                    mime == MimeTypes.VIDEO_DOLBY_VISION ||
                        (mime.startsWith("video/") && (transfer == MediaFormat.COLOR_TRANSFER_HLG || transfer == MediaFormat.COLOR_TRANSFER_ST2084))
                }
            } catch (_: Exception) {
                false
            } finally {
                x.release()
            }
        }
    }
}
