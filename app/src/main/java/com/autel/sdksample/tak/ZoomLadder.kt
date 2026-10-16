package com.autel.sdksample.tak

/**
 * The fixed zoom levels the rocker steps through, and the rule for moving between them.
 *
 * A pilot frames a subject at a few known magnifications, not at the values between them. The
 * rocker once moved the zoom continuously — 0.6x for each tick of held time, with soft detents
 * that parked at 2x, 4x and 8x — and flying it showed that the values between the levels were
 * not wanted. One press moves one level. A held rocker walks the levels and stops on whichever
 * one it reaches.
 *
 * THE LEVELS ARE A DESIGN DECISION. 12x was added to the list first proposed: 10x
 * straight to 16x is a 60% jump, far wider than any other step, and the long end would feel
 * like the control skips.
 *
 * NO ANDROID OR SDK IMPORTS, ON PURPOSE. This is the part with the arithmetic in it, thus the
 * part worth testing, and `FlightActivity` cannot be reached from a unit test. See
 * `ZoomLadderTest`.
 */
object ZoomLadder {

    /**
     * The levels, in RAW CAMERA UNITS — the same hundredths `setDigitalZoomScale` takes, where
     * 100 is 1x. Ascending, and both ends agree with the clamp in `FlightActivity`: the first
     * is `ZOOM_RAW_MIN` and the last is `ZOOM_RAW_MAX`.
     */
    val RUNGS_RAW = intArrayOf(100, 200, 300, 400, 600, 800, 1000, 1200, 1600)

    /**
     * The level one step from [currentRaw], travelling in [direction] (+1 in, -1 out). Returns
     * [currentRaw]'s own end of the ladder when there is nothing further to go to, thus the
     * caller's "did it move?" test doubles as the end stop.
     *
     * STRICTLY GREATER / STRICTLY LESS, not an index step. The camera does not have to be on a
     * level for this to give a sensible answer: Autel Explorer can leave it anywhere, and a
     * camera sitting at 2.5x steps up to 3x and down to 2x. Index arithmetic would need a
     * separate "am I on a rung" branch to do the same thing, and would have to decide what to
     * do when the answer was no.
     */
    fun next(currentRaw: Int, direction: Int): Int = when {
        direction > 0 -> RUNGS_RAW.firstOrNull { it > currentRaw } ?: RUNGS_RAW.last()
        direction < 0 -> RUNGS_RAW.lastOrNull { it < currentRaw } ?: RUNGS_RAW.first()
        else -> currentRaw
    }
}
