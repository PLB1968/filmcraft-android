package io.github.artcraftandroid.rushcraft.ui.editor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import io.github.artcraftandroid.rushcraft.core.Renderer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plays the timeline's sound and keeps the clock the picture follows. A writer thread mixes the
 * sound in blocks ahead of the speaker; the playhead is where the speaker is, so picture and sound
 * stay together even when drawing falls behind (frames are skipped, never the sound).
 */
class Player {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var running = false
    private var writer: Thread? = null
    private var startTick = 0L
    private var endTick = 0L
    private var rate = 48_000
    private var startedAt = 0L

    val isPlaying: Boolean get() = running

    /** Start playing [r] from [from] to [to] (ticks). */
    fun start(r: Renderer, from: Long, to: Long) {
        stop()
        rate = r.sampleRate().toInt().coerceIn(8000, 192_000)
        startTick = from
        endTick = to
        val bytesPerFrame = 8
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val size = maxOf(min, rate / 4 * bytesPerFrame)
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
        running = true
        startedAt = SystemClock.elapsedRealtime()
        track = t
        if (t == null) return
        val ticksPerSample = TPS / rate
        val startSample = from / ticksPerSample
        val endSample = to / ticksPerSample
        val block = 2048
        writer = Thread({
            var cursor = startSample
            runCatching { t.play() }
            while (running && cursor < endSample) {
                val n = minOf(block.toLong(), endSample - cursor).toInt()
                val pcm = runCatching { r.mix(cursor, n.toUInt()) }.getOrElse { ByteArray(n * bytesPerFrame) }
                val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
                var off = 0
                while (running && off < pcm.size) {
                    buf.position(off)
                    val w = t.write(buf, pcm.size - off, AudioTrack.WRITE_BLOCKING)
                    if (w <= 0) break
                    off += w
                }
                cursor += n
            }
        }, "rushcraft-audio").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    /** Where playback is now (ticks), from the speaker's position. */
    fun position(): Long {
        val t = track
        if (t == null) {
            // no sound device: the wall clock
            val us = (SystemClock.elapsedRealtime() - startedAt) * 1000L
            return startTick + us * (TPS / 1_000_000L)
        }
        val head = runCatching { t.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(0L)
        return startTick + head * (TPS / rate)
    }

    val end: Long get() = endTick

    fun stop() {
        running = false
        val t = track
        track = null
        runCatching { t?.pause(); t?.flush() }
        runCatching { writer?.join(300) }
        writer = null
        runCatching { t?.release() }
    }
}
