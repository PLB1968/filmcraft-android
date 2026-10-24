package io.github.artcraftandroid.rushcraft.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import io.github.artcraftandroid.rushcraft.core.Renderer
import io.github.artcraftandroid.rushcraft.engine.Engine
import io.github.artcraftandroid.rushcraft.engine.toBitmap
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pictures and waveforms for the timeline: filmstrip frames of the clips' media (decoded by the
 * phone's own decoders, which are fast at small sizes) and the loudness of their sound (read by
 * the engine). Both are made in the background on demand and drawn as they arrive: [version]
 * changes when something new is ready.
 */
class MediaCache(private val engine: Engine, private val scope: CoroutineScope) {
    var version by mutableIntStateOf(0)
        private set

    /** Filmstrip frames by "path@second", about 12 MB. */
    private val frames = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val retrievers = LinkedHashMap<String, MediaMetadataRetriever>()

    /** Waveforms by "item:path", as peaks per [WAVE_RATE] of a second of media. */
    private val waves = ConcurrentHashMap<String, FloatArray>()
    private val wavePending = ConcurrentHashMap.newKeySet<String>()

    /** The newest renderer, for waveforms and media the phone can't decode. */
    @Volatile var renderer: Renderer? = null

    /** The frame of [path] near [second] (whole tenths), or null while it is being made. */
    fun frame(path: String, second: Double, height: Int, still: Boolean): Bitmap? {
        val t = if (still) 0.0 else Math.round(second * 2) / 2.0
        val key = "$path@$t"
        frames.get(key)?.let { return it }
        if (key in failed || !pending.add(key)) return null
        scope.launch {
            val b = withContext(engine.thumbnails) { runCatching { decode(path, t, height, still) }.getOrNull() }
            if (b != null) frames.put(key, b) else failed.add(key)
            pending.remove(key)
            version++
        }
        return null
    }

    private fun decode(path: String, t: Double, height: Int, still: Boolean): Bitmap? {
        if (!File(path).isFile) return null
        if (still) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var sample = 1
            while (o.outHeight / (sample * 2) >= height) sample *= 2
            return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        val r = synchronized(retrievers) {
            retrievers[path] ?: MediaMetadataRetriever().also {
                it.setDataSource(path)
                retrievers[path] = it
                if (retrievers.size > 6) retrievers.remove(retrievers.keys.first())?.release()
            }
        }
        val us = (t * 1_000_000).toLong()
        val raw = synchronized(r) {
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (dw, dh) = if (rot == 90 || rot == 270) h to w else w to h
            if (dw > 0 && dh > 0) {
                val k = height.toFloat() / dh
                r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, (dw * k).toInt().coerceAtLeast(1), height.coerceAtLeast(1))
            } else {
                r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        }
        return raw
    }

    /** An engine-drawn still of project item [item] (blanks, media the phone can't decode). */
    fun engineFrame(item: Long, time: Long, height: Int): Bitmap? {
        val key = "item:$item@$time"
        frames.get(key)?.let { return it }
        val r = renderer ?: return null
        if (key in failed || !pending.add(key)) return null
        scope.launch {
            val b = withContext(engine.thumbnails) { runCatching { r.thumbnail(item.toULong(), time, height.toUInt(), null).toBitmap() }.getOrNull() }
            if (b != null) frames.put(key, b) else failed.add(key)
            pending.remove(key)
            version++
        }
        return null
    }

    /** The peaks of [item]'s sound ([WAVE_RATE] per second of media), or null while being read. */
    fun wave(item: Long, path: String, mediaDuration: Long): FloatArray? {
        val key = "$item:$path"
        waves[key]?.let { return it }
        val r = renderer ?: return null
        if (!wavePending.add(key)) return null
        scope.launch {
            val buckets = ((mediaDuration.toDouble() / TPS) * WAVE_RATE).toLong().coerceIn(1, 200_000).toUInt()
            val p = withContext(engine.thumbnails) { runCatching { r.peaks(item.toULong(), 0, mediaDuration, buckets) }.getOrNull() }
            waves[key] = p?.toFloatArray() ?: FloatArray(0)
            version++
        }
        return null
    }

    fun release() {
        synchronized(retrievers) {
            retrievers.values.forEach { runCatching { it.release() } }
            retrievers.clear()
        }
        frames.evictAll()
    }

    companion object {
        /** Waveform values per second of media. */
        const val WAVE_RATE = 60.0
    }
}
