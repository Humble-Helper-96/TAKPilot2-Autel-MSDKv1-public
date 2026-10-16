package com.autel.sdksample.tak

/**
 * Where the camera draws the thermal window inside the visible frame in PIP, as fractions of
 * the frame. MEASURED on this camera (XL726), not derived: the FOV ratio
 * predicts 0.46 wide by 0.65 high and the camera draws 0.40 by 0.61, offset about 1.6 % of the
 * width to the LEFT and 4.7 % of the height UP — that offset is the thermal lens's boresight
 * against the visible lens's, and it is why the window is not centred on the crosshair.
 *
 * Used to put the maximise control on the window's top-right corner. A few per cent of error
 * puts the control a few pixels off a corner nobody measures; it does not affect the picture,
 * the wire or a marker. Zoom does not enter — the camera refuses zoom in PIP.
 *
 * No Android import, so [PipWindowGeometryTest] can pin the corner without a device.
 */
object PipWindowGeometry {
    const val WIDTH_FRACTION = 0.40
    const val HEIGHT_FRACTION = 0.607
    // Re-measured on the controller with the control in the corner: the window's
    // centre sits 0.6 % of the width left and 3.6 % of the height above the frame centre.
    const val CENTRE_DX_FRACTION = -0.006
    const val CENTRE_DY_FRACTION = -0.036

    /** A rectangle as left, top, right, bottom in the same units as the frame rect. */
    data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double)

    /**
     * The thermal window inside a visible frame drawn at [frame] (the AR video rect: the whole
     * frame, which may overflow the view when the picture is filled).
     */
    fun window(frame: Box): Box {
        val w = frame.right - frame.left
        val h = frame.bottom - frame.top
        val cx = (frame.left + frame.right) / 2 + CENTRE_DX_FRACTION * w
        val cy = (frame.top + frame.bottom) / 2 + CENTRE_DY_FRACTION * h
        val hw = WIDTH_FRACTION * w / 2
        val hh = HEIGHT_FRACTION * h / 2
        return Box(cx - hw, cy - hh, cx + hw, cy + hh)
    }
}
