package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePointPolicyTest {

    @Test
    fun `the aircraft reporting the requested position counts as moved`() {
        assertEquals(HomeCheck.MOVED, homePointVerdict(aircraftHomeSet = true, separationM = 0.0))
        assertEquals(HomeCheck.MOVED, homePointVerdict(aircraftHomeSet = true, separationM = 2.9))
        assertEquals(HomeCheck.MOVED, homePointVerdict(aircraftHomeSet = true, separationM = 3.0))
    }

    @Test
    fun `a home point left at the old takeoff site is caught`() {
        // The failure worth catching: the pilot walked away from the takeoff point, asked for
        // the home to follow them, was told it had, and Return to Home would still fly to the
        // old site. That distance is tens or hundreds of metres.
        assertEquals(
            HomeCheck.NOT_MOVED,
            homePointVerdict(aircraftHomeSet = true, separationM = 40.0))
        assertEquals(
            HomeCheck.NOT_MOVED,
            homePointVerdict(aircraftHomeSet = true, separationM = 3.1))
    }

    @Test
    fun `no home flag means no answer, not a failure`() {
        // homeEnable is the SDK's own "has a home been recorded yet" flag, and until it is set
        // the reported latitude and longitude are meaningless zeros. Reading those as a real
        // position would put the aircraft's home in the Gulf of Guinea and accuse a write that
        // may have been fine.
        assertEquals(
            HomeCheck.NO_ANSWER,
            homePointVerdict(aircraftHomeSet = false, separationM = 0.0))
        assertEquals(
            HomeCheck.NO_ANSWER,
            homePointVerdict(aircraftHomeSet = false, separationM = 5_000_000.0))
    }

    @Test
    fun `no telemetry means no answer`() {
        assertEquals(
            HomeCheck.NO_ANSWER,
            homePointVerdict(aircraftHomeSet = true, separationM = Double.NaN))
    }
}
