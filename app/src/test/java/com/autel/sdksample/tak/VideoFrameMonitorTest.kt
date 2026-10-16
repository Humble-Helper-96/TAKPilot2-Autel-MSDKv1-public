package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameMonitorTest {

    /** 15 fps, the rate this stream runs at. */
    private val frame = 67L

    @Test
    fun `the first frame is never a stall`() {
        val m = VideoFrameMonitor()
        // There is nothing to measure from. Reporting the wait for the first picture would put
        // a stall in the log on every single connect.
        assertNull(m.onFrame(nowMs = 500_000L, pts = 0L))
    }

    @Test
    fun `an ordinary run says nothing until the summary falls due`() {
        val m = VideoFrameMonitor()
        var t = 0L
        m.onFrame(t, 0L)
        repeat(100) { t += frame; assertNull(m.onFrame(t, t * 1000)) }   // 6.7 s
    }

    @Test
    fun `a freeze is reported with its gap`() {
        val m = VideoFrameMonitor()
        m.onFrame(0L, 0L)
        m.onFrame(frame, 1000L)
        val r = m.onFrame(frame + 900L, 2000L) as VideoFrameMonitor.Report.Stall
        assertEquals(900L, r.gapMs)
        // First stall of the session has no previous one to measure from.
        assertEquals(-1L, r.sinceLastStallMs)
    }

    @Test
    fun `the timestamp delta is carried raw and nothing branches on it`() {
        // ⚠ THE UNITS ARE NOT DOCUMENTED. This pins that the monitor reports the SDK's own step
        // untouched — no scaling, no assumed microseconds — so the first flight can tell us what
        // it is. A diagnostic that guesses a unit lies about the thing it exists to explain.
        val m = VideoFrameMonitor()
        m.onFrame(0L, 7_000_000L)
        val r = m.onFrame(500L, 7_123_456L) as VideoFrameMonitor.Report.Stall
        assertEquals(123_456L, r.ptsDelta)
    }

    @Test
    fun `a burst of dropped frames does not bury itself in log lines`() {
        // ⚠ THE REGRESSION THIS PINS. The first cooldown was expressed in terms of the STALL
        // THRESHOLD, which limits nothing whenever the drops are spaced further apart than the
        // threshold — and that is every burst, since being later than the threshold is what
        // makes a frame count in the first place. It reported all twenty of twenty.
        //
        // Twenty frames 250 ms apart is five seconds of bad picture, which must read as a few
        // lines and not as twenty.
        val m = VideoFrameMonitor()
        var t = 0L
        m.onFrame(t, 0L)
        var stalls = 0
        repeat(20) {
            t += 250L
            if (m.onFrame(t, t * 1000) is VideoFrameMonitor.Report.Stall) stalls++
        }
        assertTrue("expected about one per second over 5 s, got $stalls of 20", stalls in 2..7)
    }

    @Test
    fun `two freezes separated by good picture are two reports`() {
        // The cooldown must not swallow a genuinely separate event.
        val m = VideoFrameMonitor()
        var t = 0L
        m.onFrame(t, 0L)
        t += 400L
        assertTrue(m.onFrame(t, 1000L) is VideoFrameMonitor.Report.Stall)
        // Good picture for longer than the cooldown, so the second freeze is its own event and
        // not the tail of the first.
        repeat(20) { t += frame; assertNull(m.onFrame(t, t * 1000)) }
        t += 400L
        val second = m.onFrame(t, t * 1000)
        assertTrue("second freeze must report, got $second",
            second is VideoFrameMonitor.Report.Stall)
    }

    @Test
    fun `the summary proves the monitor is alive`() {
        // ⚠ A DIAGNOSTIC THAT SAYS NOTHING IS INDISTINGUISHABLE FROM ONE THAT IS NOT RUNNING.
        val m = VideoFrameMonitor()
        var t = 0L
        m.onFrame(t, 0L)
        var summary: VideoFrameMonitor.Report.Summary? = null
        while (summary == null && t < 30_000L) {
            t += frame
            summary = m.onFrame(t, t * 1000) as? VideoFrameMonitor.Report.Summary
        }
        requireNotNull(summary)
        assertTrue("period ${summary.periodMs}", summary.periodMs >= 10_000L)
        // 15 fps in, 15 fps out, give or take the frame the period boundary lands on.
        assertTrue("fps ${summary.fps}", summary.fps in 14.0..16.0)
    }

    @Test
    fun `the summary carries the worst gap of its period`() {
        val m = VideoFrameMonitor()
        var t = 0L
        m.onFrame(t, 0L)
        t += 300L; m.onFrame(t, t * 1000)          // one bad gap
        var summary: VideoFrameMonitor.Report.Summary? = null
        while (summary == null && t < 30_000L) {
            t += frame
            summary = m.onFrame(t, t * 1000) as? VideoFrameMonitor.Report.Summary
        }
        assertEquals(300L, requireNotNull(summary).worstGapMs)
    }

    @Test
    fun `a deliberate break is not counted as a freeze`() {
        // A resync or a codec rebuild is OUR gap, not a fault.
        val m = VideoFrameMonitor()
        m.onFrame(0L, 0L)
        m.reset()
        assertNull(m.onFrame(5_000L, 9_000L))
    }
}
