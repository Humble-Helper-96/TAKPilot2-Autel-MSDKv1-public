package com.autel.sdksample.tak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.autel.sdksample.R

/**
 * Small circular battery gauge for the flight-screen toolbar — a colored ring sweeps out the
 * charge percentage with the number centered inside, ATAK-UAS-Tool style, in place of a plain
 * icon + text pair.
 */
class BatteryGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // Flight-tuned HUD colours, resolved once per view — see takpilot_colors.xml.
    private val COLOR_CRITICAL = ContextCompat.getColor(context, R.color.tp_hud_battery_critical)
    private val COLOR_WARNING = ContextCompat.getColor(context, R.color.tp_hud_battery_warn)
    private val COLOR_GOOD = ContextCompat.getColor(context, R.color.tp_hud_battery_good)

    private var percent: Int? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(60, 255, 255, 255)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val arcRect = RectF()

    fun setPercent(pct: Int?) {
        percent = pct
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val strokeWidth = w * STROKE_FRACTION
        trackPaint.strokeWidth = strokeWidth
        arcPaint.strokeWidth = strokeWidth
        textPaint.textSize = w * TEXT_FRACTION
        val inset = strokeWidth / 2f
        arcRect.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)

        val pct = percent
        if (pct != null) {
            // Banded gauge, not a single color for the whole filled arc: the Critical,
            // Warning and Good zones each keep their own color as the ring
            // fills, like a fuel gauge's redline band — so at e.g. 80% you see a thin red
            // wedge, a thin amber wedge, then a large green wedge, not one solid green ring.
            val sweepTotal = 360f * (pct.coerceIn(0, 100) / 100f)
            val criticalEnd = 360f * (criticalPct / 100f)
            val warningEnd = 360f * (warningPct / 100f)

            val critSweep = sweepTotal.coerceAtMost(criticalEnd)
            if (critSweep > 0f) {
                arcPaint.color = COLOR_CRITICAL
                canvas.drawArc(arcRect, -90f, critSweep, false, arcPaint)
            }
            if (sweepTotal > criticalEnd) {
                val warnSweep = sweepTotal.coerceAtMost(warningEnd) - criticalEnd
                arcPaint.color = COLOR_WARNING
                canvas.drawArc(arcRect, -90f + criticalEnd, warnSweep, false, arcPaint)
            }
            if (sweepTotal > warningEnd) {
                val goodSweep = sweepTotal - warningEnd
                arcPaint.color = COLOR_GOOD
                canvas.drawArc(arcRect, -90f + warningEnd, goodSweep, false, arcPaint)
            }
        }

        val label = pct?.toString() ?: "—"
        val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, width / 2f, textY, textPaint)
    }

    /** Red band edge — the aircraft's Battery Critical setting. */
    private var criticalPct = DEFAULT_CRITICAL_PCT
    /** Amber band edge — the aircraft's Battery Warning setting. */
    private var warningPct = DEFAULT_WARNING_PCT

    /**
     * Points the gauge at the two levels the aircraft is actually configured with, so the
     * colours mean the same thing as the Pre-Flight fields that set them: **amber from Battery
     * Warning, red from Battery Critical** (design decision).
     *
     * This used to put RED at the Warning level and invent amber ten points above it, on the
     * reasoning that red should start where the pilot still has a decision. That made the gauge
     * tell a different story from the screen that configures it — and it was built on a belief
     * that Warning is a hard turn-around, which it is not: the return can be deferred with the
     * controller's RTH button. Two levels are set, so two levels are shown.
     */
    fun setBands(criticalPct: Float, warningPct: Float) {
        val c = criticalPct.coerceIn(1f, 99f)
        val w = warningPct.coerceIn(c + 1f, 100f)
        // No-op when nothing moved. The caller polls this from the HUD loop so the bands pick up
        // the aircraft's read-back as soon as it lands; without this guard that would be an
        // invalidate() twice a second, forever, for a value that changes once per connect.
        if (c == this.criticalPct && w == this.warningPct) return
        this.criticalPct = c
        this.warningPct = w
        invalidate()
    }

    companion object {
        private const val STROKE_FRACTION = 0.12f
        private const val TEXT_FRACTION = 0.34f
        /**
         * Band edges, in percent. These are DEFAULTS ONLY — [setBands] overrides them with the
         * thresholds the aircraft is actually configured with, so the gauge cannot say "you are
         * fine" at a charge where the aircraft is about to fly itself home.
         *
         * They were 15/30 and fixed, chosen before anyone knew what the aircraft did, so a gauge
         * with its own unrelated numbers was showing amber while the aircraft was seconds from
         * acting. These now match FlightLimitsController's own defaults for the same two
         * settings — keep them in step if those change.
         */
        private const val DEFAULT_CRITICAL_PCT = 10f
        private const val DEFAULT_WARNING_PCT = 15f
    }
}
