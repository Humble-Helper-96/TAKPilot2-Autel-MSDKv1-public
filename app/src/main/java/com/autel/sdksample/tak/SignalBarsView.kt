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
 * Wifi-style RC signal-strength indicator: a filled dot at the bottom plus up to three arcs
 * fanning out above it. Color + active-bar count encode the uplink (controller→aircraft) link
 * quality:
 *   0-25%   -> red dot,    0 bars
 *   25-50%  -> yellow dot, 1 bar
 *   50-75%  -> green dot,  2 bars
 *   75-100% -> green dot,  3 bars
 * Inactive arcs are drawn faint so the wifi shape stays recognizable; null (no data) dims
 * everything.
 */
class SignalBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // Flight-tuned HUD colours, resolved once per view — see takpilot_colors.xml.
    private val COLOR_RED = ContextCompat.getColor(context, R.color.tp_hud_signal_poor)
    private val COLOR_YELLOW = ContextCompat.getColor(context, R.color.tp_hud_signal_fair)
    private val COLOR_GREEN = ContextCompat.getColor(context, R.color.tp_hud_signal_good)

    private var percent: Int? = null

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val oval = RectF()

    fun setPercent(pct: Int?) {
        percent = pct
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val dotY = h * 0.82f
        val dotR = w * 0.10f
        arcPaint.strokeWidth = w * 0.085f

        val pct = percent
        val (color, activeBars) = when {
            pct == null -> INACTIVE to 0
            pct < 25 -> COLOR_RED to 0
            pct < 50 -> COLOR_YELLOW to 1
            pct < 75 -> COLOR_GREEN to 2
            else -> COLOR_GREEN to 3
        }

        // Three arcs (portions of circles centered on the dot), opening downward like wifi.
        // Dot-to-first-bar gap kept small (0.24); wider gaps between bars so the strokes don't
        // look cramped ("swished") — the arc strokes are thicker than the dot edge, so equal
        // radial spacing reads as tighter between bars than dot-to-bar.
        val radii = floatArrayOf(w * 0.24f, w * 0.43f, w * 0.62f)
        for (i in 0 until 3) {
            val r = radii[i]
            oval.set(cx - r, dotY - r, cx + r, dotY + r)
            arcPaint.color = if (i < activeBars) color else INACTIVE
            canvas.drawArc(oval, 225f, 90f, false, arcPaint)
        }

        dotPaint.color = if (pct == null) INACTIVE else color
        canvas.drawCircle(cx, dotY, dotR, dotPaint)
    }

    companion object {
        private val INACTIVE = Color.argb(70, 255, 255, 255)
    }
}
