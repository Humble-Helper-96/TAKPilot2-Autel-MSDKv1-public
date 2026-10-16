package com.autel.sdksample.tak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the spurious photo-failure predicate (observed: DONE, then "The take
 *  photo is failed" ~20 ms later for a capture that was on the card). */
class MediaCallbackPolicyTest {

    private val incidentString = "The take photo is failed"

    @Test
    fun duplicateFailureInsideWindowIsSpurious() {
        assertTrue(isSpuriousPhotoFailure(incidentString, sincePhotoDoneMs = 20L))
        assertTrue(isSpuriousPhotoFailure(incidentString, sincePhotoDoneMs = 2_999L))
    }

    @Test
    fun failureAfterWindowIsReal() {
        assertFalse(isSpuriousPhotoFailure(incidentString, sincePhotoDoneMs = 5_000L))
    }

    @Test
    fun failureWithNoPriorCaptureIsReal() {
        assertFalse(isSpuriousPhotoFailure(incidentString, sincePhotoDoneMs = Long.MAX_VALUE))
    }

    @Test
    fun unrelatedFailureInsideWindowIsReal() {
        assertFalse(isSpuriousPhotoFailure("SD card removed", sincePhotoDoneMs = 20L))
    }

    // ---- photoDoneNamesAFile: a test flight, cross-referenced with the SD card ----

    @Test
    fun `a done carrying a thumbnail url names a file`() {
        // The shape every confirmed capture has. All 15 presses of that flight
        // logged this and all 15 files are on the card.
        assertTrue(photoDoneNamesAFile(
            "http://127.0.0.1:8080/thumbnail?path=/DCIM/100MEDIA/MAX_0047.JPG&type=0"))
    }

    @Test
    fun `a done with no detail names nothing`() {
        // A done carrying no detail points at nothing on the card, thus it cannot confirm a
        // capture. ⚠ Every url-less done SEEN so far has been the trailing echo of a named one
        // (12 of them on that flight) — no capture has been observed to fail. This pins a guard,
        // not a repair. See the withdrawal note on photoDoneNamesAFile.
        assertFalse(photoDoneNamesAFile(null))
        assertFalse(photoDoneNamesAFile(""))
        assertFalse(photoDoneNamesAFile("   "))
    }

    @Test
    fun `a failure 1ms after an UNCONFIRMED done is not spurious`() {
        // A url-less done must not move the timestamp, so the window is measured from the last
        // CONFIRMED capture — 13 seconds earlier here — and this is a real failure rather than
        // the firmware's duplicate.
        assertFalse(isSpuriousPhotoFailure(incidentString, sincePhotoDoneMs = 13_091L))
    }

    @Test
    fun `a failure 27ms after a CONFIRMED done is still spurious`() {
        // The ordinary sequence: done with a url, then a url-less done, then the failure, for
        // a capture that is on the card. This must stay suppressed.
        assertTrue(isSpuriousPhotoFailure(incidentString, sincePhotoDoneMs = 27L))
    }

    // ---- isPhotoFailure: the pilot is told, and an empty description does not silence it ----

    @Test
    fun `a failure that names a photo is a photo failure`() {
        assertTrue(isPhotoFailure(incidentString, sinceUnconfirmedDoneMs = Long.MAX_VALUE))
        // The firmware uses both words. A match on one only would be a coin toss.
        assertTrue(isPhotoFailure("Take picture failed", sinceUnconfirmedDoneMs = Long.MAX_VALUE))
    }

    @Test
    fun `a failure with NO description right after an unconfirmed done is a photo failure`() {
        // ⚠ THE REGRESSION THIS PINS. AutelError.description can be null, and the caller has
        // only "unknown" to put in its place. Word-matching alone calls that "not a photo",
        // sets no failed flag, and the flight screen then falls through to "Photo Saved". The
        // url-less done 1 ms earlier is what identifies the shutter it belongs to.
        assertTrue(isPhotoFailure("unknown", sinceUnconfirmedDoneMs = 1L))
        assertTrue(isPhotoFailure("", sinceUnconfirmedDoneMs = 999L))
    }

    @Test
    fun `the url-less done that trails a real capture is an echo, not a lost still`() {
        // Done with a url, then a url-less done a few ms later — the shape of all 15 confirmed
        // captures on that flight. If that echo armed the clock, a firmware duplicate failure
        // carrying no description would be read as a loss and the pilot would be told a SAVED
        // photo was lost.
        assertTrue(isTrailingDoneOfAConfirmedCapture(sinceConfirmedDoneMs = 5L))
        // A url-less done with no confirmed capture behind it, 13 s after the last good one.
        // That is the shape a lost still would have, and it must arm the clock.
        assertFalse(isTrailingDoneOfAConfirmedCapture(sinceConfirmedDoneMs = 13_091L))
        assertFalse(isTrailingDoneOfAConfirmedCapture(sinceConfirmedDoneMs = Long.MAX_VALUE))
    }

    @Test
    fun `an unrelated failure long after any unconfirmed done is not a photo failure`() {
        // A recording fault must not put a photo notice on the flight screen.
        assertFalse(isPhotoFailure("SD card removed", sinceUnconfirmedDoneMs = Long.MAX_VALUE))
        assertFalse(isPhotoFailure("unknown", sinceUnconfirmedDoneMs = 1_000L))
    }
}
