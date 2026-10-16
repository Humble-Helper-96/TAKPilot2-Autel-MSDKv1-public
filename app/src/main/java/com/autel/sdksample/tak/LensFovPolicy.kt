package com.autel.sdksample.tak

/**
 * Which horizontal field of view goes on the wire for the lens that is live.
 *
 * ⚠ **IN PIP THE CAMERA REPORTS THE THERMAL FIELD, AND THE TEAM SEES THE VISIBLE ONE.** Measured:
 * with the camera in PictureInPicture the 2 Hz status push carries `fov=33.0`, the thermal
 * lens's figure, and publishing it puts a 33° cone over a picture whose frame is the visible
 * lens's 66.8°. The camera point sits at the frame centre, inside the thermal window either
 * way, but the CONE would tell every client the aircraft was looking through a lens half as
 * wide as the picture it was streaming. Design decision: a blend publishes the VISIBLE cone.
 *
 * Pure, no SDK import, so the choice is pinned by a unit test rather than by a flight.
 *
 * @param liveHFov the horizontal field the camera itself reports, null before it has spoken.
 * @param calibratedHFov the application's calibratable visible-lens figure.
 * @param irHFov the thermal constant, the fallback for a camera that has not reported yet.
 */
internal fun publishedHFov(
    lens: AutelTakBridge.Lens,
    liveHFov: Double?,
    calibratedHFov: Double,
    irHFov: Double,
): Double = when (lens) {
    // The camera's live figure says "thermal" while the picture is the visible frame: the
    // calibrated visible figure wins, whether or not the camera has spoken.
    AutelTakBridge.Lens.BLEND -> calibratedHFov
    // The camera reports the field for whatever lens is live, thermal included, so when it is
    // talking the lens is not consulted. The constants are the fallback for a camera that has
    // not reported yet.
    AutelTakBridge.Lens.IR -> liveHFov ?: irHFov
    // A thermal-sized live figure under a VISIBLE belief is a contradiction: the camera is
    // really in PIP (a maximise that answered OK and did not happen), and the picture is the
    // visible frame. Taking 33 there draws every marker 2.2x too far out.
    AutelTakBridge.Lens.EO -> liveHFov?.takeIf { it >= EO_LIVE_MIN_FRACTION * calibratedHFov }
        ?: calibratedHFov
}

/** Below this share of the visible field, a live figure on the visible lens is not visible. */
internal const val EO_LIVE_MIN_FRACTION = 0.75

/**
 * The vertical field that pairs with [hDeg] under the live video aspect, in tangent space —
 * the only pairing that is self-consistent for a rectilinear lens:
 * `tan(hFov/2) / tan(vFov/2) == frameWidth / frameHeight`. Pure; [TakBridgeHolder.vFovFor]
 * calls it with the live aspect.
 */
internal fun vFovForAspect(hDeg: Double, aspect: Double): Double =
    2.0 * Math.toDegrees(Math.atan(Math.tan(Math.toRadians(hDeg / 2.0)) / aspect))
