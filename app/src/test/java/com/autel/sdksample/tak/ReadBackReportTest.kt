package com.autel.sdksample.tak

import com.autel.sdksample.tak.FlightLimitsController.LimitReadBack
import com.autel.sdksample.tak.FlightLimitsController.ReportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pre-Flight read-back policy. Pure logic: the answers the aircraft gave go in, the
 * pilot's line and the state come out. No aircraft, no SDK, no clock.
 *
 * NOT COVERED HERE: the watchdog and the one-shot guard in
 * [FlightLimitsController.readBack]. Both need a Looper, and this project runs its unit tests
 * with `returnDefaultValues = true` and no Robolectric. They need a check on real hardware:
 * the Apply button must re-enable after an aircraft goes silent mid-verify.
 */
class ReadBackReportTest {

    private fun v(label: String, want: Int?, got: Float?) = LimitReadBack(label, want, got)

    private fun build(values: List<LimitReadBack>, refused: List<String> = emptyList()) =
        FlightLimitsController.buildReadBackReport(values, refused)

    /** Three limits that all agree. 61m = 200ft, 152m = 499ft, 30m = 98ft. */
    private fun allAgree() = listOf(
        v("Max altitude", 61, 61f),
        v("Max distance", 152, 152f),
        v("RTH altitude", 30, 30f),
    )

    @Test
    fun everyValueAgreesIsConfirmed() {
        val r = build(allAgree())
        assertEquals(ReportState.CONFIRMED, r.state)
        assertTrue(r.text.startsWith("The aircraft confirms:"))
        assertTrue("Max altitude 200 ft" in r.text)
        assertTrue("RTH altitude 98 ft" in r.text)
    }

    /**
     * A real case: the pilot asked for 49ft of RTH altitude and the aircraft flew
     * 151ft. The line must carry BOTH numbers — "wrong" with no values is not actionable.
     */
    @Test
    fun aValueThatDisagreesIsAProblem() {
        val r = build(listOf(
            v("Max altitude", 61, 61f),
            v("Max distance", 152, 152f),
            v("RTH altitude", 15, 46f),
        ))
        assertEquals(ReportState.PROBLEM, r.state)
        assertTrue("RTH altitude is 151 ft, not 49 ft" in r.text)
    }

    @Test
    fun aValueInsideToleranceStillMatches() {
        val r = build(listOf(v("Max altitude", 61, 61.5f)))
        assertEquals(ReportState.CONFIRMED, r.state)
    }

    @Test
    fun aValueOutsideToleranceIsAMismatch() {
        val r = build(listOf(v("Max altitude", 61, 61.7f)))
        assertEquals(ReportState.PROBLEM, r.state)
    }

    /**
     * THE REGRESSION FOR THE AMBER BUG. A getter that answered with no value once fell into
     * neither the "got" nor the "failed" list, thus it rendered as a red "did not take all
     * the settings" with the value simply missing from the line. A silent aircraft is UNKNOWN.
     */
    @Test
    fun anUnansweredValueIsUnknownNotAFailure() {
        val r = build(listOf(
            v("Max altitude", 61, 61f),
            v("Max distance", 152, 152f),
            v("RTH altitude", 30, null),
        ))
        assertEquals(ReportState.UNKNOWN, r.state)
        assertTrue("did not answer for: RTH altitude" in r.text)
        // The two that DID answer are still reported — a silent third must not hide them.
        assertTrue("Max altitude 200 ft" in r.text)
    }

    @Test
    fun everyValueUnansweredIsUnknown() {
        val r = build(listOf(
            v("Max altitude", 61, null),
            v("Max distance", 152, null),
            v("RTH altitude", 30, null),
        ))
        assertEquals(ReportState.UNKNOWN, r.state)
        assertTrue("Max altitude" in r.text)
        assertTrue("RTH altitude" in r.text)
        assertTrue("nothing was confirmed, so no confirms clause", "It confirms" !in r.text)
    }

    /**
     * A refused write the pilot CAN act on reaches them even when every value that has a getter
     * agrees — without this they would read a clean green line for an apply the aircraft partly
     * rejected.
     */
    @Test
    fun aRefusedWriteIsAProblemEvenWhenEveryValueAgrees() {
        val r = build(allAgree(), listOf("Low battery level"))
        assertEquals(ReportState.PROBLEM, r.state)
        assertTrue("It refused: Low battery level." in r.text)
        // and it still says what the aircraft did confirm
        assertTrue("It confirms:" in r.text)
    }

    /**
     * A clean apply reads clean. RF power and the signal-loss behaviour are no longer recorded
     * as refusals — the firmware always refuses both and no pilot can change
     * either, so a red "correct the values" on every apply was training pilots to dismiss this
     * report. The signal-loss state now lives on the Enter Flight card instead.
     */
    @Test
    fun anApplyWithNoActionableRefusalIsClean() {
        val r = build(allAgree())
        assertEquals(ReportState.CONFIRMED, r.state)
        assertTrue("Correct the values" !in r.text)
        assertTrue("RF power" !in r.text)
        assertTrue("Signal-loss" !in r.text)
    }

    @Test
    fun aProblemBeatsAnUnknown() {
        val r = build(listOf(
            v("Max altitude", 61, 46f),
            v("Max distance", 152, null),
            v("RTH altitude", 30, 30f),
        ))
        assertEquals(ReportState.PROBLEM, r.state)
        assertTrue("Max altitude is 151 ft, not 200 ft" in r.text)
        assertTrue("did not answer for: Max distance" in r.text)
    }

    /** A blank Pre-Flight field asks for nothing, thus whatever the aircraft holds is right. */
    @Test
    fun anEmptyFieldIsNotAMismatch() {
        val r = build(listOf(v("Max altitude", null, 61f)))
        assertEquals(ReportState.CONFIRMED, r.state)
        assertTrue("Max altitude 200 ft" in r.text)
    }

    @Test
    fun refusedWritesAreDeduplicatedAndKeepTheirOrder() {
        val refused = FlightLimitsController.RefusedWrites()
        refused.add("Low battery level")
        refused.add("RF power")
        refused.add("Low battery level")
        assertEquals(listOf("Low battery level", "RF power"), refused.snapshot())

        // The snapshot is a copy: a caller that holds one cannot change the collector.
        val snap = refused.snapshot().toMutableList()
        snap.clear()
        assertEquals(2, refused.snapshot().size)
    }
}
