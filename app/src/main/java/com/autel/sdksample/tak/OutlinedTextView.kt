package com.autel.sdksample.tak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.autel.sdksample.R

/**
 * A TextView with a BLACK OUTLINE around every glyph, for HUD readouts over live video.
 *
 * ## Why this exists
 *
 * The readouts sit over a picture whose brightness the pilot does not control. White text over
 * snow, wet asphalt or a white roof washes out. Two other answers fall short:
 *
 *  1. A DROP SHADOW (`hudText`). It is a soft halo below and to one side, not a border, and
 *     is not enough by itself.
 *  2. A TRANSLUCENT PANEL behind each block (`bg_hud_panel`). It works, but it covers live
 *     video that the pilot is flying from. Lowering its opacity — 55 % to 40 % to 20 % —
 *     trades legibility for picture at each step, because the two fight.
 *
 * An outline ends that fight. A black border around each glyph is legible on ANY background,
 * light or dark, and it covers only the few pixels around the letters instead of a rectangle.
 * The panels go away and the pilot gets the video back.
 *
 * ## How it draws
 *
 * The text layout is drawn TWICE: first with a black stroked paint, then with the normal fill
 * on top. The [android.text.Layout] is drawn directly and NOT through `super.onDraw`, and the
 * paint's colour and style are set on the paint itself.
 *
 * ⚠ **NEVER USE `setTextColor` OR `setShadowLayer` INSIDE `onDraw` TO DO THIS.** Both call
 * `invalidate()`. On a view that is already drawing, that schedules another draw, which draws,
 * which schedules another — a permanent full-rate repaint of a view that sits over live video.
 * The obvious implementation of a two-pass outline has exactly that bug.
 *
 * Spans survive both passes, thus a [android.text.style.RelativeSizeSpan] on part of the text
 * is outlined at its own size — which is what the AGL readout needs.
 *
 * ## The width is PER-DEVICE, and it is SMALL
 *
 * `hud_text_outline_width` is a dimen with a `values-w820dp` override, for the same reason
 * `flight_readout_text_size` is: this controller draws the readouts at 18sp where a phone
 * draws them at 12sp.
 *
 * ⚠ **THE DIMEN IS THE STROKE WIDTH, AND IT IS NOT DOUBLED HERE.** The stroke is CENTRED on
 * the glyph edge, thus half of it falls INSIDE the letter and is covered by the fill pass. A
 * value that sounds modest eats the letter from within: at 18sp the text is 36px with stems
 * about 5px wide, so a 7px stroke leaves barely 1px of white and the readout turns to black
 * blobs, unreadable on the controller.
 *
 * Keep the stroke near **1/8th of the text size**: about 4px against 36px text here, 3px
 * against the 24px of the base bucket. Look at it on a real screen after any change — this is
 * one of those values that reads as fine in a mockup and fails on the device.
 */
class OutlinedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    /** The stroke width itself — see the note above on why this is NOT doubled. */
    private val strokeWidthPx: Float =
        resources.getDimension(R.dimen.hud_text_outline_width)

    /** One token for every HUD edge — the readouts, the EV slider and the mini-map frame.
     *  Colours come from takpilot_colors.xml, never from a literal here. */
    private val outlineColor: Int =
        androidx.core.content.ContextCompat.getColor(context, R.color.tp_hud_outline)

    init {
        // ⚠ ROOM FOR THE OUTLINE, OR IT IS CLIPPED FLAT.
        //
        // A TextView is measured for the glyphs' ADVANCE WIDTH, which is the fill. The stroke's
        // outer half falls OUTSIDE that, and the canvas handed to onDraw is clipped to the
        // view's bounds — so without padding the last letter of every line loses its right-hand
        // outline and ends in a flat vertical cut. It looks like a rendering artefact; it is a
        // measurement one.
        //
        // Half the stroke on each side is exactly what escapes. Padding is used rather than a
        // wider onMeasure so the text keeps its own position inside the view and the column's
        // right edge stays where the layout puts it.
        val pad = kotlin.math.ceil(strokeWidthPx / 2f).toInt()
        setPadding(paddingLeft + pad, paddingTop + pad, paddingRight + pad, paddingBottom + pad)
    }

    override fun onDraw(canvas: Canvas) {
        val textLayout = layout
        if (strokeWidthPx <= 0f || textLayout == null) {
            super.onDraw(canvas)
            return
        }
        val p = paint
        val fillStroke = p.strokeWidth
        val fillJoin = p.strokeJoin

        canvas.save()
        canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())

        // Pass 1 — the outline. ROUND joins keep the corners of a thin glyph from growing
        // spikes, which MITER does at this stroke width relative to the stroke thickness.
        p.style = Paint.Style.STROKE
        p.strokeWidth = strokeWidthPx
        p.strokeJoin = Paint.Join.ROUND
        p.color = outlineColor
        textLayout.draw(canvas)

        // Pass 2 — the text itself, over the outline. The fill covers the INNER half of the
        // stroke, so the glyph keeps its full weight and only the outer half shows as a border.
        //
        // ⚠ THE COLOUR COMES FROM [currentTextColor], NOT FROM A VALUE SAVED ABOVE. TextView
        // sets the paint's colour from its resolved text colour inside `super.onDraw`, which
        // this method never calls — so the paint's colour is not the text colour, and saving
        // and restoring it just carries the wrong value forward. Reading it here is what makes
        // the text white. Getting this wrong renders the whole readout in near-black and looks
        // like a stroke-width problem.
        p.style = Paint.Style.FILL
        p.strokeWidth = fillStroke
        p.strokeJoin = fillJoin
        p.color = currentTextColor
        textLayout.draw(canvas)

        canvas.restore()
    }
}
