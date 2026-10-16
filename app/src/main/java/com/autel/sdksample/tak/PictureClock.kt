package com.autel.sdksample.tak

import android.os.SystemClock

/**
 * How old the picture on screen is, and how old the aircraft data is: the two ages the AR
 * overlay and the marker drop must check before they trust the live pose.
 *
 * ⚠ **THE POSE IS LIVE WHEN THE PICTURE IS NOT.** A lens change freezes the picture for
 * 0.7-2 s and a photo/video mode change once froze it for 41 s, while the telemetry went on. The
 * overlay would then draw over a frozen picture from the CURRENT pose, and a drop would land
 * where the camera points now, not where the frozen crosshair shows. After a link loss it is
 * the other way: the pose freezes while the pilot can still tap a drop.
 */
object PictureClock {
    /** Older than this, the picture is frozen for AR and drops. A 30 fps feed is ~33 ms. */
    const val PICTURE_STALE_MS = 600L

    /** Older than this, the aircraft data is too old to aim with. */
    const val TELEMETRY_STALE_MS = 2_000L

    @Volatile private var lastFrameMs = 0L

    /** One store, on the render thread, per frame. */
    fun onFrame() { lastFrameMs = SystemClock.elapsedRealtime() }

    /** Milliseconds since the last frame, or null before the first one. */
    fun pictureAgeMs(): Long? =
        if (lastFrameMs == 0L) null else SystemClock.elapsedRealtime() - lastFrameMs

    /** True when the picture is known to be frozen. Unknown (no frame yet) is not "frozen". */
    fun pictureFrozen(): Boolean = (pictureAgeMs() ?: 0L) > PICTURE_STALE_MS

    /** True when the aircraft data is missing or too old. */
    fun telemetryStale(): Boolean =
        (TakBridgeHolder.telemetryAgeMs() ?: Long.MAX_VALUE) > TELEMETRY_STALE_MS
}
