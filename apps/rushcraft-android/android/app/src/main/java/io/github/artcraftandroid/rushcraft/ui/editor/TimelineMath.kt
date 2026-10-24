package io.github.artcraftandroid.rushcraft.ui.editor

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Engine ticks per second (`filmcraft_time::Tick`). */
const val TPS: Long = 254_016_000_000L

/**
 * The timeline's pure maths, kept apart from Compose so it can be tested off the phone: time
 * labels, the ruler's steps, zoom limits and where the lanes sit.
 */
object TimelineMath {
    /** Whole frames in [ticks] at [num]/[den] frames per second (floored). */
    fun frames(ticks: Long, num: Long, den: Long): Long {
        if (num <= 0 || den <= 0) return 0
        // floor(ticks × num / (TPS × den)), exactly: whole periods of `den` seconds, then the rest
        val period = TPS * den
        val q = ticks.floorDiv(period)
        val r = ticks - q * period
        val part = try {
            Math.multiplyExact(r, num) / period
        } catch (_: ArithmeticException) {
            java.math.BigInteger.valueOf(r).multiply(java.math.BigInteger.valueOf(num)).divide(java.math.BigInteger.valueOf(period)).toLong()
        }
        return q * num + part
    }

    /** The tick of frame [f]. */
    fun tickOfFrame(f: Long, num: Long, den: Long): Long {
        if (num <= 0) return 0
        val whole = f * den / num
        val rem = f * den - whole * num
        return whole * TPS + rem * TPS / num
    }

    /** Snap [ticks] down to its frame. */
    fun snap(ticks: Long, num: Long, den: Long): Long = tickOfFrame(frames(ticks, num, den), num, den)

    /** The time part of a timecode: "0:04", "1:02:03" (seconds always two digits). */
    fun clock(ticks: Long): String {
        val s = max(0L, ticks / TPS)
        val h = s / 3600
        val m = (s / 60) % 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** The frame part of a timecode: the frame within its second, two digits ("00"–"29"). */
    fun frameOfSecond(ticks: Long, num: Long, den: Long): String {
        val fps = if (den > 0) num.toDouble() / den else 30.0
        val wholeSec = max(0L, ticks / TPS) * TPS
        val f = frames(max(0L, ticks) - wholeSec, num, den)
        val cap = max(1, ceil(fps).toInt()) - 1
        return "%02d".format(min(f, cap.toLong()))
    }

    /**
     * A desktop timecode, hours:minutes:seconds:frames ("00:01:02:15"), counted in whole frames
     * at the rate's nominal frames per second (non-drop-frame, as Premiere shows 29.97 by default).
     */
    fun timecode(ticks: Long, num: Long, den: Long): String {
        val fps = max(1L, if (den > 0) Math.round(num.toDouble() / den) else 30L)
        val f = frames(max(0L, ticks), num, den)
        val secs = f / fps
        return "%02d:%02d:%02d:%02d".format(secs / 3600, (secs / 60) % 60, secs % 60, f % fps)
    }

    /** Seconds with one decimal and "s" ("0.4s"). */
    fun seconds1(ticks: Long): String = "%.1fs".format(ticks.toDouble() / TPS)

    /** A clip's length badge: seconds with one decimal ("8.0"). */
    fun badge(ticks: Long): String = "%.1f".format(ticks.toDouble() / TPS)

    /** Steps (in seconds) the ruler may label: 1, 2, 3, 4, 5, 6, 10, 12, 15, 30 s, then minutes. */
    private val SECOND_STEPS = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 10.0, 12.0, 15.0, 30.0, 60.0, 120.0, 180.0, 240.0, 300.0, 360.0, 600.0, 720.0, 900.0, 1800.0, 3600.0)
    private val FRAME_STEPS = intArrayOf(1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30)

    /** A ruler step: its length in ticks and whether its labels are frames. */
    data class Step(val ticks: Long, val frames: Int)

    /**
     * The smallest step whose labels are at least [minPx] apart at [pxPerSecond], in seconds, or
     * in frames when zoomed in far enough.
     */
    fun rulerStep(pxPerSecond: Float, minPx: Float, num: Long, den: Long): Step {
        val fps = if (den > 0) num.toDouble() / den else 30.0
        if (pxPerSecond >= minPx) {
            for (f in FRAME_STEPS) {
                if (f >= fps) break
                if (pxPerSecond * f / fps >= minPx) return Step(tickOfFrame(f.toLong(), num, den), f)
            }
        }
        for (s in SECOND_STEPS) if (pxPerSecond * s >= minPx) return Step((s * TPS).toLong(), 0)
        return Step(3600L * TPS, 0)
    }

    /** A ruler label for time [t] on a [step]: "0:02", or "15f" between whole seconds. */
    fun rulerLabel(t: Long, step: Step, num: Long, den: Long): String {
        if (step.frames > 0) {
            val f = frames(t, num, den)
            val fps = if (den > 0) Math.round(num.toDouble() / den) else 30L
            if (fps > 0 && f % fps != 0L) return "${f % fps}f"
        }
        return clock(t)
    }

    /** Zoom limits: the whole timeline at its narrowest, about 6 frames across the screen at most. */
    fun minPxPerSecond(viewport: Float, duration: Long): Float {
        val secs = max(1.0, duration.toDouble() / TPS)
        return max(2f, (viewport / 2f / secs).toFloat())
    }

    fun maxPxPerSecond(viewport: Float, num: Long, den: Long): Float {
        val fps = if (den > 0) num.toDouble() / den else 30.0
        return (viewport / 6f * fps).toFloat()
    }

    fun clampZoom(pxPerSecond: Float, viewport: Float, duration: Long, num: Long, den: Long): Float {
        val lo = minPxPerSecond(viewport, duration)
        val hi = max(lo, maxPxPerSecond(viewport, num, den))
        return pxPerSecond.coerceIn(lo, hi)
    }

    /** The default zoom: the timeline's first 15 seconds across the screen. */
    fun defaultPxPerSecond(viewport: Float): Float = max(4f, viewport / 15f)

    /** Screen x of time [t] when the playhead [playhead] sits at [centerX]. */
    fun xOf(t: Long, playhead: Long, centerX: Float, pxPerSecond: Float): Float =
        centerX + ((t - playhead).toDouble() / TPS * pxPerSecond).toFloat()

    /** The time at screen x. */
    fun timeAt(x: Float, playhead: Long, centerX: Float, pxPerSecond: Float): Long =
        playhead + ((x - centerX) / pxPerSecond * TPS).toLong()

    /** Ticks moved by a horizontal drag of [dx] pixels. */
    fun ticksFor(dx: Float, pxPerSecond: Float): Long = (dx / pxPerSecond * TPS).toLong()

    /**
     * Vertical layout of lanes: [heights] top to bottom, [main] the main track's index among them,
     * [gap] between lanes. The main track sits in the middle of [area] when everything fits;
     * otherwise the stack is scrolled by [scroll] (clamped). Returns each lane's top.
     */
    fun laneTops(heights: List<Float>, main: Int, gap: Float, area: Float, scroll: Float): FloatArray {
        val n = heights.size
        val tops = FloatArray(n)
        if (n == 0) return tops
        var y = 0f
        for (i in 0 until n) {
            tops[i] = y
            y += heights[i] + gap
        }
        val total = y - gap
        val mainCenter = tops[main.coerceIn(0, n - 1)] + heights[main.coerceIn(0, n - 1)] / 2f
        // centre the main track, but never leave space above the first lane or below the last
        var offset = area / 2f - mainCenter
        if (total <= area) {
            offset = offset.coerceIn(0f, area - total)
        } else {
            offset = (offset - scroll).coerceIn(area - total, 0f)
        }
        for (i in 0 until n) tops[i] += offset
        return tops
    }

    /** The insertion slot (0…n) a dragged main-track clip would take, from the finger's time. */
    fun slotAt(t: Long, starts: List<Long>, ends: List<Long>): Int {
        for (i in starts.indices) {
            val mid = (starts[i] + ends[i]) / 2
            if (t < mid) return i
        }
        return starts.size
    }

    /** Whether two times are within [px] pixels at [pxPerSecond]. */
    fun near(a: Long, b: Long, px: Float, pxPerSecond: Float): Boolean = abs((a - b).toDouble() / TPS * pxPerSecond) <= px

    /** The value of a piecewise-linear slider at [f] (0..1) through evenly spaced [anchors]. */
    fun piecewise(f: Float, anchors: DoubleArray): Double {
        if (anchors.isEmpty()) return 0.0
        if (anchors.size == 1) return anchors[0]
        val x = f.coerceIn(0f, 1f) * (anchors.size - 1)
        val i = min(floor(x).toInt(), anchors.size - 2)
        val k = x - i
        return anchors[i] + (anchors[i + 1] - anchors[i]) * k
    }

    /** The inverse of [piecewise] for a monotonic [anchors]. */
    fun piecewiseFraction(v: Double, anchors: DoubleArray): Float {
        if (anchors.size < 2) return 0f
        if (v <= anchors.first()) return 0f
        if (v >= anchors.last()) return 1f
        for (i in 0 until anchors.size - 1) {
            if (v <= anchors[i + 1]) {
                val k = (v - anchors[i]) / (anchors[i + 1] - anchors[i])
                return ((i + k) / (anchors.size - 1)).toFloat()
            }
        }
        return 1f
    }

    /** "1x", "0.25x", "1.5x": up to two decimals, trailing zeros dropped. */
    fun speedLabel(speed: Double): String {
        val s = "%.2f".format(speed).trimEnd('0').trimEnd('.')
        return "${s}x"
    }
}
