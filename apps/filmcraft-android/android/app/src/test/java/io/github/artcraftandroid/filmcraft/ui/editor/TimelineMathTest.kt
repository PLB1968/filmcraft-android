package io.github.artcraftandroid.filmcraft.ui.editor

import io.github.artcraftandroid.filmcraft.ui.home.cardDuration
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineMathTest {
    @Test
    fun framesAndTicksRoundTripAtCommonRates() {
        for ((num, den) in listOf(30L to 1L, 24L to 1L, 30000L to 1001L, 60L to 1L, 25L to 1L)) {
            for (f in listOf(0L, 1L, 29L, 30L, 1799L, 108_000L)) {
                assertEquals("$num/$den frame $f", f, TimelineMath.frames(TimelineMath.tickOfFrame(f, num, den), num, den))
            }
        }
        // an hour of 30 fps doesn't overflow
        assertEquals(108_000L, TimelineMath.frames(3600 * TPS, 30, 1))
    }

    @Test
    fun timecodesShowSecondsAndFramesLikeTheReference() {
        assertEquals("0:00", TimelineMath.clock(0))
        assertEquals("0:04", TimelineMath.clock(4 * TPS + TPS / 2))
        assertEquals("1:05", TimelineMath.clock(65 * TPS))
        assertEquals("1:02:03", TimelineMath.clock((3600 + 120 + 3) * TPS))
        assertEquals("12", TimelineMath.frameOfSecond(TimelineMath.tickOfFrame(42, 30, 1), 30, 1))
        assertEquals("00", TimelineMath.frameOfSecond(-5, 30, 1))
        assertEquals("0.4s", TimelineMath.seconds1(TPS * 4 / 10))
        assertEquals("8.0", TimelineMath.badge(8 * TPS))
    }

    @Test
    fun theRulerPicksTheSmallestStepWithRoomForItsLabels() {
        // 62 px a second, labels need 100 px: every 2 seconds
        assertEquals(2 * TPS, TimelineMath.rulerStep(62f, 100f, 30, 1).ticks)
        // zoomed far out: minutes
        assertTrue(TimelineMath.rulerStep(0.5f, 100f, 30, 1).ticks >= 60 * TPS)
        // zoomed in: frames
        val s = TimelineMath.rulerStep(3000f, 100f, 30, 1)
        assertTrue(s.frames > 0)
        assertEquals("15f", TimelineMath.rulerLabel(TimelineMath.tickOfFrame(15, 30, 1), TimelineMath.Step(TimelineMath.tickOfFrame(15, 30, 1), 15), 30, 1))
        assertEquals("0:01", TimelineMath.rulerLabel(TPS, TimelineMath.Step(TimelineMath.tickOfFrame(15, 30, 1), 15), 30, 1))
    }

    @Test
    fun zoomStaysBetweenTheWholeTimelineAndAFewFrames() {
        val w = 1080f
        val lo = TimelineMath.minPxPerSecond(w, 60 * TPS)
        val hi = TimelineMath.maxPxPerSecond(w, 30, 1)
        assertEquals(lo, TimelineMath.clampZoom(0.001f, w, 60 * TPS, 30, 1), 0.001f)
        assertEquals(hi, TimelineMath.clampZoom(1e9f, w, 60 * TPS, 30, 1), 0.001f)
        assertTrue(lo < TimelineMath.defaultPxPerSecond(w) && TimelineMath.defaultPxPerSecond(w) < hi)
    }

    @Test
    fun screenPositionsAndTimesInvertAroundTheFixedPlayhead() {
        val ph = 10 * TPS
        val x = TimelineMath.xOf(12 * TPS, ph, 540f, 62f)
        assertEquals(540f + 124f, x, 0.01f)
        assertEquals((12 * TPS).toDouble(), TimelineMath.timeAt(x, ph, 540f, 62f).toDouble(), TPS / 1000.0)
    }

    @Test
    fun theMainTrackSitsInTheMiddleWhenEverythingFits() {
        val tops = TimelineMath.laneTops(listOf(48f, 64f, 48f), 1, 2f, 600f, 0f)
        // main track centred: its centre at 300
        assertEquals(300f, tops[1] + 32f, 0.01f)
        // too tall: no gap above the first lane when scrolled to the top
        val tall = TimelineMath.laneTops(List(20) { 48f }, 10, 2f, 300f, -10_000f)
        assertTrue(tall[0] <= 0f)
    }

    @Test
    fun aDraggedClipLandsInTheSlotUnderTheFinger() {
        val starts = listOf(0L, 10L, 20L)
        val ends = listOf(10L, 20L, 30L)
        assertEquals(0, TimelineMath.slotAt(2, starts, ends))
        assertEquals(1, TimelineMath.slotAt(9, starts, ends))
        assertEquals(3, TimelineMath.slotAt(29, starts, ends))
    }

    @Test
    fun theSpeedSliderRunsThroughEvenlySpacedMarks() {
        val anchors = doubleArrayOf(0.1, 1.0, 2.0, 5.0, 10.0)
        assertEquals(1.0, TimelineMath.piecewise(0.25f, anchors), 1e-9)
        assertEquals(3.5, TimelineMath.piecewise(0.625f, anchors), 1e-9)
        assertEquals(0.625f, TimelineMath.piecewiseFraction(3.5, anchors), 1e-6f)
        assertEquals(0f, TimelineMath.piecewiseFraction(0.01, anchors), 0f)
        assertEquals("0.25x", TimelineMath.speedLabel(0.25))
        assertEquals("1.5x", TimelineMath.speedLabel(1.5))
        assertEquals("2x", TimelineMath.speedLabel(2.0))
    }

    @Test
    fun projectCardsRoundTheLengthUp() {
        assertEquals(":07", cardDuration(6 * TPS + 1))
        assertEquals("1:05", cardDuration(65 * TPS))
        assertEquals("1:00:00", cardDuration(3600 * TPS))
        assertEquals(":00", cardDuration(0))
    }

    @Test
    fun lanesNeverLeaveGapsAboveTheFirstOrBelowTheLast() {
        val tops = TimelineMath.laneTops(listOf(64f), 0, 2f, 100f, 0f)
        assertArrayEquals(floatArrayOf(18f), tops, 0.01f)
    }

    @Test
    fun desktopTimecodesCountHoursMinutesSecondsAndFrames() {
        assertEquals("00:00:00:00", TimelineMath.timecode(0, 30, 1))
        assertEquals("00:00:01:12", TimelineMath.timecode(TimelineMath.tickOfFrame(42, 30, 1), 30, 1))
        assertEquals("01:02:03:00", TimelineMath.timecode((3600 + 120 + 3) * TPS, 24, 1))
        // 29.97 counts 30 frames a second (non-drop-frame)
        assertEquals("00:00:01:00", TimelineMath.timecode(TimelineMath.tickOfFrame(30, 30000, 1001), 30000, 1001))
        assertEquals("00:00:00:00", TimelineMath.timecode(-5, 30, 1))
    }
}
