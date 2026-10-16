package com.autel.sdksample.tak

import android.graphics.Path
import android.graphics.RectF

/**
 * The still-camera and movie-camera symbols, in ONE place.
 *
 * ⚠ **TWO VIEWS DRAW THESE AND THEY MUST DRAW THE SAME SHAPE.** The HUD's media-mode readout
 * says which mode the camera is in; the toolbar's record pill becomes a shutter in that mode.
 * A pilot learns one symbol for "stills" and one for "video", and the two would drift apart
 * the first time either file was touched alone.
 *
 * Drawn as paths rather than shipped as vector drawables because both callers STROKE them twice
 * — a black or coloured outline pass and then the fill — so the glyph carries the same edge as
 * the text beside it and stays legible over bright ground. An ImageView cannot take that edge.
 *
 * Every dimension is a fraction of [size], so one symbol serves an 18sp readout and a 34dp pill.
 */
internal object CameraGlyphs {

    /** Still camera: body, the viewfinder hump, and the lens. */
    fun still(path: Path, box: RectF, left: Float, top: Float, size: Float) {
        path.reset()
        val bodyTop = top + size * 0.28f
        box.set(left + size * 0.06f, bodyTop, left + size * 0.94f, top + size * 0.86f)
        path.addRoundRect(box, size * 0.10f, size * 0.10f, Path.Direction.CW)
        path.moveTo(left + size * 0.28f, bodyTop)
        path.lineTo(left + size * 0.36f, top + size * 0.16f)
        path.lineTo(left + size * 0.60f, top + size * 0.16f)
        path.lineTo(left + size * 0.68f, bodyTop)
        path.addCircle(left + size * 0.50f, top + size * 0.57f, size * 0.17f, Path.Direction.CW)
    }

    /** Movie camera: body and the lens barrel pointing right. */
    fun movie(path: Path, box: RectF, left: Float, top: Float, size: Float) {
        path.reset()
        box.set(left + size * 0.06f, top + size * 0.30f, left + size * 0.66f, top + size * 0.82f)
        path.addRoundRect(box, size * 0.08f, size * 0.08f, Path.Direction.CW)
        path.moveTo(left + size * 0.70f, top + size * 0.46f)
        path.lineTo(left + size * 0.94f, top + size * 0.32f)
        path.lineTo(left + size * 0.94f, top + size * 0.80f)
        path.lineTo(left + size * 0.70f, top + size * 0.66f)
        path.close()
    }

    /**
     * Question mark, for a mode the camera has not reported. Drawn as an arc and a dot so it
     * carries the outline like the other two rather than being text pretending to be a glyph.
     */
    fun unknown(path: Path, box: RectF, left: Float, top: Float, size: Float) {
        path.reset()
        val r = size * 0.22f
        val cx = left + size / 2f
        box.set(cx - r, top + size * 0.16f, cx + r, top + size * 0.16f + 2 * r)
        path.addArc(box, 160f, 240f)
        path.lineTo(cx, top + size * 0.62f)
        path.moveTo(cx, top + size * 0.80f)
        path.lineTo(cx, top + size * 0.84f)
    }
}
