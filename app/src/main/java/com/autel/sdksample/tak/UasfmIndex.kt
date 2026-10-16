package com.autel.sdksample.tak

import android.content.Context
import com.taklite.util.AppLog
import kotlin.math.floor

/**
 * In-memory lookup of FAA UASFM altitude ceilings for whatever [UasfmStore] has downloaded.
 * Rebuilt lazily on first use; [UasfmStore] calls [invalidate] after a download or clear.
 *
 * **The grid, and why there's no polygon math here.** Every UASFM cell in the FAA's published
 * data is an axis-aligned rectangle on a fixed 30 arc-second (1/120°) grid — verified against
 * real published cells around an airport, whose corners land exactly on 1/120° boundaries
 * (e.g. 45.1333345… → 45.1416679…, exactly one grid step). So a point's cell is pure integer
 * arithmetic: `floor(lat * 120)`, `floor(lon * 120)`. No geometry is stored and no
 * point-in-polygon test is needed, which is what keeps a statewide dataset small enough to sit
 * in a HashMap and be queried on the flight screen's tick.
 *
 * Row/col are derived from each feature's LATITUDE/LONGITUDE fields — the cell *center* — rather
 * than its polygon corners: the published corners carry ~1e-6° of floating-point noise, so a
 * corner can land a hair on the wrong side of a boundary, while a center is a half-cell away
 * from the nearest one and can't.
 *
 * If the FAA ever ships cells off this grid, [UasfmStore]'s download logs a warning per
 * off-grid feature rather than silently mis-binning them.
 */
object UasfmIndex {
    private const val TAG = "UasfmIndex"

    /** Grid cells per degree — 1/120° == 30 arc-seconds. */
    const val CELLS_PER_DEGREE = 120.0

    /**
     * The 14 CFR 107.51(b) ceiling that applies in uncontrolled (Class G) airspace, where the
     * FAA publishes no UASFM cell. Callers must present this differently from a real cell value
     * — "no facility map here, the Part 107 default applies" is a materially different statement
     * to a pilot than "the facility map says 400 ft", even though the number matches.
     */
    const val PART_107_DEFAULT_CEILING_FT = 400

    @Volatile private var cells: HashMap<Long, Int>? = null

    @Synchronized
    fun invalidate() {
        cells = null
    }

    /**
     * Warms the index off the main thread. Call once at app start: a statewide dataset is tens
     * of thousands of rows, and [ensureLoaded] is otherwise first triggered from the flight
     * screen's HUD tick — i.e. on the main thread, stalling the video screen for the length of
     * the read. Safe to call when no data is downloaded (loads an empty map).
     */
    fun preload(context: Context) {
        val app = context.applicationContext
        Thread({ ensureLoaded(app) }, "uasfm-preload").start()
    }

    fun gridRowFor(lat: Double): Int = floor(lat * CELLS_PER_DEGREE).toInt()
    fun gridColFor(lon: Double): Int = floor(lon * CELLS_PER_DEGREE).toInt()

    /** Packs a (row, col) pair into one Long so the map avoids a per-cell key object. */
    fun key(gridRow: Int, gridCol: Int): Long =
        (gridRow.toLong() shl 32) or (gridCol.toLong() and 0xFFFFFFFFL)

    @Synchronized
    private fun ensureLoaded(context: Context): HashMap<Long, Int> {
        cells?.let { return it }
        val loaded = HashMap<Long, Int>()
        try {
            for (c in UasfmDatabase.get(context).uasfmDao().allCells()) {
                loaded[key(c.gridRow, c.gridCol)] = c.ceilingFt
            }
            AppLog.i(TAG, "loaded ${loaded.size} UASFM cell(s)")
        } catch (t: Throwable) {
            AppLog.w(TAG, "load failed: ${t.message}")
        }
        cells = loaded
        return loaded
    }

    /**
     * UASFM ceiling in feet AGL at (lat, lon), or null if no downloaded cell covers the point.
     *
     * Null does NOT mean "unlimited" — it means this app has nothing to say about that spot,
     * either because the FAA publishes no cell there (uncontrolled airspace, where
     * [PART_107_DEFAULT_CEILING_FT] is the rule) or because the pilot hasn't downloaded coverage
     * for it. Those two cases are indistinguishable here on purpose; [hasCoverage] plus the
     * downloaded bounds is how the UI tells them apart.
     */
    fun ceilingFtAt(context: Context, lat: Double, lon: Double): Int? {
        if (!lat.isFinite() || !lon.isFinite()) return null
        val map = ensureLoaded(context)
        if (map.isEmpty()) return null
        return map[key(gridRowFor(lat), gridColFor(lon))]
    }

    fun hasCoverage(context: Context): Boolean = ensureLoaded(context).isNotEmpty()

    /** True if (lat, lon) falls inside the bounds the pilot actually downloaded — lets the UI
     *  distinguish "outside a facility map" from "outside what you downloaded". */
    fun isWithinDownloadedArea(context: Context, lat: Double, lon: Double): Boolean {
        val m = UasfmStore.meta(context) ?: return false
        return lat >= m.south && lat <= m.north && lon >= m.west && lon <= m.east
    }
}
