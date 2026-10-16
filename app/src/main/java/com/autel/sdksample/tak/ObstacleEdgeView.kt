package com.autel.sdksample.tak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.autel.sdksample.R

/**
 * Obstacle proximity drawn as a translucent gradient bleeding in from the edge nearest the
 * hazard, one per aircraft face.
 *
 * ⚠ **IT USED TO BE AN ARC** — a stroked bow springing in from the edge with the
 * distance on a filled pill, copied from Autel Explorer after a wall strike because copying a
 * proven safety display beat inventing one. A GUI refresh replaced it with a
 * gradient: a wash that grows DEEPER into the screen and LESS transparent as the obstacle gets
 * closer.
 *
 * The reason it is better is the reason the arc was chosen in the first place, carried further.
 * An arc had to be READ — found at the edge, its thickness and colour judged. A gradient is
 * seen without being looked at, in the pilot's peripheral vision, while their eyes are on the
 * subject in the centre of the frame. Closing rate becomes a thing that grows toward you rather
 * than a line that thickens.
 *
 * ⚠ **IT COVERS LIVE VIDEO, WHICH IS WHY IT STAYS TRANSLUCENT AT ITS WORST.** This is the same
 * trade the HUD's opaque text panels lost — but it resolves the other way here, and deliberately: a
 * panel covered the picture ALL THE TIME to make text legible, while this appears only when
 * something is within [WARN_CM] and the thing it covers is the direction of the hazard. It is
 * still capped below opaque, because a pilot steering away from an obstacle must be able to see
 * what they are steering INTO.
 *
 * FOUR EDGES PLUS AN EXPLICIT REAR READOUT. The aircraft reports six faces. Left, right, up and
 * down map to screen edges the way Explorer draws them — keeping that shared visual language
 * matters, because a pilot switches between the two apps and must not have to re-learn what
 * an edge means. Front is omitted: an obstacle dead ahead is already IN the video.
 *
 * Rear was omitted too, at first, on the reasoning that a forward-looking view has no honest
 * place to put "behind you". The first flight with it immediately raised where reverse was, which
 * exposed that as exactly backwards — behind the aircraft is the ONE direction the camera cannot
 * show, so it is where a readout earns the most. The fix is to label it, not to drop it: rear
 * gets its own captioned indicator above the bottom arc, visually distinct from the down arc so
 * the two can never be read as each other.
 *
 * UNITS: centimetres. Nothing in the SDK documents this — it was inferred from magnitudes and
 * then FIELD-VALIDATED by flying the display against real obstacles and judging the
 * distances accurate. That is agreement with reality at the ranges that matter,
 * not a calibration, so the readout is trustworthy for flying and should not be quoted to
 * the inch. Every distance still passes through [CM_PER_FOOT] alone, so if a future airframe or
 * firmware disagrees, exactly one number changes.
 */
class ObstacleEdgeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // Flight-tuned HUD colours, resolved once per view — see takpilot_colors.xml.
    private val COLOR_DANGER = ContextCompat.getColor(context, R.color.tp_hud_obstacle_danger)
    private val COLOR_WARN = ContextCompat.getColor(context, R.color.tp_hud_obstacle_warn)

    /** One edge's current state. Distance is in the raw sensor units. */
    private data class Edge(var cm: Int?)

    private val left = Edge(null)
    private val right = Edge(null)
    private val top = Edge(null)
    private val bottom = Edge(null)
    private val rear = Edge(null)

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** Fills the gradient band. The shader carries the colour; [Paint.setAlpha] modulates it. */
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * ⚠ FOUR SHADERS, BUILT ONCE, REUSED FOR EVER — see the no-allocation rule below.
     *
     * Each is a unit-length ramp (0..1) in one direction for one colour, and the LOCAL MATRIX
     * stretches it to the band's current depth and points it at the right edge. Rebuilding a
     * LinearGradient per face per frame would allocate on a view that redraws at the radar's
     * push rate, on the one display that must never stutter.
     *
     * Three stops, not two. A straight ramp to transparent BANDS visibly over live video on this
     * panel; the extra stop at the half-way point bends the falloff so the dense part hugs the
     * edge and the tail fades out of notice.
     */
    private val washShaders = HashMap<Int, android.graphics.LinearGradient>(4)
    private val washMatrix = android.graphics.Matrix()
    /**
     * ⚠ **AMBER ON RED, NOT THE HUD'S WHITE ON BLACK** (design decision). Every other
     * readout on this screen is white text with a black outline, and the obstacle distance was
     * reading as one more of them — on the one display whose whole job is to be told apart from
     * the others at a glance, while the pilot's eyes are on the centre of the frame.
     *
     * ⚠ THE PAIR IS FIXED AND DOES NOT FOLLOW THE STATE. The BAND behind it already carries
     * amber-or-red; a label that changed with it would say the same thing twice and would stop
     * being a constant thing the eye can learn. This says "obstacle"; the wash says "how bad".
     */
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tp_hud_obstacle_warn)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    /** The RED pass behind the amber distance text — see textPaint for why it is not the HUD's
     *  black. It keeps the HUD's WIDTH (`hud_text_outline_width`), so the screen still carries
     *  one edge weight even where it does not carry one edge colour. */
    private val textOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tp_hud_obstacle_danger)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.hud_text_outline_width)
        strokeJoin = Paint.Join.ROUND
    }

    // Reused across draws. This view redraws at the radar's push rate, so onDraw must not
    // allocate: the chevron Path is reset and refilled, and the gradient shaders are built once
    // and re-aimed with a matrix rather than rebuilt. The FontMetrics that used to live here
    // went with the label's pill — outlined text needs no box measured around it.
    private val chevronPath = android.graphics.Path()

    /**
     * Feeds the latest radar sample. Safe to call at the sensor's own rate.
     *
     * The aircraft sends ONE face per push, round-robin, leaving the others zeroed — so this
     * must never clear a face just because it is absent from this sample. Each edge keeps its
     * last real reading until that same face reports again. Clearing on absence made all four
     * edges strobe at the push rate, which is worse than useless on a safety display.
     */
    fun update(info: com.autel.common.flycontroller.visual.AvoidanceRadarInfo?) {
        if (info == null) { clear(); return }
        nearest(info.left)?.let { left.cm = it }
        nearest(info.right)?.let { right.cm = it }
        nearest(info.top)?.let { top.cm = it }
        nearest(info.bottom)?.let { bottom.cm = it }
        nearest(info.rear)?.let { rear.cm = it }
        invalidate()
    }

    fun clear() {
        left.cm = null; right.cm = null; top.cm = null; bottom.cm = null
        rear.cm = null
        invalidate()
    }

    /**
     * Closest real reading on one face, or null if the face said nothing this push.
     *
     * Returns null for BOTH sentinels but they mean opposite things, so the caller must not
     * conflate them: 0 = "not in this push" (keep the old value), 10000 = "clear". Clear is
     * mapped to [CLEAR] rather than null so a face that genuinely sees nothing stops drawing.
     */
    private fun nearest(face: FloatArray?): Int? {
        face ?: return null
        var best: Float? = null
        var sawClear = false
        for (v in face) {
            if (v <= 0f) continue
            if (v >= AutelAvoidance.CLEAR_SENTINEL) { sawClear = true; continue }
            if (best == null || v < best!!) best = v
        }
        return best?.toInt() ?: if (sawClear) CLEAR else null
    }

    /**
     * Height of the toolbar covering the top of the video, in pixels.
     *
     * This view is full-screen behind the app's chrome, so its top edge is NOT the top of what
     * the pilot can see. Fed from the toolbar's real measured height after layout (see
     * FlightActivity) rather than a hardcoded dp, so a toolbar change cannot silently push the
     * top-face warning back out of sight.
     *
     * Only the TOP face needs this: left, right and bottom are clear of the toolbar, and the HUD
     * column on the right sits above this view's right arc without covering it.
     */
    private var topInset = 0f

    fun setTopInset(px: Float) {
        if (topInset == px) return
        topInset = px
        invalidate()
    }

    /** The actions column on the left covers the left face's label at mid-height,
     *  so the LEFT label starts right of the column, the way the top one starts below the
     *  band. The arc itself still draws from the edge: the wash is a warning, not a readout. */
    private var leftInset = 0f

    fun setLeftInset(px: Float) {
        if (leftInset == px) return
        leftInset = px
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        drawEdge(canvas, left, Side.LEFT, w, h)
        drawEdge(canvas, right, Side.RIGHT, w, h)
        drawEdge(canvas, top, Side.TOP, w, h)
        drawEdge(canvas, bottom, Side.BOTTOM, w, h)
        drawRear(canvas, w, h)
    }

    private enum class Side { LEFT, RIGHT, TOP, BOTTOM }

    /**
     * One face, as a gradient bleeding in from its edge.
     *
     * Two things move with the distance and they are the whole design:
     *
     *  - **DEPTH.** The band reaches further toward the centre of the screen as the obstacle
     *    gets closer. That is the part a pilot reads without looking — something growing toward
     *    them from the side the hazard is on.
     *  - **OPACITY.** It also gets less transparent, so a distant obstacle is a hint and a near
     *    one is hard to ignore.
     *
     * Colour keeps the flight-validated meaning it had as an arc: amber beyond [DANGER_CM], red
     * inside it. The step is deliberately hard rather than a blend — "you are now close" is a
     * different statement from "something is there", and a pilot must be able to tell which one
     * they are being given at a glance.
     *
     * ⚠ **THE BAND SPANS THE WHOLE EDGE**, where the arc covered 0.62 of it. An arc needed ends
     * to read as an arc; a wash does not, and a full edge says "this side" more plainly than a
     * centred smear. The TOP band still starts below [topInset] for the reason that inset
     * exists — the one face whose warning means "you are about to hit something above you" was
     * the one drawn under the toolbar.
     */
    private fun drawEdge(canvas: Canvas, e: Edge, side: Side, w: Float, h: Float) {
        val cm = e.cm ?: return
        if (cm == CLEAR || cm > WARN_CM) return          // nothing worth showing

        val t = (1f - (cm.toFloat() / WARN_CM)).coerceIn(0f, 1f)
        val danger = cm <= DANGER_CM
        val color = if (danger) COLOR_DANGER else COLOR_WARN

        // ⚠ **DEPTH RUNS ON t SQUARED, AND THAT IS THE WHOLE POINT OF THE CONTROL**.
        // A linear ramp spends its travel in the far field, because WARN_CM is
        // 39 ft and DANGER_CM is 13 ft: measured on the controller it went 5 % of the screen at
        // 39 ft to 19 % at 13 ft, and then only 19 % to 25 % across the ENTIRE danger range. Two
        // faces at 7.0 ft and 1.6 ft drew 456px and 515px — four times the distance, thirteen
        // per cent of the difference — and read as the same severity.
        //
        // Squaring redistributes it where a pilot needs it: about 14 % at 13 ft and 24 % at
        // contact, ten points of travel across the danger range instead of six, and a quieter
        // far field where an obstacle 39 ft away should be a hint rather than a wash.
        //
        // ⚠ ALPHA STAYS LINEAR. Depth carries the urgency; opacity carries presence. Squaring
        // both would make a distant obstacle both thin AND nearly invisible, and the far-field
        // warning still has to register at the edge.
        val depthT = t * t
        // A fraction of the dimension the band grows ALONG, so it reads the same on a 1024dp
        // controller and a phone — a dp value must not be carried between screens.
        val along = if (side == Side.LEFT || side == Side.RIGHT) w else h
        val depth = along * (MIN_DEPTH_FRAC + (MAX_DEPTH_FRAC - MIN_DEPTH_FRAC) * depthT)
        val vertical = side == Side.TOP || side == Side.BOTTOM
        // ⚠ THE TOP BAND STARTS AT THE TRUE TOP EDGE AND DRAWS BEHIND THE TOOLBAR.
        // The capsules float with video between and around them, so a wash that
        // begins below them reads as starting from nowhere; beginning at the edge is what makes
        // it look like it is coming from outside the frame.
        //
        // ⚠ **IT IS topInset PLUS THE DEPTH, NOT THE DEPTH.** The chrome would otherwise eat
        // the band: at 39 ft the depth is about 38dp against a 60dp toolbar, so the whole thing
        // would hide behind the capsules and the one face that means "you are about to hit
        // something above you" would show nothing at all. Adding the inset keeps the VISIBLE
        // depth below the chrome equal to every other edge's.
        val topSpan = if (side == Side.TOP) topInset + depth else depth

        washPaint.shader = washShader(vertical, danger, color).also { sh ->
            washMatrix.reset()
            when (side) {
                Side.LEFT -> washMatrix.setScale(depth, 1f)
                Side.RIGHT -> { washMatrix.setScale(-depth, 1f); washMatrix.postTranslate(w, 0f) }
                Side.TOP -> washMatrix.setScale(1f, topSpan)
                Side.BOTTOM -> { washMatrix.setScale(1f, -depth); washMatrix.postTranslate(0f, h) }
            }
            sh.setLocalMatrix(washMatrix)
        }
        washPaint.alpha = (MIN_WASH_ALPHA + (MAX_WASH_ALPHA - MIN_WASH_ALPHA) * t).toInt()

        val labelX: Float; val labelY: Float
        when (side) {
            Side.LEFT -> {
                canvas.drawRect(0f, 0f, depth, h, washPaint)
                labelX = leftInset + dp(LABEL_INSET); labelY = h / 2f
            }
            Side.RIGHT -> {
                canvas.drawRect(w - depth, 0f, w, h, washPaint)
                labelX = w - dp(LABEL_INSET); labelY = h / 2f
            }
            Side.TOP -> {
                canvas.drawRect(0f, 0f, w, topSpan, washPaint)
                // ⚠ THE LABEL STILL CLEARS THE CHROME. The band may hide behind a capsule; the
                // DISTANCE must not. That is the whole reason topInset was fed to this view.
                labelX = w / 2f; labelY = topInset + dp(LABEL_INSET)
            }
            Side.BOTTOM -> {
                canvas.drawRect(0f, h - depth, w, h, washPaint)
                labelX = w / 2f; labelY = h - dp(LABEL_INSET)
            }
        }
        washPaint.shader = null
        drawLabel(canvas, labelX, labelY, cm)
    }

    /** Cached per direction and per colour — see [washShaders]. */
    private fun washShader(vertical: Boolean, danger: Boolean, color: Int):
        android.graphics.LinearGradient {
        val key = (if (vertical) 2 else 0) or (if (danger) 1 else 0)
        return washShaders.getOrPut(key) {
            android.graphics.LinearGradient(
                0f, 0f, if (vertical) 0f else 1f, if (vertical) 1f else 0f,
                intArrayOf(color, color and 0x00FFFFFF or (0x66 shl 24), color and 0x00FFFFFF),
                floatArrayOf(0f, MID_STOP, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
    }

    /**
     * Rear proximity, as a captioned readout rather than an edge arc.
     *
     * NOT an arc, deliberately. Every arc in this view means "the hazard is off the screen in
     * this direction", and that reading breaks down for rear — the bottom edge already means
     * DOWN, and a second arc sharing it would make the two indistinguishable at a glance, on a
     * display whose whole job is to be read at a glance. So rear gets a shape nothing else uses:
     * a back-pointing chevron with the word REAR on it. Unmissable, unambiguous, and it cannot be
     * mistaken for the ground.
     *
     * Sits clear above the bottom arc's own label by [REAR_LIFT], which exceeds that arc's
     * maximum bow plus its label height, so the two never collide however close either gets.
     */
    private fun drawRear(canvas: Canvas, w: Float, h: Float) {
        val cm = rear.cm ?: return
        if (cm == CLEAR || cm > WARN_CM) return

        val t = (1f - (cm.toFloat() / WARN_CM)).coerceIn(0f, 1f)
        arcPaint.color = if (cm <= DANGER_CM) COLOR_DANGER else COLOR_WARN
        arcPaint.alpha = (110 + 145 * t).toInt().coerceAtMost(255)
        arcPaint.strokeWidth = dp(4f) + dp(5f) * t

        val cx = w / 2f
        val cy = h - REAR_LIFT * resources.displayMetrics.density

        // Two stacked chevrons pointing DOWN-AND-BACK — away from the direction of view.
        val half = dp(20f)
        val drop = dp(9f)
        for (i in 0 until 2) {
            val yTop = cy + dp(13f) + i * dp(9f)
            chevronPath.reset()
            chevronPath.moveTo(cx - half, yTop)
            chevronPath.lineTo(cx, yTop + drop)
            chevronPath.lineTo(cx + half, yTop)
            arcPaint.style = Paint.Style.STROKE
            canvas.drawPath(chevronPath, arcPaint)
        }

        drawLabel(canvas, cx, cy, cm, "REAR ")
    }

    /**
     * Distance in feet, as OUTLINED TEXT.
     *
     * ⚠ IT WAS WHITE ON A FILLED PILL IN THE ARC'S COLOUR, and the pill went with the arc.
     * Two reasons. The gradient behind it already carries the colour, so a second
     * coloured block said the same thing twice and hid more video doing it. And every other
     * readout on this screen is outlined text over the picture, so the pill was the odd
     * one out on the one display a pilot reads fastest.
     *
     * Same edge weight as the rest of the HUD (`hud_text_outline_width`), thus this cannot
     * drift away from the readouts beside it.
     */
    private fun drawLabel(canvas: Canvas, cx: Float, cy: Float, cm: Int, caption: String = "") {
        val feet = cm / CM_PER_FOOT
        val text = caption + "%.1fft".format(feet)
        textPaint.textSize = dp(15f)
        textOutline.textSize = dp(15f)
        canvas.drawText(text, cx, cy, textOutline)
        canvas.drawText(text, cx, cy, textPaint)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object {
        /**
         * How deep the band reaches at its weakest and at its worst, as a FRACTION of the
         * dimension it grows along. A fraction and not a dp: a dp value must not be carried
         * between screens, and this has to read the same on a 1024dp controller and a phone.
         *
         * At WARN_CM the band is a hint at the edge; at contact it covers about a quarter of
         * the way to the centre, which is hard to miss and still leaves the middle of the frame
         * — where the pilot is looking — clear.
         */
        private const val MIN_DEPTH_FRAC = 0.05f
        private const val MAX_DEPTH_FRAC = 0.26f

        /**
         * Opacity at the edge, weakest to worst, 0-255.
         *
         * ⚠ **THE TOP END IS CAPPED WELL BELOW OPAQUE ON PURPOSE.** This covers live video, and
         * a pilot steering away from an obstacle has to see what they are steering INTO. 185 is
         * about 73 %: unmissable, still see-through.
         */
        private const val MIN_WASH_ALPHA = 50f
        private const val MAX_WASH_ALPHA = 185f

        /**
         * Where the middle gradient stop sits, and it is not decoration. A straight two-stop
         * ramp to transparent BANDS visibly over live video; bending the falloff here keeps the
         * dense part against the edge and fades the tail out of notice.
         */
        private const val MID_STOP = 0.5f

        /** dp from the edge to the distance text. Clears half the text's own width, so the
         *  centred label cannot hang off the side it belongs to. */
        private const val LABEL_INSET = 34f

        /** Face reported "clear". Distinct from "no data" so the edge stops drawing. */
        private const val CLEAR = Int.MAX_VALUE

        /** Start drawing at this range, go red at this one. Both in the raw sensor units. */
        private const val WARN_CM = 1200      // ~39 ft
        private const val DANGER_CM = 400     // ~13 ft

        // Precomputed so onDraw never runs Color.parseColor (a string parse + allocation) per
        // edge per frame. Red inside DANGER_CM, amber beyond it.

        /**
         * ⚠ THE ONE ASSUMPTION IN THIS FILE. Nothing in the SDK documents the radar's units;
         * centimetres is inferred from magnitudes that line up with what Explorer showed at the
         * same moment (its HUD read 6.0/17.0/26.0/28.0 ft while raw samples ran in the tens to
         * low hundreds). Verify by hovering a known distance from a wall and comparing this
         * label against Explorer's. If it is wrong, change ONLY this constant.
         */
        private const val CM_PER_FOOT = 30.48f

        /** dp above the bottom edge for the rear readout. Clears the bottom arc's
         *  maximum bow (56dp) plus its label, so the two never overlap. */
        private const val REAR_LIFT = 118f
    }
}
