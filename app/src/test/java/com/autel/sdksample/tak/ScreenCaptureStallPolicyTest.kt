package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the encoder stall watchdog.
 *
 * ⚠ **THE FAILURE THIS GUARDS AGAINST COSTS A REBOOT.** A stalled encoder stopped the release
 * fence on the MediaProjection VirtualDisplay from signalling, SurfaceFlinger's main thread sat
 * in `Fence::waitForever`, and the whole controller died.
 *
 * ⚠ **AND THE FALSE POSITIVE COSTS A TEAM THEIR VIDEO.** These tests exist mostly to hold the
 * line between "stopped" and "slow", because getting that wrong is the expensive mistake and it
 * is the easy one to make.
 */
class ScreenCaptureStallPolicyTest {

    private val t0 = 1_000_000L

    // ---- The line between STOPPED and SLOW ----

    /**
     * ⚠ THE ONE THAT MATTERS. On one measured run the encoder dipped to 10.9-11.8 fps six
     * times in 49 minutes and every dip recovered on its own. At ~11 fps a frame arrives about
     * every 90 ms, so the mark is never more than a fraction of a second old. A watchdog that
     * fired on a slow encoder would have killed a working stream six times.
     */
    @Test
    fun `a slow encoder is not a stalled one`() {
        // Worst dip seen: 10.9 fps -> a frame every ~92 ms.
        for (gapMs in listOf(67L, 92L, 200L, 500L, 999L)) {
            assertFalse("a $gapMs ms gap is a dip, not a stall",
                ScreenCaptureStallPolicy.isStalled(true, t0, t0 + gapMs))
        }
    }

    /** Even a very bad second — 1 fps — is still producing, and must survive. */
    @Test
    fun `even one frame per second survives`() {
        assertFalse(ScreenCaptureStallPolicy.isStalled(true, t0, t0 + 1_000))
        assertFalse(ScreenCaptureStallPolicy.isStalled(true, t0, t0 + 5_000))
    }

    /** At the threshold and past it, it fires. */
    @Test
    fun `nothing at all for the full window is a stall`() {
        assertFalse(ScreenCaptureStallPolicy.isStalled(
            true, t0, t0 + ScreenCaptureStallPolicy.STALL_MS - 1))
        assertTrue(ScreenCaptureStallPolicy.isStalled(
            true, t0, t0 + ScreenCaptureStallPolicy.STALL_MS))
        assertTrue(ScreenCaptureStallPolicy.isStalled(
            true, t0, t0 + ScreenCaptureStallPolicy.STALL_MS * 10))
    }

    /**
     * ⚠ The window is GENEROUS on purpose and must stay so. Firing wrongly takes video from a
     * team in the field; firing late costs a few more seconds of a feed that already stopped.
     * It must also stay well past any repaint interval this application has, because a virtual
     * display only produces a frame when the content changes.
     */
    @Test
    fun `the window stays generous`() {
        assertEquals(10_000L, ScreenCaptureStallPolicy.STALL_MS)
        assertTrue("must be far past the 500 ms HUD repaint",
            ScreenCaptureStallPolicy.STALL_MS >= 5_000L)
    }

    // ---- The states where it must never fire ----

    /** A stopped stream produces no output for an excellent reason. */
    @Test
    fun `a stream that is not running never stalls`() {
        assertFalse(ScreenCaptureStallPolicy.isStalled(
            false, t0, t0 + ScreenCaptureStallPolicy.STALL_MS * 100))
    }

    /** Start-up — the codec configuring, the first IDR — is not a stall. */
    @Test
    fun `before the first mark it never fires`() {
        assertFalse(ScreenCaptureStallPolicy.isStalled(true, -1L, t0))
        assertFalse(ScreenCaptureStallPolicy.isStalled(
            true, -1L, t0 + ScreenCaptureStallPolicy.STALL_MS * 100))
    }

    /** A mark in the future (a clock that moved) must not read as a huge age. */
    @Test
    fun `a mark ahead of now does not fire`() {
        assertFalse(ScreenCaptureStallPolicy.isStalled(true, t0 + 5_000, t0))
    }
}
