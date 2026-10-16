package com.autel.sdksample.tak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt
import androidx.core.content.ContextCompat
import com.autel.sdksample.R

/**
 * EV-compensation slider for the flight screen: a static full-width line (always visible from
 * end to end, unlike a stock SeekBar which only tints left of the thumb), three small unlabeled
 * tick marks crossing the line at the 1/4, 1/2, 3/4 points (i.e. -1, 0, +1 on a -2..+2 scale),
 * and a draggable thumb dot. Snaps to [steps] + 1 discrete positions.
 */
class EvSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Number of intervals; positions are 0..steps (default 12 → 13 stops for -2..+2 in 1/3s). */
    var steps: Int = 12

    var index: Int = steps / 2
        set(value) {
            val clamped = value.coerceIn(0, steps)
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    /** Fired on user drag/tap (fromUser = true); programmatic [index] sets don't call it. */
    var onIndexChanged: ((Int, Boolean) -> Unit)? = null

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

    /**
     * BLACK OUTLINE, the same treatment the readouts get.
     *
     * This control sits over live video with no panel behind it any more — see
     * [OutlinedTextView] for why the translucent backing went away. The track and the ticks are
     * `tp_accent` and the thumb is white; over a bright scene both vanish exactly as the text
     * did. Each shape is therefore drawn TWICE: a black pass slightly fatter, then the real
     * colour on top.
     *
     * The outline width is [R.dimen.hud_text_outline_width], the same per-device dimen the text
     * uses, so the whole HUD carries one weight of edge. It is added to BOTH sides of a stroke,
     * thus the black line is the coloured line plus twice the outline.
     */
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tp_hud_outline)
        strokeCap = Paint.Cap.ROUND
    }
    private val outlineWidth = resources.getDimension(R.dimen.hud_text_outline_width)

    /**
     * ⚠ THE INSET CARRIES THE OUTLINE TOO. The thumb sits at [trackInset] from each end at the
     * extremes of travel, and it is now drawn at [thumbRadius] PLUS the outline. Without the
     * extra room the outlined thumb is clipped flat against the view's edge at -2 and at +2 —
     * the same measurement fault the readouts had. It is invisible mid-track,
     * which is where a screenshot usually catches it.
     */
    private val trackInset = thumbRadius + 1f * density + outlineWidth

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val desiredH = (28 * density).toInt()
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            else -> desiredH
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        val left = trackInset
        val right = width - trackInset

        val fx = if (steps <= 0) 0.5f else index.toFloat() / steps
        val thumbX = left + (right - left) * fx

        // OUTLINE PASS FIRST, every shape, fattened by the outline width on each side. Drawing
        // it per-shape rather than per-layer would let the track's black edge cut across the
        // ticks and the thumb; one pass underneath keeps the edge outside the whole control.
        outlinePaint.style = Paint.Style.STROKE
        outlinePaint.strokeWidth = linePaint.strokeWidth + outlineWidth * 2f
        canvas.drawLine(left, cy, right, cy, outlinePaint)
        outlinePaint.strokeWidth = tickPaint.strokeWidth + outlineWidth * 2f
        for (f in floatArrayOf(0.25f, 0.5f, 0.75f)) {
            val x = left + (right - left) * f
            canvas.drawLine(x, cy - tickHalf, x, cy + tickHalf, outlinePaint)
        }
        outlinePaint.style = Paint.Style.FILL
        canvas.drawCircle(thumbX, cy, thumbRadius + outlineWidth, outlinePaint)

        // Static full-width line.
        canvas.drawLine(left, cy, right, cy, linePaint)

        // Three ticks crossing the line at 1/4, 1/2, 3/4.
        for (f in floatArrayOf(0.25f, 0.5f, 0.75f)) {
            val x = left + (right - left) * f
            canvas.drawLine(x, cy - tickHalf, x, cy + tickHalf, tickPaint)
        }

        // Thumb.
        canvas.drawCircle(thumbX, cy, thumbRadius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val left = trackInset
                val right = width - trackInset
                val f = ((event.x - left) / (right - left)).coerceIn(0f, 1f)
                val newIndex = (f * steps).roundToInt().coerceIn(0, steps)
                if (newIndex != index) {
                    index = newIndex
                    onIndexChanged?.invoke(newIndex, true)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
