package com.autel.sdksample.tak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomVerifyPolicyTest {

    @Test
    fun `the camera landing on the requested level is obeyed`() {
        assertFalse(zoomWriteWasIgnored(requestedRaw = 400, reportedRaw = 400))
    }

    @Test
    fun `the camera's own quantisation is not a fault`() {
        // The pill shows fractional labels because the camera does not always land on a round
        // number. A few hundredths either way is the camera being itself, not a lost write.
        assertFalse(zoomWriteWasIgnored(requestedRaw = 400, reportedRaw = 415))
        assertFalse(zoomWriteWasIgnored(requestedRaw = 400, reportedRaw = 385))
        assertFalse(zoomWriteWasIgnored(requestedRaw = 400, reportedRaw = 425))
    }

    @Test
    fun `a write that never left the application is caught`() {
        // The shape of the fault: asked for 4x, the camera reported OK, and it is still at 1x.
        assertTrue(zoomWriteWasIgnored(requestedRaw = 400, reportedRaw = 100))
        assertTrue(zoomWriteWasIgnored(requestedRaw = 1600, reportedRaw = 100))
    }

    @Test
    fun `the tolerance cannot swallow a whole ladder step`() {
        // ⚠ THE PROPERTY THAT MATTERS. The narrowest gap in the ladder is 100 raw (1x to 2x).
        // If the tolerance ever grew past half of that, a camera sitting one rung away from the
        // request would read as obeyed and the check would be worthless at the short end, which
        // is where the pilot spends most of a flight.
        val narrowestStep = ZoomLadder.RUNGS_RAW.toList()
            .zipWithNext { a, b -> b - a }
            .min()
        assertTrue(ZOOM_TOLERANCE_RAW * 2 < narrowestStep)
        ZoomLadder.RUNGS_RAW.toList().zipWithNext { a, b ->
            assertTrue("$a and $b must not be confusable", zoomWriteWasIgnored(a, b))
        }
    }
}
