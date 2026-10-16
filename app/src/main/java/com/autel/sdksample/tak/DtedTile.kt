package com.autel.sdksample.tak

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.floor

/**
 * Parsed DTED tile (MIL-PRF-89020B: UHL + DSI + ACC + per-longitude data records), with
 * on-demand point-elevation lookup via direct file seeks rather than loading the whole
 * file (~13MB+ for the DTED2-ish tiles this app has been tested with) into memory.
 *
 * The header's own longitude-interval field already bakes in the latitude-dependent post
 * spacing DTED uses above 50°/70°/etc. (fewer longitude posts at high latitude, to keep
 * physical spacing roughly constant) — nothing special-cased here, [nLon]/[lonIntervalDeg]
 * are just read from the file as given.
 */
class DtedTile private constructor(
    private val file: File,
    private val originLonDeg: Double,
    private val originLatDeg: Double,
    private val lonIntervalDeg: Double,
    private val latIntervalDeg: Double,
    private val nLon: Int,
    private val nLat: Int,
    private val dataStartOffset: Long,
) {
    /**
     * Post spacing in degrees of latitude — i.e. this tile's RESOLUTION, straight from its own
     * header. Smaller is finer: DTED0 is 30 arc-seconds (~0.00833°, roughly 900m posts), DTED2
     * is 1 arc-second (~0.000278°, roughly 30m).
     *
     * Exposed so [DtedIndex] can prefer the finest tile covering a point. It matters more than
     * it looks: pilots import archives holding several levels for the same cell, and reading
     * the coarse one silently costs marker accuracy at shallow look angles — see the ordering
     * note in DtedIndex.
     */
    val postSpacingDeg: Double get() = latIntervalDeg

    private val recordLength = 12L + 2L * nLat

    private val minLon = originLonDeg
    private val maxLon = originLonDeg + (nLon - 1) * lonIntervalDeg
    private val minLat = originLatDeg
    private val maxLat = originLatDeg + (nLat - 1) * latIntervalDeg

    fun contains(lat: Double, lon: Double): Boolean =
        lon in minLon..maxLon && lat in minLat..maxLat

    /**
     * The open file, kept between lookups, and the POST CACHE.
     *
     * ⚠ **DTED FILE SEEKS PER CONTACT PER FRAME ON THE UI THREAD.** [ArOverlayView] redraws at 100 ms and asks for the terrain
     * under EVERY contact and every elevation-less pin on each pass. Each ask used to open this
     * file, seek four times, read eight single bytes and close it again: with ten contacts in
     * range that is a hundred file opens, four hundred seeks and eight hundred byte-reads a
     * second, on the thread that is compositing the overlay over live video. That stall is also
     * an ACCURACY cost, not only a smoothness one — it widens the telemetry-to-video lag of the
     * AR overlay.
     *
     * The fix caches the POSTS, not the answer. A post is a fixed grid sample, so a cache hit
     * returns the identical byte pair the seek would have: bilinear interpolation still runs on
     * every call with the caller's exact latitude and longitude, and no answer moves by so much
     * as a millimetre. Quantising the QUERY instead would have been easy and wrong — measured,
     * 1 m of terrain error becoming 2.6 m of horizontal miss at a 21° look angle, and
     * snapping the query point to a grid is exactly that error.
     *
     * A contact walking across a DTED2 cell (~30 m posts) reuses one set of four posts for the
     * whole crossing, so the steady state is zero I/O. The handle stays open because reopening
     * it was most of the cost; [close] releases it, called from [DtedIndex.invalidate] when the
     * pilot imports or deletes terrain.
     *
     * Guarded by `this`: the overlay's draw thread and [TerrainAgl]'s telemetry tick both land
     * here, and a shared [RandomAccessFile] has one file pointer between them. Contention is a
     * few microseconds against the file open this replaces.
     */
    private var raf: RandomAccessFile? = null
    private var rafFailed = false

    /** Raw post values by grid index, LRU-bounded. A void or unreadable post caches as
     *  [VOID_VALUE] too — both already meant "no elevation here", and not caching the misses
     *  would leave a tile's void region doing the full seek on every frame forever. */
    private val postCache = object : LinkedHashMap<Long, Int>(POST_CACHE_MAX * 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Int>?): Boolean =
            size > POST_CACHE_MAX
    }

    /** Bilinear-interpolated elevation (meters, DTED's native vertical datum) at (lat, lon),
     *  or null if outside this tile or a surrounding post is void/unreadable. */
    fun elevationAt(lat: Double, lon: Double): Double? {
        if (!contains(lat, lon)) return null
        val fc = (lon - originLonDeg) / lonIntervalDeg
        val fr = (lat - originLatDeg) / latIntervalDeg
        val c0 = floor(fc).toInt().coerceIn(0, nLon - 1)
        val r0 = floor(fr).toInt().coerceIn(0, nLat - 1)
        val c1 = (c0 + 1).coerceAtMost(nLon - 1)
        val r1 = (r0 + 1).coerceAtMost(nLat - 1)
        val tc = (fc - c0).coerceIn(0.0, 1.0)
        val tr = (fr - r0).coerceIn(0.0, 1.0)

        synchronized(this) {
            val e00 = postAt(c0, r0) ?: return null
            val e10 = postAt(c1, r0) ?: return null
            val e01 = postAt(c0, r1) ?: return null
            val e11 = postAt(c1, r1) ?: return null
            val eLow = e00 + (e10 - e00) * tc
            val eHigh = e01 + (e11 - e01) * tc
            return eLow + (eHigh - eLow) * tr
        }
    }

    /** Releases the kept file handle. Idempotent; the next lookup reopens. */
    fun close() {
        synchronized(this) {
            runCatching { raf?.close() }
            raf = null
            rafFailed = false
            postCache.clear()
        }
    }

    /** Call under `synchronized(this)`. */
    private fun postAt(col: Int, row: Int): Double? {
        val key = col.toLong() * nLat + row
        postCache[key]?.let { return if (it == VOID_VALUE) null else it.toDouble() }
        val value = readPost(col, row)
        postCache[key] = value
        return if (value == VOID_VALUE) null else value.toDouble()
    }

    /** Call under `synchronized(this)`. Returns [VOID_VALUE] for a void, an unreadable post or
     *  an unopenable file — every one of which already meant "no elevation here". */
    private fun readPost(col: Int, row: Int): Int {
        val f = openRaf() ?: return VOID_VALUE
        return try {
            val offset = dataStartOffset + col * recordLength + 8 + 2 * row
            f.seek(offset)
            val b0 = f.read()
            val b1 = f.read()
            if (b0 < 0 || b1 < 0) return VOID_VALUE
            val magnitude = ((b0 and 0x7F) shl 8) or b1
            if (b0 and 0x80 != 0) -magnitude else magnitude
        } catch (t: Throwable) {
            // The handle may be the broken part — a deleted or unmounted file reads as an
            // exception on every seek. Drop it so the next lookup tries a fresh open once.
            runCatching { f.close() }
            raf = null
            VOID_VALUE
        }
    }

    /** Call under `synchronized(this)`. One failed open is remembered: a missing tile must not
     *  cost an open() attempt per post per frame. */
    private fun openRaf(): RandomAccessFile? {
        raf?.let { return it }
        if (rafFailed) return null
        return try {
            RandomAccessFile(file, "r").also { raf = it }
        } catch (t: Throwable) {
            rafFailed = true
            null
        }
    }

    companion object {
        private const val UHL_LEN = 80L
        private const val DSI_LEN = 648L
        private const val ACC_LEN = 2700L
        private const val DATA_START = UHL_LEN + DSI_LEN + ACC_LEN
        private const val VOID_VALUE = -32767

        /** Posts held per tile. Four per lookup, and a flight works a bounded patch of ground,
         *  so this covers everything in range several times over at a few tens of kilobytes. */
        private const val POST_CACHE_MAX = 4096

        /** The exact byte length of a tile with this grid: headers plus one record per column. */
        fun lengthFor(nLon: Int, nLat: Int): Long = DATA_START + nLon.toLong() * (12L + 2L * nLat)

        /** The length the file's own UHL header says it must have, or null if it has no
         *  readable DTED header. For the importer's check of a freshly written tile. */
        fun expectedLength(file: File): Long? = try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(80)
                raf.readFully(header)
                val text = String(header, Charsets.US_ASCII)
                if (!text.startsWith("UHL1")) null else {
                    val nLon = text.substring(47, 51).trim().toIntOrNull()
                    val nLat = text.substring(51, 55).trim().toIntOrNull()
                    if (nLon == null || nLat == null || nLon <= 0 || nLat <= 0) null
                    else lengthFor(nLon, nLat)
                }
            }
        } catch (t: Throwable) { null }

        /** Parses just the 80-byte UHL header. Returns null if the file isn't a recognizable
         *  DTED tile (wrong magic, unparseable fields, etc.) — never throws. */
        fun open(file: File): DtedTile? {
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val header = ByteArray(80)
                    raf.readFully(header)
                    val text = String(header, Charsets.US_ASCII)
                    if (!text.startsWith("UHL1")) return null

                    val lonStr = text.substring(4, 12).trim()
                    val latStr = text.substring(12, 20).trim()
                    val lonIntervalRaw = text.substring(20, 24).trim().toIntOrNull() ?: return null
                    val latIntervalRaw = text.substring(24, 28).trim().toIntOrNull() ?: return null
                    val nLon = text.substring(47, 51).trim().toIntOrNull() ?: return null
                    val nLat = text.substring(51, 55).trim().toIntOrNull() ?: return null
                    if (nLon <= 0 || nLat <= 0) return null
                    // ⚠ The WHOLE tile or nothing. A tile cut short by an
                    // interrupted import used to load, and lookups in its lost part returned
                    // null or a wrong height. A DTED file's length is fixed by its header.
                    if (file.length() != lengthFor(nLon, nLat)) return null

                    val originLon = parseDmsH(lonStr) ?: return null
                    val originLat = parseDmsH(latStr) ?: return null
                    val lonIntervalDeg = (lonIntervalRaw / 10.0) / 3600.0
                    val latIntervalDeg = (latIntervalRaw / 10.0) / 3600.0
                    if (lonIntervalDeg <= 0 || latIntervalDeg <= 0) return null

                    DtedTile(file, originLon, originLat, lonIntervalDeg, latIntervalDeg, nLon, nLat, DATA_START)
                }
            } catch (t: Throwable) {
                null
            }
        }

        /** Parses a DDDMMSSH-style origin field (degrees/minutes/seconds + hemisphere letter).
         *  Tolerant of 6-8 digit encodings (some tools zero-pad the degree field differently)
         *  by taking the last 2 digits as seconds, the next 2 as minutes, and whatever's left
         *  as degrees. */
        private fun parseDmsH(raw: String): Double? {
            val m = Regex("(\\d+)\\s*([NSEW])").find(raw) ?: return null
            val digits = m.groupValues[1]
            val hemi = m.groupValues[2]
            if (digits.length < 5) return null
            val sec = digits.takeLast(2).toIntOrNull() ?: return null
            val min = digits.dropLast(2).takeLast(2).toIntOrNull() ?: return null
            val deg = digits.dropLast(4).toIntOrNull() ?: return null
            var value = deg + min / 60.0 + sec / 3600.0
            if (hemi == "S" || hemi == "W") value = -value
            return value
        }
    }
}
