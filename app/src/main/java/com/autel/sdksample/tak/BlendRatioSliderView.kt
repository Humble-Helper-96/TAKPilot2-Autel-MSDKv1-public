package com.autel.sdksample.tak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.autel.sdksample.R
import kotlin.math.roundToInt

/**
 * How much THERMAL shows in the PIP window: a vertical slider beside the actions column.
 *
 * ⚠ **THE SAME CONTROL AS [EvSliderView], TURNED ON ITS SIDE, AND THAT IS DELIBERATE.** Track,
 * ticks, thumb, and the black double-pass outline are identical — one weight of edge across the
 * whole HUD. A second slider drawn to its own taste would read as a
 * different class of control.
 *
 * UP IS MORE THERMAL. The camera's pair is IR against Visible summing to 65535, and Explorer
 * shows it as a percentage; up the screen is up the percentage, which is the only mapping a
 * pilot who has used Explorer will predict.
 *
 * ⚠ **IT IS SHOWN ONLY IN PIP.** In VISIBLE there is no thermal layer and in full thermal there
 * is nothing to mix it with, so the control would move and change nothing — see
 * FlightActivity.renderBlendSlider.
 */
class BlendRatioSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Number of intervals. 20 gives 5 % steps, which is finer than a pilot can judge by eye
     *  and coarse enough that a drag does not ask the camera for a new value every pixel. */
    var steps: Int = 20

    /** 0..[steps]; [percent] is the same value in the units the camera and Explorer use. */
    var index: Int = steps
        set(value) {
            val clamped = value.coerceIn(0, steps)
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    /** The thermal share, 0..100, rounded to the step. */
    var percent: Int
        get() = if (steps <= 0) 0 else (index * 100f / steps).roundToInt()
        set(value) { index = ((value.coerceIn(0, 100) / 100f) * steps).roundToInt() }

    /**
     * Fired while the pilot drags ([settled] = false) and once when they lift ([settled] =
     * true). A programmatic [index] or [percent] set fires nothing.
     *
     * ⚠ **THE TWO ARE NOT THE SAME EVENT AND THE CALLER MUST NOT TREAT THEM ALIKE.** The drag
     * stream is for a preview the pilot can see; the settled one is the pilot's decision, and
     * it is the only one worth storing. See FlightActivity for the write policy.
     */
    var onPercentChanged: ((percent: Int, settled: Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val thumbRadius = 7f * density
    private val tickHalf = 4f * density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tp_accent)
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tp_accent)
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /** See [EvSliderView]'s outline note — same reason, same dimen, same double pass. */
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tp_hud_outline)
        strokeCap = Paint.Cap.ROUND
    }
    private val outlineWidth = resources.getDimension(R.dimen.hud_text_outline_width)

    /** ⚠ The thumb is drawn at [thumbRadius] PLUS the outline, thus the track stops this far
     *  from each END of travel or the outlined thumb is clipped flat at 0 % and at 100 %. The
     *  EV slider carries the same inset for the same measured reason. */
    private val trackInset = thumbRadius + 1f * density + outlineWidth

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Width is the control's own business — enough for the outlined thumb and no more, so
        // it costs the picture one thumb of width. The HEIGHT is given by the caller, which
        // matches it to the actions column.
        val desiredW = ((thumbRadius + outlineWidth) * 2f + 2f * density).toInt()
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            else -> desiredW
        }
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val top = trackInset
        val bottom = height - trackInset
        if (bottom <= top) return

        // UP IS MORE: index 0 sits at the BOTTOM.
        val f = if (steps <= 0) 0f else index.toFloat() / steps
        val thumbY = bottom - (bottom - top) * f

        outlinePaint.style = Paint.Style.STROKE
        outlinePaint.strokeWidth = linePaint.strokeWidth + outlineWidth * 2f
        canvas.drawLine(cx, top, cx, bottom, outlinePaint)
        outlinePaint.strokeWidth = tickPaint.strokeWidth + outlineWidth * 2f
        for (t in floatArrayOf(0.25f, 0.5f, 0.75f)) {
            val y = bottom - (bottom - top) * t
            canvas.drawLine(cx - tickHalf, y, cx + tickHalf, y, outlinePaint)
        }
        outlinePaint.style = Paint.Style.FILL
        canvas.drawCircle(cx, thumbY, thumbRadius + outlineWidth, outlinePaint)

        canvas.drawLine(cx, top, cx, bottom, linePaint)
        for (t in floatArrayOf(0.25f, 0.5f, 0.75f)) {
            val y = bottom - (bottom - top) * t
            canvas.drawLine(cx - tickHalf, y, cx + tickHalf, y, tickPaint)
        }
        canvas.drawCircle(cx, thumbY, thumbRadius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // ⚠ The flight screen's root takes touches for the marker drop; without this a
                // drag down the slider is read as a scroll/tap by an ancestor and the thumb
                // stops following the thumb that is dragging it.
                parent?.requestDisallowInterceptTouchEvent(true)
                val top = trackInset
                val bottom = height - trackInset
                val f = ((bottom - event.y) / (bottom - top)).coerceIn(0f, 1f)
                val newIndex = (f * steps).roundToInt().coerceIn(0, steps)
                if (newIndex != index) {
                    index = newIndex
                    onPercentChanged?.invoke(percent, false)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                // ⚠ ALWAYS fired, even when the index did not move on this event: lifting is
                // the pilot's decision and it is what commits the value to the camera and to
                // the preference. A lift that changed nothing writes the same value again,
                // which is harmless and far cheaper than a lost setting.
                onPercentChanged?.invoke(percent, true)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
