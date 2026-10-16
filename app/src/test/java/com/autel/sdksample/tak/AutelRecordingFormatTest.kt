package com.autel.sdksample.tak

import com.autel.common.camera.media.VideoEncodeFormat
import com.autel.common.camera.media.VideoFps
import com.autel.common.camera.media.VideoResolution
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-back comparison behind the recording format. The point of the object is that the
 * camera is not believed, so the one piece of logic that decides believed-or-not is pinned.
 */
class AutelRecordingFormatTest {

    @Test
    fun `the camera agrees only when all three match`() {
        assertTrue(AutelRecordingFormat.agrees(
            VideoResolution.Resolution_1920x1080,
            VideoFps.FrameRate_30ps,
            VideoEncodeFormat.H264))
    }

    @Test
    fun `a camera that kept 4K does not agree`() {
        // The case this exists to catch: the write reports OK and the camera keeps what Autel
        // Explorer left it in, so the card fills at the old rate and nothing says why.
        assertFalse(AutelRecordingFormat.agrees(
            VideoResolution.Resolution_3840x2160,
            VideoFps.FrameRate_30ps,
            VideoEncodeFormat.H264))
    }

    @Test
    fun `the codec alone reverting does not agree`() {
        // ⚠ H.265 is the WRONG answer here now, and deliberately so: it writes the same bytes
        // as H.264 on this camera and is harder to play back, so a camera that quietly kept it
        // must still read as a disagreement.
        assertFalse(AutelRecordingFormat.agrees(
            VideoResolution.Resolution_1920x1080,
            VideoFps.FrameRate_30ps,
            VideoEncodeFormat.H265))
    }

    @Test
    fun `the frame rate alone reverting does not agree`() {
        assertFalse(AutelRecordingFormat.agrees(
            VideoResolution.Resolution_1920x1080,
            VideoFps.FrameRate_60ps,
            VideoEncodeFormat.H264))
    }

    @Test
    fun `an unknown is never a match`() {
        // ⚠ "I do not know" is not "1080p H.265". Collapsing the two would report a silent
        // revert as a success, which is the exact failure the read-back exists to find.
        assertFalse(AutelRecordingFormat.agrees(null, null, null))
        assertFalse(AutelRecordingFormat.agrees(
            VideoResolution.UNKNOWN, VideoFps.UNKNOWN, VideoEncodeFormat.UNKNOWN))
        assertFalse(AutelRecordingFormat.agrees(
            VideoResolution.Resolution_1920x1080, null, VideoEncodeFormat.H264))
    }
}
