package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

/**
 * Pins the crosshair-to-ground solve: the point is where
 * the camera ray FIRST meets the terrain, on flat ground and on slopes that break a naive
 * fixed-point iteration; straight down lands under the aircraft; far rays clamp.
 */
class CameraSlantPointTerrainTest {

    private val lat0 = 45.2
    private val lon0 = -100.9

    /** Terrain rising (or falling) [grade] metres per metre northward from [base] m. */
    private fun slope(base: Double, grade: Double): (Double, Double) -> Double? = { lat, _ ->
        base + grade * (lat - lat0) * 111_195.0
    }

    private fun range(p: CameraSlantPoint.GroundPoint) =
        CameraSlantPoint.distanceMeters(lat0, lon0, p.lat, p.lon)

    @Test
    fun `flat terrain gives the flat answer`() {
        val p = CameraSlantPoint.compute(lat0, lon0, 100.0, 0.0, -20.0,
            elevationAt = slope(150.0, 0.0), aircraftMslMeters = 250.0)
        assertEquals(100.0 / tan(Math.toRadians(20.0)), range(p), 1.0)
        assertEquals(150.0, p.elevationMeters, 0.01)
    }

    /** 30% upslope at -10° pitch: a fixed-point iteration diverges here (slope > tan 10° = 18%). */
    @Test
    fun `a steep upslope is solved on the ray`() {
        val terrain = slope(150.0, 0.30)
        val p = CameraSlantPoint.compute(lat0, lon0, 100.0, 0.0, -10.0,
            elevationAt = terrain, aircraftMslMeters = 250.0)
        val r = range(p)
        val rayH = 250.0 - r * tan(Math.toRadians(10.0))
        assertEquals("the point must lie on the camera ray", rayH, p.elevationMeters, 0.6)
        // Analytic: 250 - r tan10 = 150 + 0.3 r  ->  r = 100 / (0.3 + tan10)
        assertEquals(100.0 / (0.30 + tan(Math.toRadians(10.0))), r, 1.0)
    }

    @Test
    fun `a downslope is solved on the ray`() {
        val p = CameraSlantPoint.compute(lat0, lon0, 100.0, 0.0, -20.0,
            elevationAt = slope(150.0, -0.10), aircraftMslMeters = 250.0)
        assertEquals(100.0 / (tan(Math.toRadians(20.0)) - 0.10), range(p), 1.0)
    }

    /** A -1.25 aim offset on a nadir gimbal gives -91.25, which must not put a point 300 m forward. */
    @Test
    fun `past nadir lands under the aircraft`() {
        val flat = CameraSlantPoint.compute(lat0, lon0, 60.0, 0.0, -91.25)
        assertTrue("flat: ${range(flat)}", range(flat) < 1.0)
        val dted = CameraSlantPoint.compute(lat0, lon0, 60.0, 0.0, -91.25,
            elevationAt = slope(150.0, 0.0), aircraftMslMeters = 210.0)
        assertTrue("dted: ${range(dted)}", range(dted) < 1.0)
    }

    /** A ray that meets no ground within 10 km clamps there instead of snapping to 300 m. */
    @Test
    fun `a ray beyond the maximum range clamps`() {
        val p = CameraSlantPoint.compute(lat0, lon0, 200.0, 0.0, -1.05)
        assertEquals(10_000.0, range(p), 50.0)
    }
}
