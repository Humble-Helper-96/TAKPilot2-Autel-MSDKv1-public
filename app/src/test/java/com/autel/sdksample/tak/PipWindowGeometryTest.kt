package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Test

class PipWindowGeometryTest {

    @Test
    fun `the window sits where the camera drew it`() {
        // The controller's filled frame: 2730 wide, 1536 high, centred in a 2048-wide view
        // (341 px overflow each side). The captures showed the window at about x 445–1567,
        // y 246–1178 in the 2048x1536 screen.
        val frame = PipWindowGeometry.Box(-341.0, 0.0, 2389.0, 1536.0)
        val w = PipWindowGeometry.window(frame)
        assertEquals(432.0, w.left, 30.0)
        assertEquals(1524.0, w.right, 30.0)
        assertEquals(230.0, w.top, 30.0)
        assertEquals(1162.0, w.bottom, 30.0)
    }

    @Test
    fun `the window is up and left of the frame centre, never centred`() {
        val frame = PipWindowGeometry.Box(0.0, 0.0, 1280.0, 720.0)
        val w = PipWindowGeometry.window(frame)
        val cx = (w.left + w.right) / 2; val cy = (w.top + w.bottom) / 2
        assertEquals(640.0 - 0.006 * 1280, cx, 1e-9)
        assertEquals(360.0 - 0.036 * 720, cy, 1e-9)
    }
}
