package com.autel.sdksample.tak

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.taklite.util.AppLog
import org.json.JSONObject
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Downloads FAA UAS Facility Map (UASFM) altitude ceilings for a pilot-chosen area and persists
 * them via [UasfmDatabase]. Lookup lives in [UasfmIndex].
 *
 * **Source.** The FAA's live ArcGIS feature service, queried as Esri JSON with
 * `returnGeometry=false` — we only need each cell's CEILING plus its centre LATITUDE/LONGITUDE,
 * because the cells sit on a fixed 1/120° grid (see [UasfmIndex]). Dropping geometry is what
 * takes a statewide pull from tens of MB of polygon rings down to a couple of MB of attributes.
 *
 * **This is advisory data.** UASFM shows the altitudes at which the FAA is *likely* to approve a
 * Part 107 authorization without extra analysis. It is not an authorization, it is not updated in
 * real time, and a downloaded copy goes stale as the FAA revises the maps — which is why
 * [UasfmMetaEntity] keeps both the download date and the FAA's own MAP_EFF effective dates, and
 * the UI shows them. Nothing here is wired to the aircraft's flight limits; it is display-only by
 * design.
 */
object UasfmStore {
    private const val TAG = "UasfmStore"

    /**
     * FAA UAS Data Delivery System feature service — the LIVE published layer.
     *
     * **Do not "upgrade" this to one of the suffixed siblings.** That server hosts several
     * UASFM layers and the names are actively misleading:
     *
     * | Layer | Ceiling at one test cell | MAP_EFF |
     * |---|---|---|
     * | `FAA_UAS_FacilityMap_Data` (this one) | 200 | current |
     * | `FAA_UAS_FacilityMap_Data_Primary` | 0 | 1/26/2023 |
     * | `FAA_UAS_FacilityMap_Data_V5` | 0 | 10/6/2022 |
     *
     * `_V5` reads like "version 5, therefore newest" — it is not; it sits alongside `_V5_Dev`
     * and `_V5_AppTest` and is a stale snapshot. This app shipped against `_V5` and reported
     * a **0 ft ceiling in a real 200 ft grid**, from data nearly four years out of
     * date. Caught by a pilot standing in the cell, cross-checked against the
     * FAA's own "Visualize it" viewer, which agrees with this layer.
     *
     * **`MAP_EFF` is the tell.** If a spot check ever returns an effective date that isn't
     * roughly current, the layer is wrong — that field was visible in the original research and
     * showing 2022, and not questioning it is what let this through.
     */
    private const val SERVICE_URL =
        "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services/" +
            "FAA_UAS_FacilityMap_Data/FeatureServer/0/query"

    /** This layer's `maxRecordCount` is 2000 (the stale `_V5` one was 1000). */
    private const val PAGE_SIZE = 2000

    /** Refuse absurd areas outright — the nationwide set is ~370k cells, and pulling that to a
     *  phone over a field hotspot is a mistake we should catch before it starts, not halfway
     *  through 370 HTTP round-trips. */
    private const val MAX_CELLS = 150_000

    /** Safety net against a paging bug turning into an unbounded request loop. */
    private const val MAX_PAGES = MAX_CELLS / PAGE_SIZE + 5

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 20_000

    private val main = Handler(Looper.getMainLooper())

    data class Bbox(val west: Double, val south: Double, val east: Double, val north: Double)

    data class CountResult(val count: Int?, val error: String?)
    data class DownloadResult(
        val meta: UasfmMetaEntity?,
        val error: String?,
        /** Features whose centre didn't land on the 1/120° grid — see [UasfmIndex]. Non-zero
         *  means the FAA changed the grid and this app's core assumption needs revisiting. */
        val offGridSkipped: Int = 0,
    )

    /** Square bbox of [radiusMi] around a point. Longitude degrees shrink with latitude, which
     *  matters a lot at high latitudes — at 60°N a degree of longitude is half the
     *  ground distance it is at the equator, so without the cos() term a "50 mile" box would
     *  come out half as wide as asked. */
    fun bboxAround(lat: Double, lon: Double, radiusMi: Double): Bbox {
        val milesPerDegLat = 69.0
        val dLat = radiusMi / milesPerDegLat
        val cosLat = max(0.01, cos(Math.toRadians(lat)))
        val dLon = radiusMi / (milesPerDegLat * cosLat)
        return Bbox(
            west = max(-180.0, lon - dLon), south = max(-90.0, lat - dLat),
            east = min(180.0, lon + dLon), north = min(90.0, lat + dLat),
        )
    }

    fun meta(context: Context): UasfmMetaEntity? = try {
        UasfmDatabase.get(context).uasfmDao().meta()
    } catch (t: Throwable) {
        AppLog.w(TAG, "meta read failed: ${t.message}"); null
    }

    fun clear(context: Context) {
        try {
            UasfmDatabase.get(context).uasfmDao().clearAll()
            UasfmIndex.invalidate()
            AppLog.i(TAG, "cleared downloaded UASFM data")
        } catch (t: Throwable) {
            AppLog.w(TAG, "clear failed: ${t.message}")
        }
    }

    // ---- Network ----

    /** How many cells the given area would pull. Cheap (`returnCountOnly`) — lets the pilot see
     *  the size before committing to the download. Result delivered on the main thread. */
    fun countAsync(bbox: Bbox, onResult: (CountResult) -> Unit) {
        Thread {
            val r = try {
                val json = httpGetJson(buildUrl(bbox, countOnly = true, offset = 0))
                json.optJSONObject("error")?.let {
                    CountResult(null, "FAA service error: ${it.optString("message", "unknown")}")
                } ?: CountResult(json.optInt("count", 0), null)
            } catch (t: Throwable) {
                AppLog.w(TAG, "count failed: ${t.message}")
                CountResult(null, t.message ?: "Network error")
            }
            main.post { onResult(r) }
        }.start()
    }

    /**
     * Pulls every cell in [bbox], paging through the service, and replaces the stored dataset.
     * Callbacks are delivered on the main thread; [onProgress] fires per page with the running
     * cell count.
     */
    fun downloadAsync(
        context: Context,
        bbox: Bbox,
        areaLabel: String,
        onProgress: (Int) -> Unit,
        onDone: (DownloadResult) -> Unit,
    ) {
        Thread {
            val r = try {
                doDownload(context, bbox, areaLabel, onProgress)
            } catch (t: Throwable) {
                AppLog.w(TAG, "download failed: ${t.message}")
                DownloadResult(null, t.message ?: "Network error")
            }
            main.post { onDone(r) }
        }.start()
    }

    private fun doDownload(
        context: Context,
        bbox: Bbox,
        areaLabel: String,
        onProgress: (Int) -> Unit,
    ): DownloadResult {
        // Collected into a map keyed by grid cell so a duplicate (row, col) can't produce two
        // rows. On the rare collision we keep the LOWER ceiling: if the data is ambiguous about
        // how high you may fly somewhere, the restrictive reading is the safe one to show.
        val byCell = HashMap<Long, Int>()
        var offGrid = 0
        var effMin: Int? = null
        var effMax: Int? = null
        var offset = 0
        var pages = 0
        // The loop condition used to be `pages < MAX_PAGES`, so running out of pages looked
        // exactly like reaching the end of the data — and the partial result was then written
        // with replaceAll() and a meta row saying it was a complete area. See the refusal below
        // for why that is the one outcome this must never produce.
        var hitPageLimit = false

        while (true) {
            if (pages >= MAX_PAGES) {
                hitPageLimit = true
                break
            }
            val json = httpGetJson(buildUrl(bbox, countOnly = false, offset = offset))
            json.optJSONObject("error")?.let {
                return DownloadResult(null, "FAA service error: ${it.optString("message", "unknown")}")
            }
            val features = json.optJSONArray("features") ?: break
            if (features.length() == 0) break

            for (i in 0 until features.length()) {
                val attrs = features.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                val ceiling = if (attrs.isNull("CEILING")) continue else attrs.optInt("CEILING", -1)
                if (ceiling < 0) continue
                val lat = attrs.optDouble("LATITUDE", Double.NaN)
                val lon = attrs.optDouble("LONGITUDE", Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) continue

                val row = UasfmIndex.gridRowFor(lat)
                val col = UasfmIndex.gridColFor(lon)
                // A real cell centre sits half a cell off the boundary in both axes. Anything
                // that isn't near a centre means the published geometry left the 1/120° grid
                // this whole design rests on, so skip it rather than bin it somewhere wrong.
                if (!isNearCellCentre(lat, row) || !isNearCellCentre(lon, col)) {
                    offGrid++
                    continue
                }
                val k = UasfmIndex.key(row, col)
                val prev = byCell[k]
                byCell[k] = if (prev == null) ceiling else min(prev, ceiling)

                parseMapEff(attrs.optString("MAP_EFF", ""))?.let { d ->
                    effMin = effMin?.let { min(it, d) } ?: d
                    effMax = effMax?.let { max(it, d) } ?: d
                }
            }

            offset += features.length()
            pages++
            val soFar = byCell.size
            main.post { onProgress(soFar) }

            if (byCell.size > MAX_CELLS) {
                return DownloadResult(null,
                    "That area is too large (over $MAX_CELLS cells). Try a smaller radius.")
            }
            // exceededTransferLimit tells us the service truncated this page; when it's absent
            // or false and the page came back short, we've reached the end.
            val more = json.optBoolean("exceededTransferLimit", false) ||
                features.length() == PAGE_SIZE
            if (!more) break
        }

        // REFUSED, not stored with a warning — unlike the off-grid count below, which is
        // reported and kept. The difference is which way each one fails. A missing UASFM cell
        // does not read as "unknown" to the pilot: the area simply has no ceiling, which reads
        // as unrestricted, i.e. Part 107's 400 ft. So a truncated pull produces an advisory that
        // is CONFIDENTLY WRONG exactly where the data ran out, and the error is in the
        // permissive direction. No data at all is honest — the screen shows no coverage and the
        // pilot knows to check elsewhere. Partial data pretending to be whole is not.
        if (hitPageLimit) {
            AppLog.e(TAG, "UASFM paging hit MAX_PAGES ($MAX_PAGES) with ${byCell.size} cell(s) — " +
                "the area was NOT fully retrieved, refusing to store a partial advisory")
            return DownloadResult(null,
                "Could not download the whole area (stopped after $MAX_PAGES pages). " +
                    "Nothing was saved. Try a smaller radius.")
        }
        if (offGrid > 0) {
            AppLog.w(TAG, "$offGrid feature(s) were off the 1/120 degree grid and were SKIPPED " +
                "— if this is non-zero the FAA grid assumption in UasfmIndex needs revisiting")
        }
        if (byCell.isEmpty()) {
            return DownloadResult(null,
                "No FAA facility-map cells in that area. Outside controlled airspace the Part 107 " +
                    "400 ft limit applies and the FAA publishes no cells.", offGrid)
        }

        val cells = byCell.map { (k, ceiling) ->
            UasfmCellEntity(
                gridRow = (k shr 32).toInt(),
                gridCol = (k and 0xFFFFFFFFL).toInt(),
                ceilingFt = ceiling,
            )
        }
        val meta = UasfmMetaEntity(
            areaLabel = areaLabel,
            downloadedAtMs = System.currentTimeMillis(),
            cellCount = cells.size,
            effectiveLabel = formatEffective(effMin, effMax),
            south = bbox.south, west = bbox.west, north = bbox.north, east = bbox.east,
        )
        UasfmDatabase.get(context).uasfmDao().replaceAll(cells, meta)
        UasfmIndex.invalidate()
        AppLog.i(TAG, "downloaded ${cells.size} UASFM cell(s) for '$areaLabel' " +
            "(eff ${meta.effectiveLabel}, $offGrid off-grid skipped)")
        return DownloadResult(meta, null, offGrid)
    }

    /** True if [value] degrees sits near the centre of grid index [index] (within 10% of a
     *  cell), i.e. the feature really is a cell centre on the expected grid. */
    private fun isNearCellCentre(value: Double, index: Int): Boolean {
        val offsetInCell = value * UasfmIndex.CELLS_PER_DEGREE - index
        return abs(offsetInCell - 0.5) <= 0.1
    }

    private fun buildUrl(bbox: Bbox, countOnly: Boolean, offset: Int): String {
        val geometry = "${bbox.west},${bbox.south},${bbox.east},${bbox.north}"
        val sb = StringBuilder(SERVICE_URL)
        sb.append("?where=").append(enc("1=1"))
        sb.append("&geometry=").append(enc(geometry))
        sb.append("&geometryType=esriGeometryEnvelope")
        sb.append("&inSR=4326")
        sb.append("&spatialRel=esriSpatialRelIntersects")
        sb.append("&f=json")
        if (countOnly) {
            sb.append("&returnCountOnly=true")
        } else {
            sb.append("&outFields=").append(enc("CEILING,LATITUDE,LONGITUDE,MAP_EFF"))
            sb.append("&returnGeometry=false")
            // Stable ordering is required for offset paging to be coherent — without it the
            // service may return overlapping/missing records across pages.
            sb.append("&orderByFields=OBJECTID")
            sb.append("&resultOffset=").append(offset)
            sb.append("&resultRecordCount=").append(PAGE_SIZE)
        }
        return sb.toString()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun httpGetJson(urlStr: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("HTTP $code from FAA service${
                    if (body.isNotBlank()) ": ${body.take(200)}" else ""}")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    // ---- MAP_EFF handling ----

    /** FAA publishes MAP_EFF as "M/d/yyyy". Returned as a sortable yyyyMMdd int, or null. */
    private fun parseMapEff(raw: String): Int? {
        if (raw.isBlank()) return null
        val parts = raw.trim().split("/")
        if (parts.size != 3) return null
        val month = parts[0].toIntOrNull() ?: return null
        val day = parts[1].toIntOrNull() ?: return null
        val year = parts[2].toIntOrNull() ?: return null
        if (year < 1900 || month !in 1..12 || day !in 1..31) return null
        return year * 10000 + month * 100 + day
    }

    private fun formatEffective(minD: Int?, maxD: Int?): String {
        if (minD == null || maxD == null) return "unknown"
        val lo = prettyDate(minD)
        return if (minD == maxD) lo else "$lo – ${prettyDate(maxD)}"
    }

    private fun prettyDate(d: Int): String =
        "%d/%d/%04d".format((d / 100) % 100, d % 100, d / 10000)
}
