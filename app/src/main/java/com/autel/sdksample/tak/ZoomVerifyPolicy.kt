package com.autel.sdksample.tak

/**
 * Did the camera really go to the zoom this application asked for?
 *
 * ⚠ **THIS ONE DIAGNOSES; IT DOES NOT REPAIR, AND THAT IS THE DIFFERENCE FROM THE OTHER TWO.**
 * Unlike the lens and the home point, a zoom that does not take already CORRECTS itself:
 *
 *  - the camera reports its own `zoomScale` on the ~2 Hz push, and `AutelProductHolder` feeds
 *    that straight to `TakBridgeHolder.setLiveZoom`, thus the published `<sensor>` cone and the
 *    AR projection are right again within a tick. **Nothing wrong reaches the TAK team.**
 *  - `FlightActivity.seedZoomFromCamera` puts the camera's value back on the pill at the next
 *    connect or rocker release.
 *
 * So the pilot sees a picture that did not zoom and a label that fixes itself. What nobody gets
 * is a REASON, and that is what this adds: `seedZoomFromCamera` logs its correction at INFO with
 * one wording for two completely different events — "Autel Explorer moved the zoom", which is
 * expected and benign and is the case that function was written for, and "our own write was
 * ignored", which is a fault. One line cannot be read as either.
 *
 * ⚠ **NO PILOT NOTICE, DELIBERATELY.** An amber notice on the flight screen is for something
 * the pilot must act on. This condition shows itself in the picture, repairs its own label, and
 * puts nothing wrong on the wire — a notice would be noise on the one screen they fly from.
 * The log is the right audience. Do not "finish the job" by adding one.
 */

/**
 * How far the camera's reported zoom may sit from the requested value and still count as
 * obeyed, in the raw hundredths `setDigitalZoomScale` takes.
 *
 * ⚠ SMALL ENOUGH TO STAY INSIDE ONE LADDER STEP. The narrowest gap in [ZoomLadder.RUNGS_RAW] is
 * 100 raw — 1x to 2x — so a quarter of that cannot let one rung be mistaken for its neighbour.
 * It is there for the camera's own quantisation: the reported value is a ratio that has been
 * through the aircraft and back, and the zoom pill shows fractional labels ("2.4X") because the
 * camera does not always land on a round number.
 */
internal const val ZOOM_TOLERANCE_RAW = 25

/**
 * How long after the last write before the camera's reading is believed.
 *
 * ⚠ THE SAME WINDOW `seedZoomFromCamera` ALREADY USES, AND IT MUST STAY THE SAME. That guard
 * calls the camera's reading stale inside `ZOOM_SEED_SETTLE_MS`; a check that believed the
 * reading any sooner would be calling a write failed on exactly the data the rest of this
 * screen refuses to trust yet. A held rocker writes repeatedly, so each write restarts the
 * window and one check falls at the end of the burst rather than one per tick.
 */
internal const val ZOOM_VERIFY_SETTLE_MS = 1500L

/**
 * True when the camera's reported zoom contradicts what was asked for.
 *
 * @param requestedRaw what this application last wrote, in hundredths (100 = 1x).
 * @param reportedRaw the camera's own reading in the same units, from its status push.
 */
internal fun zoomWriteWasIgnored(requestedRaw: Int, reportedRaw: Int): Boolean =
    Math.abs(requestedRaw - reportedRaw) > ZOOM_TOLERANCE_RAW
