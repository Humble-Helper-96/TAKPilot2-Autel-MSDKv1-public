package com.autel.sdksample.tak

/**
 * The vertical datum rules of the AR overlay, in one place and with no Android in them.
 * Pinned by [ElevationPolicyTest].
 *
 * Three heights meet on the flight screen and every one of them is in a different frame:
 *
 *  - the aircraft reports height ABOVE TAKEOFF (barometric, good);
 *  - DTED is height above MEAN SEA LEVEL (the geoid);
 *  - a CoT contact carries `hae`, height above the WGS84 ELLIPSOID.
 *
 * MSL and HAE differ by the geoid separation N, typically ±tens of metres: `hae = msl + N`.
 */

/** Where the aircraft's sea-level height came from, so a reader can tell a fact from an estimate. */
enum class MslSource {
    /** DTED at the takeoff point plus height above takeoff. The reference. */
    TAKEOFF_REFERENCE,
    /**
     * DTED under the aircraft NOW plus height above takeoff — assumes the aircraft took off
     * from ground at the same level as the ground it is over. Wrong by the terrain difference
     * between the two, which is small for a hover mission and zero at the launch site. Used
     * only while no takeoff reference has latched.
     */
    TERRAIN_UNDER_AIRCRAFT,
    /** Nothing to difference against. Everything falls back to the flat plane. */
    NONE,
}

/**
 * The aircraft's sea-level height, and where it came from.
 *
 * ⚠ **WITHOUT A TAKEOFF REFERENCE, DO NOT FALL STRAIGHT TO THE FLAT PLANE.** Placing every
 * marker on the plane through the takeoff point — the "no aircraft MSL, flat-plane estimates"
 * warning — draws markers that sit 50 ft above the takeoff point 50 ft low.
 * DTED under the aircraft is usually available even when the takeoff reference is not (home
 * point not yet set, or the aircraft never armed), and it recovers most of that:
 * the target's terrain difference from the aircraft's own ground is what matters and it is
 * captured; only the launch-site-to-here difference is lost.
 */
internal fun estimateAircraftMsl(
    takeoffTerrainElevMsl: Double?,
    terrainUnderAircraftMsl: Double?,
    heightAboveTakeoff: Double,
): Pair<Double?, MslSource> = when {
    takeoffTerrainElevMsl != null ->
        (takeoffTerrainElevMsl + heightAboveTakeoff) to MslSource.TAKEOFF_REFERENCE
    terrainUnderAircraftMsl != null ->
        (terrainUnderAircraftMsl + heightAboveTakeoff) to MslSource.TERRAIN_UNDER_AIRCRAFT
    else -> null to MslSource.NONE
}

/**
 * The height of a dropped pin above the aircraft (negative below, the normal case).
 *
 * ⚠ **A PIN WITH NO ELEVATION MUST NOT BE PLACED AT SEA LEVEL.**
 * A map-tap pin, a pin restored from disk, or a look-point pin dropped where DTED has no
 * coverage has no altitude. Carried as `0.0` and differenced against the aircraft's real MSL,
 * it gives `0.0 − (60 + relAlt)` at a 60 m field, 60 m of fictitious depth —
 * 17° low at 15° down and pushed off the bottom of the frame at 60° down. Silent, because
 * `0.0` is indistinguishable from "at sea level".
 *
 * "Unknown" is therefore NaN, and an unknown pin is placed on the DTED terrain under it, the way a
 * ground contact already is. Only with no terrain under the pin AND no aircraft MSL does it
 * fall to the flat plane.
 */
internal fun pinHeightAboveAircraft(
    pinAltMsl: Double,
    terrainUnderPinMsl: Double?,
    aircraftMsl: Double?,
    heightAboveTakeoff: Double,
): Double = when {
    aircraftMsl != null && pinAltMsl.isFinite() -> pinAltMsl - aircraftMsl
    aircraftMsl != null && terrainUnderPinMsl != null -> terrainUnderPinMsl - aircraftMsl
    else -> -heightAboveTakeoff
}

/**
 * The geoid separation the aircraft's own receiver applies, from its two altitudes:
 * `N = hae − msl`. Both come from one solution, so the fix's own noise cancels — the aircraft
 * GPS height has been seen to drift 309 m to 188 m in the first minute of a flight with N
 * untouched by that. Null when either figure is missing or the pair is implausible; the
 * plausible band is the whole earth's, −110 m to +90 m, with margin.
 *
 * ⚠ **WITHOUT THIS, A CONTACT'S `hae` CARRIES THE SEPARATION** when differenced against the
 * aircraft's MSL: 12.2 m measured, 2.3° at 253 m and 24° at 26 m. Ground contacts use DTED
 * and avoid it; everything else — a person on a roof, another aircraft, a non-ground
 * marker — needs this correction.
 */
internal fun geoidSeparation(haeM: Double, mslM: Double): Double? {
    if (!haeM.isFinite() || !mslM.isFinite()) return null
    // No fix reads as two zeros; the SDK's first-fix placeholder as ONE zero with a valid
    // partner — at a site below 130 m MSL that would seed N = -MSL.
    if (haeM == 0.0 || mslM == 0.0) return null
    val n = haeM - mslM
    return n.takeIf { it > -130.0 && it < 110.0 }
}

/** A reported `hae` brought into the DTED frame, when the separation is known. Without it the
 *  figure is returned as it came, and still carries N. */
internal fun reportedHaeToMsl(haeM: Double, geoidN: Double?): Double =
    if (geoidN != null) haeM - geoidN else haeM

/**
 * Smooths the geoid separation across samples. Measured in flight: the aircraft reports both
 * altitudes in whole metres and they do not come from the same instant, so the raw N walked
 * from 10 to 16 m over one flight around a true 12.5 — up to 3.5 m, half a degree at 30 m,
 * flickering at 2 Hz. A slow exponential average holds it near the truth; the first sample
 * seeds it so there is never a period of "no separation" once one has been seen.
 */
internal const val GEOID_SMOOTHING = 0.05

internal fun smoothGeoid(previous: Double?, sample: Double): Double =
    if (previous == null) sample else previous + GEOID_SMOOTHING * (sample - previous)
