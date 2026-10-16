package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The frame shapes are the ones this camera really emits, taken from a flight log
 * and from [FlightActivity.armVideoFill]'s recorded measurements.
 */
class LensFramePolicyTest {

    private val thermal = 640f / 512f    // 1.250 — the thermal sensor's native size
    private val video = 1920f / 1080f    // 1.778 — what the flight log showed throughout
    private val video720 = 1280f / 720f  // 1.778 — the other visible video size
    private val still = 1280f / 960f     // 1.333 — a photo-mode frame

    @Test
    fun `the picture agrees when the lens really changed`() {
        assertEquals(Verdict.AGREES, lensAgreesWithFrame(believeIr = true, frameAspect = thermal))
        assertEquals(Verdict.AGREES, lensAgreesWithFrame(believeIr = false, frameAspect = video))
        assertEquals(Verdict.AGREES, lensAgreesWithFrame(believeIr = false, frameAspect = video720))
    }

    @Test
    fun `a lens change the camera ignored is caught`() {
        // Eight toggles, every one logging setDisplayMode OK, and the frame stayed 1920x1080.
        // The application believed IR was live and told the bridge so, thus the camera point
        // went to the whole TAK team tagged thermal over visible video.
        assertEquals(
            Verdict.CONTRADICTS,
            lensAgreesWithFrame(believeIr = true, frameAspect = video))
    }

    @Test
    fun `the reverse fault is caught too`() {
        // The same failure on the way back: the application believes it returned to visible
        // while the aircraft is still streaming thermal.
        assertEquals(
            Verdict.CONTRADICTS,
            lensAgreesWithFrame(believeIr = false, frameAspect = thermal))
    }

    @Test
    fun `a still's 4 to 3 frame accuses nobody`() {
        // ⚠ THE FALSE-ALARM CASE. 1.333 is nearer the thermal 1.25 than the video 1.778, so a
        // two-way rule would call this thermal and raise an alarm on a lens that changed
        // correctly. The middle band must stay silent in BOTH directions.
        assertEquals(
            Verdict.INCONCLUSIVE,
            lensAgreesWithFrame(believeIr = true, frameAspect = still))
        assertEquals(
            Verdict.INCONCLUSIVE,
            lensAgreesWithFrame(believeIr = false, frameAspect = still))
    }

    @Test
    fun `PIP is a video-shaped frame and must not be read as a failed lens change`() {
        // Measured: the camera composites the thermal picture into the 1280x720 visible
        // frame, thus the witness sees the video shape. The belief passed for PIP is "not the
        // thermal shape", and the composite AGREES with it.
        val believe = CameraView.PIP.expectsThermalFrame
        assertEquals(Verdict.AGREES, lensAgreesWithFrame(believeIr = believe, frameAspect = video720))
        // And a PIP write that did NOT take, while the camera still streams bare thermal, is
        // still caught.
        assertEquals(Verdict.CONTRADICTS, lensAgreesWithFrame(believeIr = believe, frameAspect = thermal))
    }

    @Test
    fun `no frame is no evidence`() {
        // Before the first frame arrives, and after a disconnect stops them.
        assertEquals(Verdict.INCONCLUSIVE, lensAgreesWithFrame(believeIr = true, frameAspect = 0f))
        assertEquals(Verdict.INCONCLUSIVE, lensAgreesWithFrame(believeIr = false, frameAspect = -1f))
        assertEquals(
            Verdict.INCONCLUSIVE,
            lensAgreesWithFrame(believeIr = true, frameAspect = Float.NaN))
    }
}
