package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The field the AR overlay projects with, per view. These pin two AR faults measured off a
 * lens-cycle test log: in thermal the overlay paired a
 * live 33° horizontal with a vertical derived from the calibrated 66.8° (55.6° for a 26.7°
 * lens), and in PIP it took the camera's thermal 33° across a 66.8° picture.
 */
class ArFovPolicyTest {

    private val calibrated = 66.8
    private val ir = 33.0
    private val video = 16.0 / 9.0     // 1280x720: visible and PIP
    private val thermal = 640.0 / 512.0 // the bare sensor, 5:4

    private fun arFov(lens: AutelTakBridge.Lens, live: Double?, aspect: Double): Pair<Double, Double> {
        val h = publishedHFov(lens, live, calibrated, ir)
        return h to vFovForAspect(h, aspect)
    }

    @Test
    fun `thermal pairs the live 33 with the vertical of THAT lens, not of the visible one`() {
        val (h, v) = arFov(AutelTakBridge.Lens.IR, 33.0, thermal)
        assertEquals(33.0, h, 0.01)
        // The camera itself reports 33.0 x 26.0. Before the fix this derived to 55.6.
        assertEquals(26.7, v, 0.1)
    }

    @Test
    fun `PIP projects with the visible field although the camera reports the thermal one`() {
        val (h, v) = arFov(AutelTakBridge.Lens.BLEND, 33.0, video)
        assertEquals(66.8, h, 0.01)
        assertEquals(40.7, v, 0.1)
    }

    @Test
    fun `visible pairs the live horizontal with its own vertical`() {
        val (h, v) = arFov(AutelTakBridge.Lens.EO, 65.8, video)
        assertEquals(65.8, h, 0.01)
        // The camera reports 65.8 x 39.9.
        assertEquals(39.9, v, 0.1)
    }

    @Test
    fun `the vertical follows the tangent identity`() {
        // tan(h/2)/tan(v/2) == aspect, by construction.
        val h = 50.0
        val v = vFovForAspect(h, 1.5)
        val ratio = Math.tan(Math.toRadians(h / 2)) / Math.tan(Math.toRadians(v / 2))
        assertEquals(1.5, ratio, 1e-9)
    }
}
