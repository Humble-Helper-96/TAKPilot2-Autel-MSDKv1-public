package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pins the AR overlay's camera-frame projection. The numbers were worked by hand by rotating
 * the target direction into the camera frame.
 */
class CameraProjectionTest {

    private val tol = 0.1

    @Test
    fun `on the axis is the centre, at any pitch`() {
        for (pitch in listOf(0.0, -15.0, -45.0, -89.0)) {
            val (az, el) = cameraFrameAngles(0.0, pitch, pitch)
            assertEquals(0.0, az, tol); assertEquals(0.0, el, tol)
        }
    }

    @Test
    fun `at the horizon the world's angles ARE the camera's`() {
        // The old mapping was right here, and only here.
        val (az, el) = cameraFrameAngles(20.0, -5.0, 0.0)
        assertEquals(20.0, az, tol)
        assertEquals(-5.0, el, 0.4)   // small coupling from the 20° azimuth, real and tiny
    }

    @Test
    fun `pitched down, a bearing difference is less sideways than it looks`() {
        // A target on the ground at the camera's own depression, 20° off the heading.
        assertEquals(17.2, cameraFrameAngles(20.0, -30.0, -30.0).first, tol)
        assertEquals(14.0, cameraFrameAngles(20.0, -45.0, -45.0).first, tol)
        assertEquals(9.8, cameraFrameAngles(20.0, -60.0, -60.0).first, tol)
        assertEquals(5.1, cameraFrameAngles(20.0, -75.0, -75.0).first, tol)
    }

    @Test
    fun `and it is also a little below the centre line, not on it`() {
        // The old mapping put every such target on the centre row (dElev = 0).
        assertEquals(-1.8, cameraFrameAngles(20.0, -45.0, -45.0).second, tol)
        assertEquals(-3.4, cameraFrameAngles(30.0, -60.0, -60.0).second, tol)
    }

    @Test
    fun `straight down, a bearing difference is a rotation about the crosshair`() {
        // Camera at −90°, target 5° up from nadir on four bearings: same off-axis distance,
        // four directions.
        val r = { b: Double -> cameraFrameAngles(b, -85.0, -90.0) }
        assertEquals(0.0, r(0.0).first, tol);   assertEquals(5.0, r(0.0).second, tol)
        assertEquals(5.0, r(90.0).first, tol);  assertEquals(0.0, r(90.0).second, tol)
        assertEquals(0.0, r(180.0).first, tol); assertEquals(-5.0, r(180.0).second, tol)
        assertEquals(-5.0, r(-90.0).first, tol); assertEquals(0.0, r(-90.0).second, tol)
    }

    @Test
    fun `left and right are mirror images`() {
        val l = cameraFrameAngles(-25.0, -40.0, -50.0)
        val r = cameraFrameAngles(25.0, -40.0, -50.0)
        assertEquals(-r.first, l.first, 1e-9)
        assertEquals(r.second, l.second, 1e-9)
    }

    @Test
    fun `behind the camera passes 90 degrees`() {
        val (az, _) = cameraFrameAngles(170.0, 0.0, 0.0)
        assertTrue(abs(az) > 90.0)
    }
}
