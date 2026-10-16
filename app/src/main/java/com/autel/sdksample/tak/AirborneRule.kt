package com.autel.sdksample.tak

/**
 * Is the aircraft flying? THE one rule — the bridge's `airborne` flag, the drone PLI, the flight
 * record and the flight-screen warnings all take it from here.
 *
 * ## ⚠ THE FLY MODE DECIDES, NOT THE HEIGHT (measured)
 *
 * An earlier rule was `relAlt > 0.5 m || speed > 0.5 m/s`. The aircraft
 * re-zeroes its relative altitude only at TAKEOFF, so a landing that ended with the barometer
 * 1.4 m off kept that 1.4 m until the next takeoff. Measured:
 *
 *  | moment                         | flyMode         | relAlt | old rule  |
 *  |--------------------------------|-----------------|--------|-----------|
 *  | aircraft on, motors off        | DISARM          | 1.4 m  | FLYING ✗  |
 *  | motors spinning on the ground  | MOTOR_SPINNING  | 1.4 m  | flying    |
 *  | takeoff, low hover             | GPS_FLIGHT      | < 0.5  | grounded ✗ (twice) |
 *  | landed, motors stopped         | DISARM          | 0.0 m  | grounded  |
 *
 * The old rule cost: after such a landing, Change Mode said "The aircraft is flying" on the
 * ground, Format Card stayed blocked, the flight record never closed, and a power-up with the
 * motors off STARTED a phantom flight record. The fly mode tracked the aircraft exactly in
 * every row.
 *
 * ## The rule
 *
 *  - `DISARM` (motors off) — grounded. The only grounded mode.
 *  - `MOTOR_SPINNING` — ⚠ FLYING, deliberately. The propellers turn and a takeoff is one stick
 *    movement away: no mode change, no Format Card, no avoidance writes then. It also starts
 *    the flight record at motor start rather than at the first half metre.
 *  - every other known mode (GPS_FLIGHT, ATTI_FLIGHT, TAKEOFF, LANDING, the go-home and mission
 *    modes …) — flying.
 *  - null or `UNKNOWN` (a firmware that sends no mode, or the product is DISCONNECTED) — the
 *    OLD height/speed rule, so this can never be worse than the height rule alone. ⚠ On EVO II the
 *    SDK's status is never null: after a heartbeat loss it is an EMPTY status that reads DISARM.
 *    The bridge therefore passes null while the product is disconnected.
 *
 * The mode is taken by NAME so the rule is a pure function a unit test can hold without the
 * SDK's enum on the classpath.
 */
object AirborneRule {

    fun isFlying(flyModeName: String?, relAltM: Double, speedMs: Double): Boolean =
        when (flyModeName) {
            null, "UNKNOWN" -> heightTest(relAltM, speedMs)
            "DISARM" -> false
            else -> true
        }

    /** The original height/speed rule, kept as the fallback for an unknown mode. */
    internal fun heightTest(relAltM: Double, speedMs: Double): Boolean =
        relAltM.isFinite() && (relAltM > 0.5 || speedMs > 0.5)
}
