package com.autel.sdksample.tak

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Computes the ground point the camera is looking at (the "slant point" / sensor
 * point of interest) from the aircraft position, altitude, and camera orientation.
 *
 * Default (no [DtedIndex] coverage) assumes flat ground at the aircraft's takeoff altitude
 * (good over level terrain). When DTED coverage is available for the area, [compute] instead
 * finds where the camera ray first meets the terrain — see its doc.
 */
object CameraSlantPoint {

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val DEG = Math.PI / 180.0
    private const val MAX_RANGE_M = 10_000.0
    // When altitude/geometry can't give a valid range, plant the point this far along the
    // camera bearing (so a point shows even on the ground).
    private const val FALLBACK_RANGE_M = 300.0
    /** Below this depression the camera is at or above the horizon: no ground solve. */
    private const val MIN_DEPRESSION_DEG = 1.0
    /** Ray-march steps: finer than DTED2's ~30 m posts near, coarser far out. */
    private const val MARCH_STEP_NEAR_M = 10.0
    private const val MARCH_STEP_FAR_M = 25.0
    private const val BISECT_TOLERANCE_M = 0.5

    /**
     * @param elevationMeters terrain elevation at (lat, lon) as sampled from DTED during the
     *   iteration that produced this point, or NaN when there was no coverage to sample (the
     *   flat-ground path has no way to know the target's absolute elevation — it only ever
     *   knows height *relative* to the aircraft's own ground). ⚠ It used to be 0.0, and 0.0 is
     *   sea level: a pin dropped without coverage was then drawn 60 m deep once the
     *   aircraft's real MSL was known. Unknown must look unknown.
     */
    data class GroundPoint(
        val lat: Double,
        val lon: Double,
        val rangeMeters: Double,
        val elevationMeters: Double = Double.NaN,
    )

    /**
     * Where the camera is looking, projected onto the ground.
     *
     * Method: slant range = altitude / sin(|pitch|), capped to
     * [MAX_RANGE_M]; if the geometry is invalid (no altitude, camera near level/up), fall
     * back to [FALLBACK_RANGE_M] so a point still shows. Then project that HORIZONTAL
     * distance from the aircraft along the camera bearing.
     *
     * When [elevationAt] is supplied (typically [DtedIndex]::elevationAt) and covers the
     * aircraft's own position, the camera ray is marched out over the terrain and the first
     * crossing is bisected (see the body for why the old fixed-point iteration was
     * replaced). Off the loaded terrain it finishes on a flat plane at the last terrain seen.
     *
     * The frame is consistent, with no datum mix: [aircraftMslMeters] is the DTED elevation of
     * the takeoff point plus the height above it, and every target elevation is the same DTED,
     * so both are EGM96 MSL. There is no GPS-ellipsoid mix here, so no geoid correction is
     * needed.
     *
     * @param lat,lon     aircraft position (deg)
     * @param aglMeters   aircraft height above the ground point (m); assume flat ground
     * @param bearingDeg  true bearing the camera points along (deg, 0..360)
     * @param pitchDeg    gimbal pitch (deg): level 0, down negative, clamped at -90.
     * @param elevationAt optional terrain elevation lookup (meters) at a given (lat, lon);
     *                    null/omitted keeps the original flat-ground behavior.
     */
    fun compute(
        lat: Double, lon: Double, aglMeters: Double,
        bearingDeg: Double, pitchDeg: Double,
        elevationAt: ((Double, Double) -> Double?)? = null,
        aircraftMslMeters: Double? = null,
    ): GroundPoint {
        // ⚠ Past -90 the camera cannot point. A negative aim offset on a nadir gimbal gave
        // -91.25, tan() went negative and the point was thrown 300 m forward.
        val pitch = pitchDeg.coerceAtLeast(-90.0)
        val flat = computeFlat(lat, lon, aglMeters, bearingDeg, pitch)
        if (elevationAt == null) return flat

        val groundElevAtAircraft = elevationAt(lat, lon) ?: return flat
        val depression = -pitch
        if (depression <= MIN_DEPRESSION_DEG) return flat

        // The aircraft's height in the terrain's frame. Prefer the true MSL frame: [aglMeters]
        // is height above the TAKEOFF point, which equals height above the ground below only
        // until the aircraft flies over different terrain.
        val aircraftH = aircraftMslMeters ?: (groundElevAtAircraft + aglMeters)
        if (aircraftH - groundElevAtAircraft <= 0.0) return flat

        // ⚠ A RAY MARCH, NOT A FIXED-POINT ITERATION. The old loop took
        // r = (H - terrain(r)) / tan(dep) four times and returned the last guess as if solved.
        // It converges only while the terrain slope along the look is below tan(dep), so on a
        // hillside or at a shallow pitch it oscillated or diverged: on one test flight 21
        // of 302 published points were more than 20 m off, the worst 349 m. Here the camera
        // ray is walked outward until it first meets the terrain, then that crossing is
        // bisected. The FIRST crossing is the right one: it is what the camera sees.
        val tanDep = tan(depression * DEG)
        fun rayH(r: Double) = aircraftH - r * tanDep
        fun terrainAt(r: Double): Double? {
            val p = project(lat, lon, bearingDeg, r)
            return elevationAt(p.lat, p.lon)
        }

        var rPrev = 0.0
        var lastTerrain = groundElevAtAircraft
        var r = 0.0
        while (r < MAX_RANGE_M) {
            r = (r + if (r < 2_000.0) MARCH_STEP_NEAR_M else MARCH_STEP_FAR_M).coerceAtMost(MAX_RANGE_M)
            val terrain = terrainAt(r)
            if (terrain == null) {
                // Off the loaded terrain: finish on a flat plane at the last terrain seen.
                val rFlat = ((aircraftH - lastTerrain) / tanDep).coerceIn(rPrev, MAX_RANGE_M)
                return project(lat, lon, bearingDeg, rFlat).copy(elevationMeters = lastTerrain)
            }
            if (rayH(r) <= terrain) {
                // Crossed between rPrev and r: bisect to well under a metre.
                var lo = rPrev
                var hi = r
                var hiTerrain: Double = terrain
                while (hi - lo > BISECT_TOLERANCE_M) {
                    val mid = (lo + hi) / 2
                    val tm = terrainAt(mid) ?: break
                    if (rayH(mid) <= tm) { hi = mid; hiTerrain = tm } else lo = mid
                }
                return project(lat, lon, bearingDeg, hi).copy(elevationMeters = hiTerrain)
            }
            rPrev = r
            lastTerrain = terrain
        }
        // No ground within MAX_RANGE_M: clamp there, not at the 300 m fallback (the
        // point used to jump from ~10 km to 300 m on one tick).
        return project(lat, lon, bearingDeg, MAX_RANGE_M).copy(elevationMeters = lastTerrain)
    }

    private fun computeFlat(
        lat: Double, lon: Double, aglMeters: Double,
        bearingDeg: Double, pitchDeg: Double,
    ): GroundPoint {
        val depression = -pitchDeg.coerceAtLeast(-90.0) // angle below horizon
        val horizontalRange: Double = if (aglMeters > 0.0 && depression > MIN_DEPRESSION_DEG) {
            // tan gives the ground (horizontal) distance directly.
            val d = aglMeters / tan(depression * DEG)
            when {
                !d.isFinite() || d < 0.0 -> FALLBACK_RANGE_M
                d > MAX_RANGE_M -> MAX_RANGE_M   // clamp, not the fallback
                else -> d
            }
        } else {
            FALLBACK_RANGE_M
        }
        return project(lat, lon, bearingDeg, horizontalRange)
    }

    /** Move [distanceM] from (lat,lon) along [bearingDeg] (great-circle, deg result). */
    /** The point [distanceM] from (lat, lon) along [bearingDeg], great circle. */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> =
        project(lat, lon, bearingDeg, distanceM).let { it.lat to it.lon }

    private fun project(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): GroundPoint {
        val angDist = distanceM / EARTH_RADIUS_M
        val br = bearingDeg * DEG
        val lat1 = lat * DEG
        val lon1 = lon * DEG

        val lat2 = asin(
            sin(lat1) * cos(angDist) + cos(lat1) * sin(angDist) * cos(br)
        )
        val lon2 = lon1 + atan2(
            sin(br) * sin(angDist) * cos(lat1),
            cos(angDist) - sin(lat1) * sin(lat2)
        )
        val outLon = ((lon2 / DEG + 540.0) % 360.0) - 180.0 // normalize to -180..180
        return GroundPoint(lat2 / DEG, outLon, distanceM)
    }

    /** Normalize any heading/bearing to 0..360. */
    fun norm360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /** Great-circle distance between two points (haversine), meters. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * DEG
        val dLon = (lon2 - lon1) * DEG
        val a = sin(dLat / 2).let { it * it } +
            cos(lat1 * DEG) * cos(lat2 * DEG) * sin(dLon / 2).let { it * it }
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** True (not magnetic) initial bearing from point 1 to point 2, degrees 0..360. */
    fun initialBearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = lat1 * DEG
        val phi2 = lat2 * DEG
        val dLon = (lon2 - lon1) * DEG
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return norm360(atan2(y, x) / DEG)
    }

}
