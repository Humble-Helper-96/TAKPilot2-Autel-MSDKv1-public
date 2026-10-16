package com.autel.sdksample.tak

import android.content.Context
import com.taklite.util.AppLog
import org.osmdroid.config.Configuration
import java.io.File

/**
 * Storage settings for the map tiles that osmdroid keeps.
 *
 * **The application caches each tile that the flight map draws.** This costs nothing more, because
 * the tile was fetched to be shown. Therefore a route flown one time with a connection is on the
 * controller for the next flight. It covers only the ground that the map has shown.
 *
 * ⚠ **There is no download of an area before a flight, and there is no control to select a tile
 * source.** The Map Display section did both. It was removed (design decision).
 * **Therefore a first flight into new ground with no connection has no map.** Fly the area one
 * time with a connection, or keep a connection.
 *
 * osmdroid controls the size of the store itself: it removes the oldest tiles when the store
 * passes [MAX_BYTES]. No control in the application deletes the cache. The store cannot grow
 * without limit, but a person cannot reclaim the space early either.
 */
object MapTileCache {
    private const val TAG = "MapTileCache"

    /** Hard ceiling on the tile store. */
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024

    /**
     * What osmdroid trims down to when [MAX_BYTES] is passed, oldest first.
     *
     * Deliberately below the ceiling rather than equal to it. If trim == max, the store sits
     * exactly at the limit and re-trims on almost every new tile, which turns a background
     * cleanup into a constant one. The 10% gap means a trim reclaims real space and then leaves
     * the cache alone for a while.
     */
    private const val TRIM_BYTES = (MAX_BYTES * 0.9).toLong()

    /**
     * How long a cached tile stays usable, whatever the server's headers said.
     *
     * Without this the cache does not survive the thing it exists for. OSM tile servers send short
     * expiry times, osmdroid obeys them, and its cleanup deletes expired tiles — so a cache filled
     * at home would be stale by the time it mattered, and a map with no signal would show empty
     * squares while it held the tiles that would have filled them.
     *
     * The cost is that a change to a road takes up to a year to appear. For a map whose work is to
     * orient a pilot, out of date is very much better than absent.
     */
    private val EXPIRY_OVERRIDE_MS = 365L * 24 * 60 * 60 * 1000

    @Volatile private var configured = false

    /**
     * Sets up the storage of osmdroid. It must run before a MapView inflates. Idempotent.
     *
     * The base path is private to the application ([Context.getFilesDir]), not shared external
     * storage: on the Android 11 of the Smart Controller, the default external path of osmdroid
     * needs a storage permission that this application has no other reason to request, and a cache
     * that fails to write with no message is worse than one in a less usual place.
     */
    @Synchronized
    fun configure(context: Context) {
        if (configured) return
        val base = File(context.filesDir, "osmdroid").apply { mkdirs() }
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
            tileFileSystemCacheMaxBytes = MAX_BYTES
            tileFileSystemCacheTrimBytes = TRIM_BYTES
            expirationOverrideDuration = EXPIRY_OVERRIDE_MS
        }
        configured = true
        AppLog.i(TAG, "osmdroid cache configured: max=${human(MAX_BYTES)} " +
            "trim=${human(TRIM_BYTES)} path=${base.absolutePath}")
    }

    /** Bytes as a short string, for the log. */
    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes.toDouble() / (1L shl 20))
        else -> "%.0f KB".format(bytes.toDouble() / 1024)
    }
}
