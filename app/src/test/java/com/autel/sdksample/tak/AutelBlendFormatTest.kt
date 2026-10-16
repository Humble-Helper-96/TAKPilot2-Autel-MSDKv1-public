package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blend ratio's arithmetic, pinned without a camera.
 *
 * It is Autel Explorer's own: IR = pct/100 x 65536, Visible = 65535 - IR. The pair is what the
 * pilot's slider sends and what the connect-time write asks for, thus a change here changes the
 * PIP picture on every aircraft running this application.
 */
class AutelBlendFormatTest {

    @Test
    fun `the pair always sums to the camera's scale`() {
        for (pct in 0..100) {
            val (ir, vis) = AutelBlendFormat.ratioFor(pct)
            assertEquals("$pct% must sum to 65535", 65535, ir + vis)
        }
    }

    @Test
    fun `the measured values are what the camera was given`() {
        // 75% is the default. ⚠ THE FORMULA GIVES 49152/16383 AND AN EARLIER VERSION WROTE
        // 49151/16384 — a hand-computed pair, one count low, which the camera took and read
        // back. One part in 65535 is not a picture a pilot can tell apart, but the arithmetic
        // is Explorer's and this follows it rather than the typo.
        assertEquals(49152 to 16383, AutelBlendFormat.ratioFor(75))
        // 50% is what earlier versions wrote, and what Explorer's slider showed as half.
        assertEquals(32768 to 32767, AutelBlendFormat.ratioFor(50))
    }

    @Test
    fun `the ends are whole`() {
        assertEquals(0 to 65535, AutelBlendFormat.ratioFor(0))
        assertEquals(65535 to 0, AutelBlendFormat.ratioFor(100))
    }

    @Test
    fun `out of range percentages are clamped, never wrapped`() {
        assertEquals(AutelBlendFormat.ratioFor(0), AutelBlendFormat.ratioFor(-20))
        assertEquals(AutelBlendFormat.ratioFor(100), AutelBlendFormat.ratioFor(400))
    }

    @Test
    fun `more percent is more thermal, always`() {
        var last = -1
        for (pct in 0..100) {
            val ir = AutelBlendFormat.ratioFor(pct).first
            assertTrue("IR weight must not fall as the percentage rises", ir >= last)
            last = ir
        }
    }
}
