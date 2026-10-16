package com.autel.sdksample.tak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.autel.sdksample.R

/**
 * Record-to-SD badge — a dot and the word "REC", drawn as a toggle pill to the same
 * specification as the AR, IR and exterior-lights pills beside it.
 *
 * ## What it was, and why that was wrong
 *
 * It was a SWITCH: a fully-round capsule on a flat grey fill with a large white knob circle at
 * the left end. Squaring the corner to `hud_pill_radius` was tried first and made it worse — a
 * switch with a border. **The knob was the problem.** A big filled circle at one end of a
 * rounded track is the universal affordance for a slider, so the control read as something the
 * pilot drags rather than something they tap, whatever the corner did.
 *
 * ## What it is now
 *
 * The same treatment as [R.drawable.bg_pill_active], hue changed: a 30 % wash of the state
 * colour, a full-strength stroke of it, and the CONTENT — the dot and the label — in that same
 * colour. Idle takes the neutral fill and stroke with white content. Nothing is white-on-solid
 * any more, and the six pills in the capsule finally read as one family.
 *
 * ⚠ **RED IS NOT GREEN, AND THAT IS THE POINT**. Green on the pills
 * beside this one means "this feature is on". Red here means "the camera is writing to the
 * card" — a different question, and the one a pilot must never misread. Only the hue departs
 * from the neighbours; the treatment does not.
 */
class RecordToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // Flight-tuned HUD colours, resolved once per view — see takpilot_colors.xml.
    private val COLOR_RECORDING = ContextCompat.getColor(context, R.color.tp_hud_toggle_active)
    private val COLOR_IDLE_CONTENT = ContextCompat.getColor(context, R.color.tp_text_primary)

    /** The pill's shape and idle look, shared with the drawable-backed pills beside it. */
    private val cornerRadius = resources.getDimension(R.dimen.hud_pill_radius)
    private val pillStroke = resources.getDimension(R.dimen.hud_pill_stroke)
    private val idleFill = ContextCompat.getColor(context, R.color.tp_pill_idle_fill)
    private val idleStroke = ContextCompat.getColor(context, R.color.tp_pill_idle_stroke)
    private val liveFill = ContextCompat.getColor(context, R.color.tp_pill_live_fill)

    private var isRecording: Boolean = false

    /**
     * True when the CAMERA is in stills mode, so this pill is a shutter rather than a record
     * control.
     *
     * ⚠ **THE PILL FOLLOWS THE CAMERA; IT DOES NOT SET IT.** The mode is read from the camera's
     * own push — see [AutelProductHolder.mediaMode] — and the hardware shutter can move it
     * without this application being asked. The pill changing shape IS the second cue that the
     * camera is in stills: the HUD readout says it in words, this says it where the pilot's
     * thumb already is.
     */
    private var photoMode: Boolean = false

    fun setPhotoMode(photo: Boolean) {
        if (photoMode == photo) return
        photoMode = photo
        // Owned here rather than by the caller: the description and the drawing are the same
        // fact, and setting it from the HUD tick would rewrite it twice a second for nothing.
        contentDescription = if (photo) "Take a photo. The camera is in photo mode."
        else "Start or stop recording to the aircraft SD card"
        invalidate()
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = pillStroke
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** The still-camera symbol for stills mode. Stroked, like the HUD readout's copy of it. */
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = pillStroke
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphPath = android.graphics.Path()
    private val glyphBox = RectF()

    /** The shutter symbol's size as a fraction of the pill's height. Sized to sit in the pill
     *  the way LIVE's content does — see the note in onDraw about what it replaced. */
    private val GLYPH_FRACTION = 0.62f
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }

    private val trackRect = RectF()

    fun setRecording(recording: Boolean) {
        isRecording = recording
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // ⚠ INSET BY HALF THE STROKE, OR THE BORDER IS CLIPPED FLAT on all four sides. A stroke
        // is CENTRED on the path, thus its outer half falls outside the view, and the canvas
        // handed to onDraw is clipped to the view's bounds. A shape drawable does this for you;
        // a canvas does not. Same fault and same fix as OutlinedTextView.
        val half = pillStroke / 2f
        trackRect.set(half, half, w - half, h - half)
        textPaint.textSize = h * 0.4f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val w = width.toFloat()

        // ⚠ STILLS MODE TAKES THE IDLE TREATMENT AND NO COLOUR OF ITS OWN. A shutter is a
        // momentary ACTION, not a state that is on — and red already means "the camera is
        // writing to the card", which is the one meaning on this screen that must never be
        // diluted. The GLYPH and the WORD carry the mode; the colour stays out of it.
        val content = if (isRecording) COLOR_RECORDING else COLOR_IDLE_CONTENT
        fillPaint.color = if (isRecording) liveFill else idleFill
        strokePaint.color = if (isRecording) COLOR_RECORDING else idleStroke
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, fillPaint)
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, strokePaint)

        val cy = h / 2f
        if (photoMode) {
            // ⚠ **THE SYMBOL ALONE, AND NO WORD** — the word made the pill crowded.
            // Glyph plus "PHOTO" filled the pill edge to edge where LIVE and REC have margins,
            // and this pill cannot widen — the layout fixes six pills at two widths.
            //
            // The word is what goes, not the symbol, for three reasons. A shutter is a glyph in
            // every camera a pilot has ever used. The HUD readout a few lines away already says
            // PHOTO in words, so the pill was saying it twice. And a lone symbol is a stronger
            // SILHOUETTE against REC's dot-and-word than a second word would ever be, which is
            // the mode cue this pill was changed to carry.
            val glyphSize = h * GLYPH_FRACTION
            CameraGlyphs.still(glyphPath, glyphBox, (w - glyphSize) / 2f,
                cy - glyphSize / 2f, glyphSize)
            glyphPaint.color = content
            canvas.drawPath(glyphPath, glyphPaint)
            return
        }

        // The dot and the label are CENTRED AS ONE GROUP, not pinned to the ends. The knob used
        // to hold the left end and the text was centred in what was left, so the two moved
        // independently when the view's width changed. Measuring the pair keeps the pill
        // readable at any width the layout gives it.
        val dotRadius = h * 0.10f
        val gap = h * 0.16f
        val label = "REC"
        val textWidth = textPaint.measureText(label)
        val groupWidth = dotRadius * 2f + gap + textWidth
        val startX = (w - groupWidth) / 2f

        dotPaint.color = content
        canvas.drawCircle(startX + dotRadius, cy, dotRadius, dotPaint)

        textPaint.color = content
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, startX + dotRadius * 2f + gap, textY, textPaint)
    }
}
