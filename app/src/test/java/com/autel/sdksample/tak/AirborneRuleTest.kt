package com.autel.sdksample.tak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [AirborneRule] to measurements taken on the aircraft. Each case is a row that was seen
 * on the aircraft, with the relAlt and speed it carried.
 */
class AirborneRuleTest {

    @Test
    fun `motors off with a stale 1_4 m relAlt is grounded`() {
        // A height-only rule calls this flying.
        assertFalse(AirborneRule.isFlying("DISARM", 1.4, 0.0))
        assertTrue(AirborneRule.heightTest(1.4, 0.0))
    }

    @Test
    fun `motors off and landed is grounded`() {
        assertFalse(AirborneRule.isFlying("DISARM", 0.0, 0.0))
    }

    @Test
    fun `motors spinning on the ground counts as flying, deliberately`() {
        assertTrue(AirborneRule.isFlying("MOTOR_SPINNING", 1.4, 0.0))
        assertTrue(AirborneRule.isFlying("MOTOR_SPINNING", 0.0, 0.0))
    }

    @Test
    fun `a low hover in GPS flight is flying even under half a metre`() {
        // A height-only rule calls frames like these grounded.
        assertTrue(AirborneRule.isFlying("GPS_FLIGHT", 0.2, 0.1))
        assertFalse(AirborneRule.heightTest(0.2, 0.1))
    }

    @Test
    fun `every other known mode is flying`() {
        for (m in listOf("TAKEOFF", "LANDING", "ATTI_FLIGHT", "NORMAL_GO_HOME",
                "LOW_BATTERY_GO_HOME", "RC_LOST_GO_HOME", "WAYPOINT_MODE", "ORBIT_ORBIT")) {
            assertTrue(m, AirborneRule.isFlying(m, 0.0, 0.0))
        }
    }

    @Test
    fun `an unknown mode falls back to the height test`() {
        assertFalse(AirborneRule.isFlying(null, 0.0, 0.0))
        assertTrue(AirborneRule.isFlying(null, 3.0, 0.0))
        assertTrue(AirborneRule.isFlying("UNKNOWN", 0.0, 2.0))
        assertFalse(AirborneRule.isFlying("UNKNOWN", Double.NaN, 0.0))
    }
}
