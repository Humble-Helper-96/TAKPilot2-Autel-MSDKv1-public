package com.autel.sdksample.tak

import android.content.Context
import com.taklite.util.AppLog

/**
 * Turns Autel's takeoff-relative altitude into a true height above the ground *under the
 * aircraft*, using the pilot's imported DTED ([DtedIndex]), read from Autel's telemetry
 * cache ([AutelTakBridge]).
 *
 * **The correction.** `LocalCoordinateInfo.altitude` (the bridge's `relAlt`) is height above
 * the takeoff point, not above the terrain below. Over flat ground those agree; fly off a
 * bluff or up a valley and they diverge by exactly the terrain difference. So:
 *
 *     correctedAgl = takeoffRelativeAlt + (terrainElevAtTakeoff − terrainElevUnderAircraft)
 *
 * **Why a difference of two DTED samples, rather than absolute altitudes.** DTED is
 * MSL-referenced while GNSS altitude is WGS84-ellipsoid; that geoid offset is tens of metres in
 * places and is a known open item for this app's SPoI. Here it **cancels** — both samples come
 * from the same dataset in the same datum, so only their difference is used and the offset drops
 * out entirely. That's why this deliberately does NOT use `EvoGpsInfo.getHeightMeanSeaLevel()`
 * as the takeoff reference: mixing an SDK "altitude above sea level" (whose datum and error we
 * can't verify) with a DTED MSL sample would reintroduce exactly the datum problem this
 * formulation avoids.
 *
 * **The takeoff reference is latched, not re-read.** It's captured from the home location the
 * first time one is available (`LocalCoordinateInfo.getHomeEnable()`), because at that moment
 * home *is* the takeoff point — but the pilot can move the home point mid-flight. The
 * aircraft's altitude stays referenced to where it actually took off, so the terrain reference
 * has to as well; re-reading it from a moved home point would silently corrupt the correction
 * by the terrain difference between the two.
 *
 * Re-taken at EVERY takeoff via [onTakeoff] (motor start), and re-read via [onTerrainChanged]
 * when terrain is imported or deleted. Latching once per bridge session would keep the first
 * site's elevation through a battery swap and a move.
 *
 * **Residual error sources** (this is a correction, not truth): DTED's own vertical accuracy,
 * its post spacing smoothing over real terrain, and any difference between the true takeoff
 * elevation and DTED's value there — taking off from a rooftop, a vehicle, or a riverbank the
 * terrain model doesn't resolve all bias the reference. It is still substantially better than
 * no correction the moment the aircraft leaves the elevation it launched from.
 */
object TerrainAgl {
    private const val TAG = "TerrainAgl"

    /** Re-sample the terrain under the aircraft only after it's moved this far horizontally.
     *  Each sample opens a file and does four seeks ([DtedTile.elevationAt] interpolates
     *  bilinearly), and this runs on the flight screen's telemetry tick — while hovering,
     *  the answer isn't changing, so neither should the I/O. Comfortably finer than DTED's own
     *  post spacing, so it costs no meaningful accuracy. */
    private const val RESAMPLE_DISTANCE_M = 15.0

    /** Height above the ground under the aircraft, in metres, plus whether DTED actually
     *  informed it. [terrainCorrected] false means this is the raw takeoff-relative altitude —
     *  callers must label the two differently, since presenting an uncorrected figure as "AGL"
     *  is the inaccuracy this class exists to remove. */
    data class Reading(
        val meters: Double,
        val terrainCorrected: Boolean,
        /**
         * Altitude above mean sea level, metres, or null if it can't be known yet.
         *
         * Needs ONLY the takeoff terrain elevation — `takeoffElevMsl + heightAboveTakeoff` — so
         * it's available in strictly more situations than the AGL correction, which also needs
         * terrain under the aircraft's current position. Expect MSL to be populated while AGL
         * is still falling back to the raw altitude, e.g. flying off the edge of the imported
         * terrain.
         */
        val mslMeters: Double?,
        /** Where [mslMeters] came from — see [MslSource]. A reader that prints a number should
         *  know whether it is the takeoff reference or the stand-in. */
        val mslSource: MslSource = MslSource.NONE,
    )

    @Volatile private var takeoffTerrainElevM: Double? = null

    /** Where the motors last started — the takeoff point — or NaN before any start was seen. */
    private var takeoffLat = Double.NaN
    private var takeoffLon = Double.NaN

    /** The motors started at (lat, lon): that is the takeoff point. Drops the old reference;
     *  the next HUD tick latches the terrain there. */
    @Synchronized
    fun onTakeoff(lat: Double, lon: Double) {
        takeoffLat = lat
        takeoffLon = lon
        takeoffTerrainElevM = null
        AppLog.i(TAG, "motor start: takeoff point %.5f, %.5f — terrain reference re-taken".format(lat, lon))
    }

    /** The terrain data changed (import or delete). The same takeoff point is read again from
     *  the new data, and the under-aircraft sample is dropped. */
    @Synchronized
    fun onTerrainChanged() {
        takeoffTerrainElevM = null
        cachedForLat = Double.NaN
        cachedForLon = Double.NaN
        cachedTerrainElevM = null
    }

    private var cachedForLat = Double.NaN
    private var cachedForLon = Double.NaN
    private var cachedTerrainElevM: Double? = null

    /** New flight session — drop the latched takeoff reference and the terrain cache. */
    @Synchronized
    fun reset() {
        takeoffTerrainElevM = null
        takeoffLat = Double.NaN
        takeoffLon = Double.NaN
        cachedForLat = Double.NaN
        cachedForLon = Double.NaN
        cachedTerrainElevM = null
        lastMslSource = null
        AppLog.v(TAG, "reset")
    }

    /** True once a takeoff terrain reference has been captured — i.e. correction is possible
     *  as soon as there's also DTED coverage under the aircraft. */
    val hasTakeoffReference: Boolean get() = takeoffTerrainElevM != null

    /**
     * DTED elevation (m MSL) at the takeoff point, or null before it latches.
     *
     * Exposed so [CameraSlantPoint] can work in a true MSL frame. The bridge's altitude is
     * height above the TAKEOFF POINT; adding this converts it to height above sea level, which
     * is the only reference that can be differenced against DTED terrain elevations at some
     * other location.
     */
    val takeoffTerrainElevMsl: Double? get() = takeoffTerrainElevM

    @Synchronized
    fun reading(context: Context, hud: AutelTakBridge.Hud): Reading {
        if (!hud.hasFix) return Reading(hud.relAlt, terrainCorrected = false, mslMeters = null)

        latchTakeoffReference(context, hud)
        val takeoffElev = takeoffTerrainElevM
        val underAircraft = terrainUnderAircraft(context, hud.lat, hud.lon)

        // Sea level = the takeoff point's own elevation plus how far above it we've climbed.
        // DTED is already MSL-referenced, so no datum conversion enters here. Without a takeoff
        // reference the ground under the aircraft stands in — see [estimateAircraftMsl] for
        // what that assumes and what it recovers.
        val (msl, source) = estimateAircraftMsl(takeoffElev, underAircraft, hud.relAlt)
        if (source != lastMslSource) {
            lastMslSource = source
            AppLog.i(TAG, "aircraft MSL source: $source")
        }

        if (takeoffElev == null || underAircraft == null) {
            return Reading(hud.relAlt, terrainCorrected = false, mslMeters = msl, mslSource = source)
        }
        return Reading(
            meters = hud.relAlt + (takeoffElev - underAircraft),
            terrainCorrected = true,
            mslMeters = msl,
            mslSource = source,
        )
    }

    /** Transition-only log of the MSL source; this runs on every HUD tick. */
    private var lastMslSource: MslSource? = null

    /** Captures the terrain elevation at the takeoff point, once, from the first home location
     *  we see. Retries on later ticks if DTED had no coverage yet (nothing is latched on a
     *  failed lookup), so importing terrain mid-session still starts working. */
    private fun latchTakeoffReference(context: Context, hud: AutelTakBridge.Hud) {
        if (takeoffTerrainElevM != null) return
        // ⚠ THE AIRCRAFT ON THE GROUND IS AT THE TAKEOFF POINT, home set or not.
        // The home point arrives when the aircraft arms; on the ground without arming it never
        // does, and AR would run on the flat plane even with DTED covering the site. Below
        // [ON_GROUND_M] of height the aircraft's own fix is the takeoff point. Not after a
        // restart mid-flight — that is what the home path is for.
        val onGround = hud.hasFix && kotlin.math.abs(hud.relAlt) < ON_GROUND_M
        val (refLat, refLon, why) = when {
            takeoffLat.isFinite() && takeoffLon.isFinite() ->
                Triple(takeoffLat, takeoffLon, "motor start")
            hud.homeSet && hud.homeLat.isFinite() && hud.homeLon.isFinite() ->
                Triple(hud.homeLat, hud.homeLon, "home point")
            onGround -> Triple(hud.lat, hud.lon, "aircraft on the ground")
            else -> return
        }
        val elev = DtedIndex.elevationAt(context, refLat, refLon) ?: return
        takeoffTerrainElevM = elev
        AppLog.i(TAG, "takeoff terrain reference latched: %.1f m MSL at %.5f, %.5f (%s)"
            .format(elev, refLat, refLon, why))
    }

    /** Below this height above takeoff the aircraft is taken to be sitting on it. */
    private const val ON_GROUND_M = 2.0

    private fun terrainUnderAircraft(context: Context, lat: Double, lon: Double): Double? {
        if (cachedForLat.isFinite() && cachedForLon.isFinite()) {
            val moved = CameraSlantPoint.distanceMeters(cachedForLat, cachedForLon, lat, lon)
            if (moved < RESAMPLE_DISTANCE_M) return cachedTerrainElevM
        }
        val elev = DtedIndex.elevationAt(context, lat, lon)
        cachedForLat = lat
        cachedForLon = lon
        cachedTerrainElevM = elev
        return elev
    }
}
