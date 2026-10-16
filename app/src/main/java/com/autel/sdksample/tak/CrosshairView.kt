package com.autel.sdksample.tak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.autel.sdksample.R
import kotlin.math.hypot
import androidx.core.content.ContextCompat

/**
 * ATAK-UAS-Tool-style center reticle, drawn over the FPV video. Marks where the camera is
 * pointed, and its centre ring doubles as a marker-accuracy cue (see [setGimbalPitch]).
 *
 * **No content rect from Autel.** A video surface that is pillarboxed inside its view would
 * compute the video's real content rectangle and feed it in via [setVideoRect], because there
 * the video centre is NOT the view centre. Autel's video comes from the SDK's own
 * `AutelCodecView`, which is an opaque widget filling the container — it exposes no
 * content-rect callback, so [onSizeChanged] defaults [videoRect] to the full view bounds.
 *
 * That is correct as long as `AutelCodecView` fills its container without internal letterboxing.
 * If the 640T's feed turns out letterboxed inside the widget, the reticle
 * will sit at the view centre rather than the image centre and this needs a real rect — call
 * [setVideoRect] with it. Flagged rather than assumed: nothing here can detect that without the
 * actual aircraft.
 */
class CrosshairView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val videoRect = RectF()
    /** True once a caller has supplied a real content rect, so [onSizeChanged]'s full-bounds
     *  default stops overwriting it. */
    private var videoRectExplicit = false

    fun setVideoRect(rect: RectF) {
        videoRect.set(rect)
        videoRectExplicit = true
        invalidate()
    }

    /** Default the reticle to the view's own centre — see the class note on why Autel can't get
     *  a real video content rect from `AutelCodecView`. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!videoRectExplicit) {
            videoRect.set(0f, 0f, w.toFloat(), h.toFloat())
            invalidate()
        }
    }

    /**
     * Centre-ring colour as a marker-accuracy cue, driven by gimbal pitch AND whether DTED
     * covers the aircraft's current position.
     *
     * Ground-point accuracy falls off as `1/sin²(pitch)` — the shallower the look angle, the
     * more a given pitch or bearing error smears along the ground. So the same marker drop is
     * an order of magnitude tighter looking steeply down than obliquely, and the pilot has no
     * other way to see that. Thresholds: [PITCH_GOOD_DEG]/[PITCH_FAIR_DEG] (with DTED),
     * [PITCH_GOOD_DEG_NO_DTED]/[PITCH_FAIR_DEG_NO_DTED] (without) — steeper is required for the
     * same trust level without DTED, because the flat-ground assumption stacks an extra error
     * source on top of the same geometric term.
     *
     * Only the centre ring is tinted; the arms stay white so the reticle reads the same as a
     * sighting reference regardless of state.
     *
     * @param dtedAvailable whether DTED covers the aircraft's CURRENT position — i.e. whether a
     *   marker dropped right now would actually get [CameraSlantPoint]'s terrain-corrected
     *   solve, not just whether any DTED is loaded anywhere. Defaults false (the stricter
     *   thresholds) so an omitted argument fails toward more caution, not less.
     */
    fun setGimbalPitch(pitchDeg: Double?, dtedAvailable: Boolean = false) {
        val next = accuracyColorFor(context, pitchDeg, dtedAvailable)
        if (next == ringColor) return   // avoid invalidating on every HUD tick
        ringColor = next
        ring.color = next
        invalidate()
    }

    /**
     * Distance to the look-point, shown off the reticle's lower-right (otherwise the pilot
     * has no way to know how far away the thing under the reticle is). Null hides the text — the flight screen passes null when the camera is at or
     * above the horizon, where no ground distance exists.
     */
    fun setRangeText(text: String?) {
        if (text == rangeText) return   // avoid invalidating on every HUD tick
        rangeText = text
        invalidate()
    }

    private var rangeText: String? = null
    private val rangeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
    }
    /** Dark halo behind the range text — same job as the arms' outline: stay legible on
     *  bright ground. */
    private val rangeOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 160
        textSize = 14f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var ringColor = Color.WHITE
    /** Twice the arms' weight: the ring carries the accuracy state, so it should read at a
     *  glance without the pilot looking for it. Stroke widths come from dimens (crosshair_*_width)
     *  rather than hardcoded floats so the Smart Controller V3 can run ~25% heavier (the
     *  reticle read too fine on that screen) while every other screen
     *  keeps the exact original weight — see values/dimens.xml's comment for the reasoning. */
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = resources.getDimension(R.dimen.crosshair_ring_width)
        style = Paint.Style.STROKE
    }
    /** Matching heavier outline, so the thicker ring keeps its dark edge on bright ground. */
    private val ringOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 160
        strokeWidth = resources.getDimension(R.dimen.crosshair_ring_outline_width)
        style = Paint.Style.STROKE
    }

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 160
        strokeWidth = resources.getDimension(R.dimen.crosshair_arm_outline_width)
        style = Paint.Style.STROKE
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = resources.getDimension(R.dimen.crosshair_arm_line_width)
        style = Paint.Style.STROKE
    }
    // Drawn outline-then-white each frame. Held as a field so onDraw doesn't allocate the
    // array on every HUD tick.
    private val armPaints = arrayOf(outline, line)

    /** Tap inside the reticle — drop or re-aim the one quick marker at the look point. Set by the
     *  flight screen. */
    var onReticleTap: (() -> Unit)? = null

    /** Long-press inside the reticle — drop a NEW stationary Unknown marker at the look point.
     *  This is NOT the quick marker: see FlightActivity.onUnknownMarkerAction. Set by the flight
     *  screen. */
    var onReticleLongPress: (() -> Unit)? = null

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onReticleTap?.invoke()
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            // The action fires without any visible press state (there's no button here to
            // highlight), so the buzz is the only confirmation the long-press registered.
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onReticleLongPress?.invoke()
        }
    })

    /**
     * Touches are claimed ONLY within [HIT_RADIUS_DP] of the reticle centre.
     *
     * This view is `match_parent` and sits over the whole video, so consuming everything would
     * silently swallow every future touch on the FPV area. Rejecting at ACTION_DOWN — rather
     * than filtering later — means a touch that starts outside the reticle is never routed here
     * at all and the rest of its gesture goes wherever it would have gone before.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onReticleTap == null && onReticleLongPress == null) return false
        if (videoRect.isEmpty) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val r = HIT_RADIUS_DP * resources.displayMetrics.density
            val dist = hypot(event.x - videoRect.centerX(), event.y - videoRect.centerY())
            if (dist > r) return false
        }
        return gesture.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (videoRect.isEmpty) return
        val cx = videoRect.centerX()
        val cy = videoRect.centerY()
        val armLen = 22f * resources.displayMetrics.density
        val gap = 6f * resources.displayMetrics.density
        val ringR = 5f * resources.displayMetrics.density
        // Arms: dark outline then white, unchanged — the sighting reference stays constant.
        for (p in armPaints) {
            canvas.drawLine(cx - armLen, cy, cx - gap, cy, p)
            canvas.drawLine(cx + gap, cy, cx + armLen, cy, p)
            canvas.drawLine(cx, cy - armLen, cx, cy - gap, p)
            canvas.drawLine(cx, cy + gap, cx, cy + armLen, p)
        }
        // Centre ring carries the accuracy state; outlined first so it stays legible on a
        // bright background whatever colour it is.
        canvas.drawCircle(cx, cy, ringR, ringOutline)
        canvas.drawCircle(cx, cy, ringR, ring)
        // Look-point distance, off the lower-right arm — outside the arms so it never
        // covers what the pilot is sighting.
        rangeText?.let {
            val tx = cx + armLen * 0.75f
            val ty = cy + armLen * 0.75f + rangeFill.textSize * 0.5f
            canvas.drawText(it, tx, ty, rangeOutline)
            canvas.drawText(it, tx, ty, rangeFill)
        }
    }

    companion object {
        /**
         * Tap target around the reticle centre, in dp. Comfortably larger than the drawn reticle
         * (22dp arms) because the pilot is aiming at it with a thumb while flying, but well short
         * of covering the video — an over-wide target here would eat FPV touches for no visible
         * reason.
         */
        private const val HIT_RADIUS_DP = 34f

        /**
         * Steeper than this WITH DTED coverage, a marker drop is worth trusting. Set to -25
         * from field results: placement was acceptable at ~100 ft AGL and -20 deg, so -30 was
         * stricter than the hardware actually warrants and left the ring amber during
         * perfectly good drops.
         *
         * ⚠ NO GROUND-ERROR FIGURE BELONGS HERE. The thresholds came from field results with
         * another airframe at about 100 ft AGL; the ground error at a fixed pitch grows LINEARLY
         * with the height above the target (pitch term h/sin²·δθ, bearing term R·δψ), so the
         * "+/-10 ft" that was here is ~70 ft at 400 ft AGL. A colour from an error ESTIMATE (height,
         * range, lens, DTED) would be the better fix, but it is a policy change: it changes
         * when a drop is allowed.
         */
        const val PITCH_GOOD_DEG = -25.0

        /**
         * Between this and [PITCH_GOOD_DEG] WITH DTED: usable, but a degree of pointing error
         * is already tens of feet on the ground — more with height, see [PITCH_GOOD_DEG]. Both this and [PITCH_GOOD_DEG] assume good inputs: a weak GPS fix or a
         * magnetically noisy hover degrades them regardless of look angle.
         */
        const val PITCH_FAIR_DEG = -10.0

        /**
         * Steeper than this WITHOUT DTED coverage at the aircraft's current position, a marker
         * drop is worth trusting. Field-calibrated on another airframe (no DTED loaded),
         * at a low height — see [PITCH_GOOD_DEG] for why no ground figure is kept. Steeper is required without DTED because the
         * flat-ground assumption ([CameraSlantPoint.computeFlat]) stacks its own error on top
         * of the same 1/sin²(pitch) geometric term DTED coverage would otherwise correct for.
         */
        const val PITCH_GOOD_DEG_NO_DTED = -30.0

        /**
         * Between this and [PITCH_GOOD_DEG_NO_DTED] WITHOUT DTED: field-calibrated alongside
         * [PITCH_GOOD_DEG_NO_DTED].
         */
        const val PITCH_FAIR_DEG_NO_DTED = -15.0

        /** The POOR tint, exposed so callers can ASK "is the reticle red?" instead of
         *  re-deriving a pitch threshold that would drift from this one. Takes a Context
         *  because the value is a token now — see tp_hud_accuracy_poor. */
        fun accuracyPoorColor(context: Context): Int =
            ContextCompat.getColor(context, R.color.tp_hud_accuracy_poor)

        /**
         * Single source for the accuracy tint, shared with the HUD's gimbal readout so the
         * number and the reticle cannot disagree about what state the pilot is in.
         *
         * @param dtedAvailable selects which threshold pair applies — see the constants' docs.
         *   Defaults false (the stricter, no-DTED pair) so an omitted argument fails toward
         *   showing worse accuracy than is actually the case, never better.
         */
        fun accuracyColorFor(context: Context, pitchDeg: Double?, dtedAvailable: Boolean = false): Int {
            val good = if (dtedAvailable) PITCH_GOOD_DEG else PITCH_GOOD_DEG_NO_DTED
            val fair = if (dtedAvailable) PITCH_FAIR_DEG else PITCH_FAIR_DEG_NO_DTED
            val res = when {
                // Unknown is its own state, in the unknown token.
                pitchDeg == null -> R.color.tp_state_unknown
                pitchDeg <= good -> R.color.tp_hud_accuracy_good
                pitchDeg <= fair -> R.color.tp_hud_accuracy_fair
                else -> R.color.tp_hud_accuracy_poor
            }
            return ContextCompat.getColor(context, res)
        }
    }
}
