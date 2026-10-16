package com.autel.sdksample.tak

/**
 * Did the aircraft's home point really move to where the pilot asked?
 *
 * ⚠ **THIS IS THE READOUT WITH THE WORST CONSEQUENCE IF IT LIES.** The home point is where
 * Return to Home flies the aircraft. `setLocationAsHomePoint` reported success and this
 * application told the pilot "Home Point Updated" on that word alone — an acknowledgement
 * reported as a result, on the one control where being wrong sends the airframe to the wrong field.
 *
 * There is no need to ask the aircraft anything. It reports its own home point in the
 * fly-controller telemetry that already arrives at about 2 Hz — `homeEnable`, `homeLatitude`,
 * `homeLongitude`, read at [AutelTakBridge] and carried on the HUD snapshot, where the flight
 * screen has been drawing the home marker and the home distance from it all along. The truth
 * was already on the screen; nothing compared it with the request.
 *
 * ⚠ **THE BLIND SPOT IS REAL AND IT IS HARMLESS, WHICH IS WHY THE TOLERANCE CAN BE GENEROUS.**
 * This cannot tell "the home point moved to where I stand" from "the home point never moved"
 * when the pilot is standing within [HOME_POINT_TOLERANCE_M] of the old one. It does not need
 * to: in that case Return to Home brings the aircraft to the same patch of ground either way,
 * so the two outcomes the check confuses are the two outcomes that do not differ. The failure
 * worth catching is the home point staying at a takeoff site the pilot has since walked away
 * from, and that is tens or hundreds of metres, not three.
 */
internal enum class HomeCheck {
    /** The aircraft reports a home point at the requested position. */
    MOVED,
    /** The aircraft reports a home point somewhere else. The request did not take. */
    NOT_MOVED,
    /** The aircraft has not reported a usable home point. Say nothing either way. */
    NO_ANSWER,
}

/**
 * How near the aircraft's reported home must be to the requested position to count as moved.
 *
 * Both positions are plain degrees and no unit conversion happens on the way — unlike the
 * flight limits, which go over the wire as metres rounded from feet and need a tolerance for
 * that alone. Three metres is here for coordinate quantisation in the aircraft's own store,
 * and it is far tighter than any home-point error that would matter. See the blind-spot note.
 */
internal const val HOME_POINT_TOLERANCE_M = 3.0

/**
 * How long the aircraft has to show the new home point before the pilot is told it did not
 * take. The telemetry arrives at about 2 Hz, thus this is several reports, not one.
 */
internal const val HOME_VERIFY_TIMEOUT_MS = 3000L

/** How often the telemetry is re-read while waiting. One tick is faster than the ~2 Hz push,
 *  so no report is missed and the confirmation lands as soon as one arrives. */
internal const val HOME_VERIFY_POLL_MS = 250L

/**
 * @param aircraftHomeSet the SDK's own `homeEnable` flag. Until it is set, the reported
 *   latitude and longitude are meaningless zeros — see [AutelTakBridge].
 * @param separationM metres between the requested position and the one the aircraft reports.
 */
internal fun homePointVerdict(aircraftHomeSet: Boolean, separationM: Double): HomeCheck = when {
    !aircraftHomeSet || !separationM.isFinite() -> HomeCheck.NO_ANSWER
    separationM <= HOME_POINT_TOLERANCE_M -> HomeCheck.MOVED
    else -> HomeCheck.NOT_MOVED
}
