package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Test

/** The zoom levels the rocker steps through. The rocker is a hardware control that
 *  cannot be exercised from a test, thus the arithmetic lives in [ZoomLadder] and this pins it.
 *  Without it, a zoom change could only be checked by simulating it by hand. */
class ZoomLadderTest {

    @Test
    fun eachRungStepsToItsNeighbourGoingUp() {
        val r = ZoomLadder.RUNGS_RAW
        for (i in 0 until r.size - 1) {
            assertEquals("up from ${r[i]}", r[i + 1], ZoomLadder.next(r[i], +1))
        }
    }

    @Test
    fun eachRungStepsToItsNeighbourGoingDown() {
        val r = ZoomLadder.RUNGS_RAW
        for (i in 1 until r.size) {
            assertEquals("down from ${r[i]}", r[i - 1], ZoomLadder.next(r[i], -1))
        }
    }

    /** The ends are stops, not wraps. A pilot holding the rocker at 16x must not find the view
     *  snap back to 1x. */
    @Test
    fun theEndsHold() {
        assertEquals(1600, ZoomLadder.next(1600, +1))
        assertEquals(100, ZoomLadder.next(100, -1))
    }

    /** Autel Explorer can leave the camera anywhere. Off a rung, the step goes to the nearest
     *  rung IN THE DIRECTION ASKED FOR — never past it, and never back the way it came. */
    @Test
    fun offLadderValuesResolveToTheNeighbouringRung() {
        assertEquals(300, ZoomLadder.next(250, +1))
        assertEquals(200, ZoomLadder.next(250, -1))
        assertEquals(1600, ZoomLadder.next(1450, +1))
        assertEquals(1200, ZoomLadder.next(1450, -1))
        // Just off a rung, both ways: 401 must not step back onto the 400 it has left.
        assertEquals(600, ZoomLadder.next(401, +1))
        assertEquals(400, ZoomLadder.next(401, -1))
    }

    /** Below 1x or above 16x the camera is somewhere this app cannot have put it. Step back
     *  onto the ladder rather than refusing to move. */
    @Test
    fun valuesOutsideTheLadderComeBackOntoIt() {
        assertEquals(100, ZoomLadder.next(40, +1))
        assertEquals(100, ZoomLadder.next(40, -1))
        assertEquals(1600, ZoomLadder.next(9000, +1))
        assertEquals(1600, ZoomLadder.next(9000, -1))
    }

    @Test
    fun theLadderMatchesTheChosenLevels() {
        assertEquals(
            listOf(1, 2, 3, 4, 6, 8, 10, 12, 16),
            ZoomLadder.RUNGS_RAW.map { it / 100 },
        )
    }
}
