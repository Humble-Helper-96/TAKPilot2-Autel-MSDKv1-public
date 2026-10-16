package com.autel.sdksample.tak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.autel.sdksample.R

/**
 * What the CAMERA is set to save, as a TWO-WAY TOGGLE: the movie camera on the left, the still
 * camera on the right, the lit half is the mode the camera reports.
 *
 * ## It is a control, and a readout still
 *
 * A tap on a half asks the camera for that mode WITHOUT taking a still or starting a
 * recording, and the shutter and REC keep changing the mode by themselves. The lit half always
 * shows what the CAMERA reports (the 2 Hz push), never what was tapped — a tap that the camera
 * ignores leaves the highlight where it was, which is the truth. Unknown is its own state: both
 * halves amber until the camera answers.
 *
 * Drawn as a SWITCH: a fully rounded track (the HUD pill stroke) with a white thumb on the
 * side the camera reports, the glyph under the thumb in `tp_state_go` and the other in white on
 * the track. A pill reads as "tap to capture"; a thumb on a track reads as "which of two". The glyphs are [CameraGlyphs], the same shapes the record pill
 * drew, so a pilot learns one symbol per mode; the one on the track carries the HUD's outline.
 */
class MediaModeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** What the camera says. Null is UNKNOWN and is its own state. */
    enum class Mode { PHOTO, VIDEO }

    /** Set by the flight screen: the pilot tapped a half and wants that mode. */
    var onModeRequested: ((Mode) -> Unit)? = null

    private var mode: Mode? = null
    private var otherLabel: String? = null

    private val outlineWidth = resources.getDimension(R.dimen.hud_text_outline_width)
    private val textSize = resources.getDimension(R.dimen.flight_readout_text_size)
    private val radius = resources.getDimension(R.dimen.hud_pill_radius)
    private val stroke = resources.getDimension(R.dimen.hud_pill_stroke)
    private val unknownColor = ContextCompat.getColor(context, R.color.tp_state_unknown)
    private val goColor = ContextCompat.getColor(context, R.color.tp_state_go)
    private val outlineColor = ContextCompat.getColor(context, R.color.tp_hud_outline)
    private val idleFill = ContextCompat.getColor(context, R.color.tp_pill_idle_fill)
    private val idleStroke = ContextCompat.getColor(context, R.color.tp_pill_idle_stroke)
    private val activeFill = ContextCompat.getColor(context, R.color.tp_pill_active_fill)
    private val unknownFill = ContextCompat.getColor(context, R.color.tp_pill_unknown_fill)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke
    }
    private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = outlineWidth / 2f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = outlineWidth * 2f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = outlineColor
    }

    private val glyph = Path()
    private val body = RectF()
    private val pill = RectF()
    private val half = RectF()

    private val glyphSize: Float get() = textSize * 1.15f
    /** The track is two thumbs wide plus a little travel; its height is a thumb. */
    private val cellH: Float get() = glyphSize * 1.7f
    private val cell: Float get() = cellH * 1.6f

    fun setMode(m: Mode?, unhandledName: String? = null) {
        if (m == mode && unhandledName == otherLabel) return
        mode = m
        otherLabel = unhandledName
        contentDescription = when {
            unhandledName != null -> "Camera mode $unhandledName"
            m == Mode.PHOTO -> "Camera is in photo mode; tap the movie camera for video"
            m == Mode.VIDEO -> "Camera is in video mode; tap the still camera for photo"
            else -> "Camera mode not known"
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val pad = stroke
        val w = cell * 2 + pad * 2
        val h = cellH + pad * 2
        setMeasuredDimension(
            resolveSize(Math.ceil(w.toDouble()).toInt(), widthMeasureSpec),
            resolveSize(Math.ceil(h.toDouble()).toInt(), heightMeasureSpec),
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action != MotionEvent.ACTION_UP) return false
        val wanted = if (event.x < width / 2f) Mode.VIDEO else Mode.PHOTO
        performClick()
        onModeRequested?.invoke(wanted)
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // A SWITCH, not a pill (design decision): a fully rounded track with a THUMB that
        // sits on the mode the camera reports. A pill reads as "tap to capture", which this is
        // not; a thumb on a track reads as "which of two", which it is. The track keeps the
        // HUD pill stroke; the radius is the track's half height because it IS a switch — this
        // is the one control on the screen where the knob is the right affordance.
        val pad = stroke / 2f
        pill.set(pad, pad, width - pad, height - pad)
        val r = pill.height() / 2f
        val unknown = mode == null || otherLabel != null

        fillPaint.color = if (unknown) unknownFill else idleFill
        canvas.drawRoundRect(pill, r, r, fillPaint)
        strokePaint.color = if (unknown) unknownColor else idleStroke
        canvas.drawRoundRect(pill, r, r, strokePaint)

        // The thumb: a disc the height of the track, on the left for video, the right for
        // photo, centred and amber when the camera has not answered.
        val thumbR = r - stroke
        val thumbCx = when {
            unknown -> width / 2f
            mode == Mode.VIDEO -> pill.left + r
            else -> pill.right - r
        }
        fillPaint.color = if (unknown) unknownColor else Color.WHITE
        canvas.drawCircle(thumbCx, height / 2f, thumbR, fillPaint)

        // Glyphs at the two ends: the one under the thumb is drawn dark on the white disc, the
        // other white on the track. Movie left, still right.
        val cy = height / 2f
        val leftCx = pill.left + r
        val rightCx = pill.right - r
        drawGlyph(canvas, leftCx, cy, movie = true,
            colour = if (unknown) Color.WHITE else if (mode == Mode.VIDEO) goColor else Color.WHITE,
            onThumb = !unknown && mode == Mode.VIDEO)
        drawGlyph(canvas, rightCx, cy, movie = false,
            colour = if (unknown) Color.WHITE else if (mode == Mode.PHOTO) goColor else Color.WHITE,
            onThumb = !unknown && mode == Mode.PHOTO)
    }

    private fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, movie: Boolean, colour: Int, onThumb: Boolean) {
        val s = glyphSize * 0.85f
        if (movie) CameraGlyphs.movie(glyph, body, cx - s / 2f, cy - s / 2f, s)
        else CameraGlyphs.still(glyph, body, cx - s / 2f, cy - s / 2f, s)
        // On the white thumb the black outline pass would just be a black glyph; the go colour
        // alone reads there. On the track the HUD outline stays.
        if (!onThumb) canvas.drawPath(glyph, glyphStroke)
        glyphFill.color = colour
        canvas.drawPath(glyph, glyphFill)
    }
}
