package com.autel.sdksample.tak

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a target sits in the CAMERA'S frame, given where it sits in the world's.
 *
 * ⚠ **THE WORLD'S ANGLES ARE NOT THE CAMERA'S.** The
 * overlay used to take the compass-bearing difference and the elevation difference and hand
 * them to the pixel mapping as if they were the camera's own left-right and up-down angles.
 * That is true only while the camera looks at the horizon. A bearing difference is a rotation
 * about the VERTICAL axis; once the gimbal pitches down, that rotation is partly sideways and
 * partly up-down in the camera's tilted frame, and the steeper the pitch the less sideways it
 * is. At −90° a bearing difference is not a sideways shift at all but a rotation about the
 * crosshair.
 *
 * Measured against this function for a target on the ground at the camera's own depression:
 *
 *     pitch −30°, 20° off the heading:  the old mapping was 2.8° wrong sideways
 *     pitch −45°, 20° off the heading:  6.0°
 *     pitch −60°, 20° off the heading:  10.2°
 *     pitch −75°, 20° off the heading:  14.9°
 *
 * The visible frame is 66° across and the thermal 33°, so at −60° a marker 20° off the heading
 * was drawn a sixth of the visible frame and a third of the thermal frame from where it
 * belonged. Zero at the centre and growing outward — which is exactly why the crosshair
 * self-test never caught it (that test is the centre) and why an earlier one-pixel check
 * passed, at a shallow pitch. This was the cause of markers drawn wildly off horizontally.
 *
 * ## The rotation
 *
 * The target's direction, with the camera's heading already removed, is
 *
 *     forward = cos(el)·cos(Δaz)     right = cos(el)·sin(Δaz)     up = sin(el)
 *
 * The camera's forward axis is pitched by `p` from the horizon, so its axes in that same frame
 * are `f = (cos p, 0, sin p)`, `r = (0, 1, 0)`, `u = (−sin p, 0, cos p)`. The target's
 * components along those are the dot products, and the camera-frame angles follow:
 *
 *     azCam = atan2(d·r, d·f)        elCam = atan2(d·u, d·f)
 *
 * Both are defined against the FORWARD component, so `tan(azCam) = right/forward` and
 * `tan(elCam) = up/forward` — the exact gnomonic ratios [ArOverlayView.project] scales into
 * pixels. Behind the camera `forward` is negative and the angles pass ±90°, which the
 * existing ±85° guard and the edge-arrow clamp already handle.
 *
 * Gimbal roll is not modelled here; its effect is small.
 *
 * Pure and SDK-free on purpose — pinned by [CameraProjectionTest].
 *
 * @param dBearingDeg the target's bearing minus the camera's, wrapped to −180..180.
 * @param targetElevDeg the target's elevation angle from the aircraft, from the horizontal.
 * @param cameraPitchDeg the camera's pitch from the horizontal, down negative.
 * @return the camera-frame azimuth (right positive) and elevation (up positive), degrees.
 */
internal fun cameraFrameAngles(
    dBearingDeg: Double,
    targetElevDeg: Double,
    cameraPitchDeg: Double,
): Pair<Double, Double> {
    val b = Math.toRadians(dBearingDeg)
    val e = Math.toRadians(targetElevDeg)
    val p = Math.toRadians(cameraPitchDeg)
    val fwd = cos(e) * cos(b)
    val right = cos(e) * sin(b)
    val up = sin(e)
    val alongF = fwd * cos(p) + up * sin(p)
    val alongR = right
    val alongU = -fwd * sin(p) + up * cos(p)
    return Math.toDegrees(atan2(alongR, alongF)) to Math.toDegrees(atan2(alongU, alongF))
}
