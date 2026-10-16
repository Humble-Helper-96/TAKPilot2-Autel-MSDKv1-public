package com.autel.sdksample.tak

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import org.osmdroid.views.MapView

/**
 * osmdroid [MapView] with pilot navigation gestures disabled — a locked mini-map, the
 * osmdroid equivalent of MapLibre's `uiSettings.setAllGesturesEnabled(false)` (design
 * decision): no pan, no zoom, no rotate. North stays up because the map orientation is never
 * changed, and zoom stays wherever [FlightActivity] pins it; the per-tick recenter on the
 * aircraft is the only thing that ever moves the camera.
 *
 * **Why this shape.** MapLibre/Mapbox has one `setAllGesturesEnabled` switch that still
 * leaves click listeners working. osmdroid has no equivalent, and two obvious shortcuts are both
 * wrong:
 *  - `setOnTouchListener { _, _ -> true }` locks the map but ALSO kills tapping an inbound TAK
 *    contact to hide it locally — [android.view.View.dispatchTouchEvent] consults the listener
 *    before `onTouchEvent`, so osmdroid's overlays never see the gesture at all.
 *  - Overriding `onScroll`/`onFling`/`onDoubleTap` doesn't compile: osmdroid's [MapView] is not
 *    itself the gesture listener (verified against osmdroid 6.1.14 — it holds a *private*
 *    `mGestureDetector` with an internal listener class), so there is nothing to override.
 *
 * So this consumes every touch WITHOUT calling `super.onTouchEvent` — which is what actually
 * suppresses pan/fling/double-tap-zoom, since all of those live in that private detector — and
 * runs its own detector purely to forward confirmed single taps to the overlay manager, keeping
 * marker taps alive. Pinch-zoom is off separately via `setMultiTouchControls(false)` at the call
 * site (that also nulls osmdroid's multi-touch controller, so `dispatchTouchEvent`'s multitouch
 * branch is skipped before it can reach us).
 */
class LockedMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MapView(context, attrs) {

    /**
     * Double-tap on the map body. Set by [FlightActivity] to grow/shrink the mini-map.
     *
     * Free to use because the WIDE/NEAR zoom control is a separate BUTTON layered over the map,
     * not a tap on the map itself — so nothing else here wants this gesture. The detector below
     * already distinguishes the two: a double tap raises this and NEVER raises
     * `onSingleTapConfirmed`, so it cannot also register as a marker tap.
     */
    var onDoubleTap: (() -> Unit)? = null

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            // onSingleTapConfirmed, not onSingleTapUp: the "confirmed" variant waits out the
            // double-tap timeout, which is what Marker.onSingleTapConfirmed expects and what
            // keeps a stray double-tap from registering as two separate marker hits.
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean =
                overlayManager?.onSingleTapConfirmed(e, this@LockedMapView) ?: false

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val handler = onDoubleTap ?: return false
                handler()
                return true
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        tapDetector.onTouchEvent(event)
        // Always consume. Deliberately never calls super.onTouchEvent — that is the whole lock:
        // pan, fling and double-tap zoom all live in osmdroid's own detector inside it.
        return true
    }
}
