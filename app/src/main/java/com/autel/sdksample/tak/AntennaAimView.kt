package com.autel.sdksample.tak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import androidx.core.content.ContextCompat
import com.autel.sdksample.R

/**
 * BVLOS antenna-aim indicator: a semicircular arc at the bottom
 * centre of the FPV. The dot at the arc's centre is the PILOT; the aircraft marker rides
 * the arc at the aircraft's bearing RELATIVE to the way the controller faces. The pilot
 * turns until the marker sits at the top of the arc — then the directional antennas face
 * the aircraft and the marker reads green.
 *
 * The arc spans -90°..+90° of relative bearing. An aircraft BEHIND the pilot pins the
 * marker at the near end of the arc and hollows it, which still tells the pilot which
 * shoulder to turn over — the marker walks the arc as they come around.
 *
 * Fed from the flight screen's HUD tick ([setRelativeBearing]); this view owns no sensor
 * and no listener. Colours follow the flight-HUD custom-view pattern (inline literals,
 * white with dark outline for legibility on video).
 */
class AntennaAimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // Flight-tuned HUD colours, resolved once per view — see takpilot_colors.xml.
    private val ALIGNED_GREEN = ContextCompat.getColor(context, R.color.tp_state_go)

    /**
     * Aircraft bearing relative to the controller's facing, -180..+180 (0 = dead ahead).
     * Null hides the whole indicator (no compass, no fix, or no aircraft).
     */
    fun setRelativeBearing(relDeg: Double?) {
        if (relDeg == null && shownRelDeg == null) return
        // Sub-degree jitter from the rotation vector is invisible on a ~40dp arc; skip
        // the redraw unless the marker would actually move.
        if (relDeg != null && shownRelDeg != null && abs(relDeg - shownRelDeg!!) < 0.5) return
        shownRelDeg = relDeg
        visibility = if (relDeg == null) GONE else VISIBLE
        invalidate()
    }

    private var shownRelDeg: Double? = null

    private val dp = resources.displayMetrics.density
    private val arcRadius = 40f * dp
    private val markerR = 5f * dp

    private val arcOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; alpha = 160
        style = Paint.Style.STROKE; strokeWidth = 4f * dp
    }
    private val arcLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE; strokeWidth = 2f * dp
    }
    private val dotOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; alpha = 160
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val markerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE; strokeWidth = 2f * dp
    }
    private val markerHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; alpha = 160
        style = Paint.Style.STROKE; strokeWidth = 4.5f * dp
    }
    private val markerPath = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Wide enough for the arc plus the marker overhanging both ends; tall enough for
        // the top of the arc plus the marker.
        val w = ((arcRadius + markerR * 3) * 2).toInt()
        val h = (arcRadius + markerR * 4).toInt()
        setMeasuredDimension(
            resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rel = shownRelDeg ?: return
        val cx = width / 2f
        val cy = height - markerR * 1.5f   // pilot dot sits at the bottom middle
        // The semicircle, ends at the pilot's 9 and 3 o'clock. Sweep is screen-angle:
        // 180°..360° covers left-horizon over the top to right-horizon.
        val r = arcRadius
        for (p in arrayOf(arcOutline, arcLine)) {
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 180f, 180f, false, p)
        }
        // Pilot dot.
        canvas.drawCircle(cx, cy, 3.5f * dp, dotOutline)
        canvas.drawCircle(cx, cy, 2.5f * dp, dotFill)

        // Aircraft marker. On-arc position clamps to the arc span; behind the pilot the
        // marker pins at the end and draws hollow (stroke only).
        val behind = abs(rel) > 90.0
        val clamped = rel.coerceIn(-90.0, 90.0)
        val a = Math.toRadians(clamped)
        val mx = cx + (r * sin(a)).toFloat()
        val my = cy - (r * cos(a)).toFloat()
        val aligned = abs(rel) <= FlightActivity.ANTENNA_ALIGNED_DEG
        val tint = if (aligned) ALIGNED_GREEN else Color.WHITE
        markerFill.color = tint
        markerStroke.color = tint
        // Triangle nose points outward along the pilot→aircraft direction, so the marker
        // itself says "that way".
        markerPath.reset()
        val nose = markerR * 1.6f
        markerPath.moveTo(mx + (nose * sin(a)).toFloat(), my - (nose * cos(a)).toFloat())
        markerPath.lineTo(mx + (markerR * sin(a + 2.4)).toFloat(), my - (markerR * cos(a + 2.4)).toFloat())
        markerPath.lineTo(mx + (markerR * sin(a - 2.4)).toFloat(), my - (markerR * cos(a - 2.4)).toFloat())
        markerPath.close()
        canvas.drawPath(markerPath, markerHalo)
        canvas.drawPath(markerPath, if (behind) markerStroke else markerFill)
    }

    private companion object {
    }
}
