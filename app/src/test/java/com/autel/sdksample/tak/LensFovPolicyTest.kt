package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Test

class LensFovPolicyTest {

    private val eo = 66.8      // the calibrated visible figure
    private val ir = 33.0      // the thermal constant
    private val liveEo = 65.8  // what the camera reports on the visible lens
    private val liveIr = 33.0  // what the camera reports on the thermal lens AND in PIP

    @Test
    fun `the camera's live figure wins on a single lens`() {
        assertEquals(liveEo, publishedHFov(AutelTakBridge.Lens.EO, liveEo, eo, ir), 0.0)
        assertEquals(liveIr, publishedHFov(AutelTakBridge.Lens.IR, liveIr, eo, ir), 0.0)
    }

    @Test
    fun `the constants are the fallback before the camera has spoken`() {
        assertEquals(eo, publishedHFov(AutelTakBridge.Lens.EO, null, eo, ir), 0.0)
        assertEquals(ir, publishedHFov(AutelTakBridge.Lens.IR, null, eo, ir), 0.0)
    }

    @Test
    fun `a blend publishes the visible cone even though the camera says thermal`() {
        // Measured: in PictureInPicture the status push carried fov=33.0 and the bridge
        // published a 33° cone over a 66.8° picture. Design decision: the visible cone.
        assertEquals(eo, publishedHFov(AutelTakBridge.Lens.BLEND, liveIr, eo, ir), 0.0)
        assertEquals(eo, publishedHFov(AutelTakBridge.Lens.BLEND, null, eo, ir), 0.0)
    }

    /** The app believes VISIBLE, the camera is really in PIP and reports the thermal 33. */
    @Test
    fun `a thermal-sized figure on the visible lens is not believed`() {
        assertEquals(eo, publishedHFov(AutelTakBridge.Lens.EO, liveIr, eo, ir), 0.0)
        // A real zoom-free visible figure a few percent off the calibration is still believed.
        assertEquals(liveEo, publishedHFov(AutelTakBridge.Lens.EO, liveEo, eo, ir), 0.0)
    }
}
