package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraViewTest {

    @Test
    fun `the camera's display mode maps to a view by name`() {
        assertEquals(CameraView.VISIBLE, CameraView.fromDisplayModeName("VISIBLE"))
        assertEquals(CameraView.IR, CameraView.fromDisplayModeName("IR"))
        assertEquals(CameraView.PIP, CameraView.fromDisplayModeName("PICTURE_IN_PICTURE"))
    }

    @Test
    fun `overlap is a blend and reads as PIP`() {
        // This firmware refuses Overlap (status -1, measured), but the SDK enum has it
        // and another firmware or Explorer could leave the camera in it. It has the video's
        // shape and the visible field, which is all a blend needs to be treated as.
        assertEquals(CameraView.PIP, CameraView.fromDisplayModeName("OVERLAP"))
    }

    @Test
    fun `an unknown or missing answer changes nothing`() {
        assertEquals(CameraView.VISIBLE, CameraView.fromDisplayModeName("UNKNOWN"))
        assertEquals(CameraView.VISIBLE, CameraView.fromDisplayModeName(null))
    }

    @Test
    fun `only the bare thermal sensor is shown whole and expects the thermal frame`() {
        // PIP is the 1280x720 composite (a recording off the card reads 1280x720p25). It
        // fills like the visible modes and the frame witness expects the video shape; a
        // "thermal" expectation there would flip the state back 1.5 s after every PIP write.
        assertTrue(CameraView.IR.fitsWhole)
        assertTrue(CameraView.IR.expectsThermalFrame)
        assertFalse(CameraView.PIP.fitsWhole)
        assertFalse(CameraView.PIP.expectsThermalFrame)
        assertFalse(CameraView.VISIBLE.fitsWhole)
        assertFalse(CameraView.VISIBLE.expectsThermalFrame)
    }

    @Test
    fun `the palette applies wherever the thermal sensor is on screen`() {
        assertFalse(CameraView.VISIBLE.hasThermal)
        assertTrue(CameraView.IR.hasThermal)
        assertTrue(CameraView.PIP.hasThermal)
    }

    @Test
    fun `a tap toggles visible and PIP, and from full thermal goes back to PIP`() {
        assertEquals(CameraView.PIP, CameraView.VISIBLE.next)
        assertEquals(CameraView.VISIBLE, CameraView.PIP.next)
        assertEquals(CameraView.PIP, CameraView.IR.next)
    }

    @Test
    fun `the window's corner control maximises and minimises, and is absent in visible`() {
        assertEquals(CameraView.IR, CameraView.PIP.maximised)
        assertEquals(CameraView.PIP, CameraView.IR.maximised)
        assertEquals(null, CameraView.VISIBLE.maximised)
    }

    @Test
    fun `the pill reads PIP unlit in visible and PIP lit in PIP and in full thermal`() {
        assertEquals("PIP", CameraView.VISIBLE.label); assertFalse(CameraView.VISIBLE.active)
        assertEquals("PIP", CameraView.PIP.label); assertTrue(CameraView.PIP.active)
        assertEquals("PIP", CameraView.IR.label); assertTrue(CameraView.IR.active)
    }

    @Test
    fun `each view has its lens`() {
        assertEquals(AutelTakBridge.Lens.EO, CameraView.VISIBLE.lens)
        assertEquals(AutelTakBridge.Lens.IR, CameraView.IR.lens)
        assertEquals(AutelTakBridge.Lens.BLEND, CameraView.PIP.lens)
    }
}
