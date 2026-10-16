package com.autel.sdksample.tak

import android.content.Context
import com.taklite.util.AppLog

/**
 * In-memory index of parsed DTED tile headers (not full elevation grids — see [DtedTile]'s
 * direct-seek lookup) for whatever's currently in [DtedStore]. Rebuilt lazily on first use;
 * [DtedStore.import] and [DtedStore.delete] call [invalidate] so the next lookup picks up
 * the change.
 */
object DtedIndex {
    private const val TAG = "DtedIndex"
    @Volatile private var tiles: List<DtedTile>? = null

    /**
     * Drops the index AND releases each tile's kept file handle and post cache — see
     * [DtedTile.close]. The handle is what makes a redraw cheap, so it must be let go when the
     * pilot imports or deletes terrain: a stale handle on a deleted file would answer every
     * lookup from a cache of a file that is no longer there.
     */
    fun invalidate() {
        dropTiles()
        // The takeoff reference and the under-aircraft sample came from the OLD terrain.
        // Called OUTSIDE this object's lock: TerrainAgl holds its own lock while it calls
        // elevationAt here, and taking the two in opposite orders could deadlock.
        TerrainAgl.onTerrainChanged()
    }

    @Synchronized
    private fun dropTiles() {
        tiles?.forEach { runCatching { it.close() } }
        tiles = null
    }

    @Synchronized
    private fun ensureLoaded(context: Context): List<DtedTile> {
        tiles?.let { return it }
        val files = DtedStore.listFiles(context)
        // FINEST TILE FIRST. elevationAt() returns the first tile that covers the point, and
        // DtedStore.listFiles() hands them back sorted by FILENAME — so for a cell imported at
        // several levels ("w101_n45.dt0", "w101_n45.dt2") the .dt0 sorted first and won every
        // lookup. The DTED2 the pilot imported was parsed, indexed, and never read.
        //
        // That is not a rounding detail. DTED0 is ~900m posts against DTED2's ~30m, and marker
        // placement divides terrain error by tan(look angle): at 21° down, 1m of terrain error
        // becomes 2.6m of horizontal miss (at 54° only 0.7m). Measured in the field — drops at
        // 54° landed within a metre while drops at 21° were ~10ft out, which is exactly this.
        val loaded = finestFirst(files.mapNotNull { DtedTile.open(it) })
        AppLog.i(TAG, "loaded ${loaded.size}/${files.size} DTED tile(s); " +
            "finest post spacing ${loaded.firstOrNull()?.postSpacingDeg ?: 0.0}°")
        tiles = loaded
        return loaded
    }

    /** Elevation (meters, DTED's native vertical datum) at (lat, lon), or null if no
     *  uploaded tile covers the point. */
    fun elevationAt(context: Context, lat: Double, lon: Double): Double? {
        for (t in ensureLoaded(context)) {
            val e = t.elevationAt(lat, lon)
            if (e != null) return e
        }
        return null
    }

    fun hasCoverage(context: Context): Boolean = ensureLoaded(context).isNotEmpty()

    /** The lookup order: finest post spacing first (see ensureLoaded). Pure, so
     *  DtedImportPolicyTest can hold it. */
    internal fun finestFirst(tiles: List<DtedTile>): List<DtedTile> = tiles.sortedBy { it.postSpacingDeg }
}
