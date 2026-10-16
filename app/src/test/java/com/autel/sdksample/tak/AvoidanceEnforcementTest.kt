package com.autel.sdksample.tak

import com.autel.sdksample.tak.AvoidanceEnforcement.Outcome
import com.autel.sdksample.tak.AvoidanceEnforcement.Switch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the enforcement decision core. Pure logic: desired versus cached aircraft
 * state in, verify/retry/give-up out. A LANDING_PROTECT write that times out is the
 * case these rules pin.
 */
class AvoidanceEnforcementTest {

    private val allOn = mapOf(Switch.SYSTEM to true, Switch.RTH to true, Switch.LANDING to true)

    private fun actual(sys: Boolean? = true, rth: Boolean? = true, landing: Boolean? = true) =
        mapOf(Switch.SYSTEM to sys, Switch.RTH to rth, Switch.LANDING to landing)

    @Test
    fun allMatchingVerifies() {
        assertEquals(Outcome.Verified,
            AvoidanceEnforcement.decide(allOn, actual(), attempt = 1, maxAttempts = 3))
    }

    @Test
    fun mismatchRetriesOnlyThatSwitch() {
        // The beep-burst rule: never rewrite a switch that already matches.
        assertEquals(Outcome.Retry(setOf(Switch.LANDING)),
            AvoidanceEnforcement.decide(allOn, actual(landing = false), attempt = 1, maxAttempts = 3))
    }

    @Test
    fun exhaustedAttemptsGiveUp() {
        assertEquals(Outcome.GiveUp(setOf(Switch.LANDING)),
            AvoidanceEnforcement.decide(allOn, actual(landing = false), attempt = 4, maxAttempts = 3))
    }

    @Test
    fun unknownCacheIsNotAMatch() {
        // A dark feed must not read as success: unknown is its own state.
        assertEquals(Outcome.Retry(setOf(Switch.LANDING)),
            AvoidanceEnforcement.decide(allOn, actual(landing = null), attempt = 2, maxAttempts = 3))
        assertEquals(Outcome.GiveUp(setOf(Switch.LANDING)),
            AvoidanceEnforcement.decide(allOn, actual(landing = null), attempt = 4, maxAttempts = 3))
    }

    @Test
    fun retryThatTookVerifiesOnNextPass() {
        assertEquals(Outcome.Retry(setOf(Switch.LANDING)),
            AvoidanceEnforcement.decide(allOn, actual(landing = false), attempt = 1, maxAttempts = 3))
        assertEquals(Outcome.Verified,
            AvoidanceEnforcement.decide(allOn, actual(), attempt = 2, maxAttempts = 3))
    }

    // ---- matches: the airborne drift check ----

    private val S = AvoidanceEnforcement.Switch.SYSTEM
    private val R = AvoidanceEnforcement.Switch.RTH
    private val L = AvoidanceEnforcement.Switch.LANDING

    @Test
    fun `all switches agreeing is a match`() {
        assertTrue(AvoidanceEnforcement.matches(
            mapOf(S to true, R to true, L to true),
            mapOf(S to true, R to true, L to true)))
    }

    @Test
    fun `a reconnect with landing protection off is a mismatch`() {
        // A 44-second link loss has been seen to come back with landing protection OFF against
        // a Pre-Flight selection of ON. On the ground enforcement fixes it; at altitude
        // the enforcement is skipped, and this is what has to notice instead.
        assertFalse(AvoidanceEnforcement.matches(
            mapOf(S to true, R to true, L to true),
            mapOf(S to true, R to true, L to false)))
    }

    @Test
    fun `a switch the aircraft has not reported is NOT a mismatch`() {
        // ⚠ Null is "we have not been told", and treating it as wrong would put an amber banner
        // up on every connect for the second or two before the standing listener populates.
        // Unknown is its own state; the caller re-asks on the next push.
        assertTrue(AvoidanceEnforcement.matches(
            mapOf(S to true, R to true, L to true),
            mapOf(S to null, R to null, L to null)))
        assertTrue(AvoidanceEnforcement.matches(
            mapOf(S to true, R to true, L to true),
            mapOf(S to true, R to true, L to null)))
    }

    @Test
    fun `a known mismatch still counts when its neighbours are unknown`() {
        assertFalse(AvoidanceEnforcement.matches(
            mapOf(S to true, R to true, L to true),
            mapOf(S to null, R to null, L to false)))
    }
}
