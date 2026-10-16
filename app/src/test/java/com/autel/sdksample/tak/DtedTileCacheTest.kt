package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Pins [DtedTile]'s post cache. Without it the AR overlay opened a DTED file, seeked four times
 * and closed it again for every contact on every 100 ms redraw, on the thread compositing over
 * live video.
 *
 * **The claim under test is that the cache changes no answer.** The posts are a fixed grid, so
 * a cached post is the same byte pair the seek would have returned and bilinear interpolation
 * still runs against the caller's exact latitude and longitude. That is worth pinning rather
 * than asserting in a comment, because the easy version of this fix — quantising the QUERY to a
 * grid — would pass a smoke test and silently reintroduce a measurable terrain error (1 m of
 * terrain error becomes 2.6 m of horizontal miss at a 21° look angle).
 *
 * [DtedTile] is SDK-free and takes a [File], so it tests on the JVM with a synthesised tile —
 * no device, no imported terrain. The tile is MIL-PRF-89020B shaped: UHL + DSI + ACC, then one
 * record per longitude of 8 prefix bytes, nLat 2-byte posts and a 4-byte checksum.
 */
class DtedTileCacheTest {

    private val nLon = 4
    private val nLat = 4

    /** Elevation written at each post: distinct per post, so an off-by-one in the cache key
     *  shows up as a wrong number rather than as a plausible one. */
    private fun postValue(col: Int, row: Int) = col * 100 + row

    /**
     * A tile whose origin is 10°N 1°W with 1 arc-second posts. [voidAt] writes DTED's void
     * sentinel at one post, for the miss path.
     */
    private fun writeTile(dir: File, voidAt: Pair<Int, Int>? = null): File {
        val f = File(dir, "w001_n10.dt2")
        val uhl = StringBuilder()
        uhl.append("UHL1")                  // 0..4
        uhl.append("0010000W")              // 4..12   origin longitude, DDDMMSSH
        uhl.append("0100000N")              // 12..20  origin latitude
        uhl.append("0010")                  // 20..24  lon interval, tenths of an arc-second
        uhl.append("0010")                  // 24..28  lat interval
        uhl.append(" ".repeat(47 - 28))     // 28..47  not read by the parser
        uhl.append("%04d".format(nLon))     // 47..51
        uhl.append("%04d".format(nLat))     // 51..55
        uhl.append(" ".repeat(80 - 55))     // 55..80
        val header = uhl.toString().toByteArray(Charsets.US_ASCII)
        assertEquals("UHL must be exactly 80 bytes", 80, header.size)

        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(0)
            raf.write(header)
            raf.write(ByteArray(648))       // DSI
            raf.write(ByteArray(2700))      // ACC
            for (col in 0 until nLon) {
                raf.write(ByteArray(8))     // record prefix
                for (row in 0 until nLat) {
                    val v = if (voidAt == (col to row)) -32767 else postValue(col, row)
                    val magnitude = kotlin.math.abs(v)
                    val b0 = ((magnitude shr 8) and 0x7F) or (if (v < 0) 0x80 else 0)
                    raf.write(b0)
                    raf.write(magnitude and 0xFF)
                }
                raf.write(ByteArray(4))     // checksum
            }
        }
        return f
    }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "dted-${System.nanoTime()}").apply { mkdirs() }

    /** One arc-second, the tile's post spacing. */
    private val step = 1.0 / 3600.0

    @Test
    fun `a post reads back the value written at it`() {
        val dir = tempDir()
        val tile = DtedTile.open(writeTile(dir))
        assertNotNull(tile)
        tile!!
        for (col in 0 until nLon) {
            for (row in 0 until nLat) {
                val lon = -1.0 + col * step
                val lat = 10.0 + row * step
                assertEquals(
                    "post ($col,$row)",
                    postValue(col, row).toDouble(),
                    tile.elevationAt(lat, lon)!!,
                    1e-6,
                )
            }
        }
        tile.close()
    }

    @Test
    fun `interpolation is bilinear between the four surrounding posts`() {
        val dir = tempDir()
        val tile = DtedTile.open(writeTile(dir))!!
        // A quarter of the way east and three quarters north inside cell (1,2).
        val lon = -1.0 + (1 + 0.25) * step
        val lat = 10.0 + (2 + 0.75) * step
        val e00 = postValue(1, 2).toDouble()
        val e10 = postValue(2, 2).toDouble()
        val e01 = postValue(1, 3).toDouble()
        val e11 = postValue(2, 3).toDouble()
        val low = e00 + (e10 - e00) * 0.25
        val high = e01 + (e11 - e01) * 0.25
        assertEquals(low + (high - low) * 0.75, tile.elevationAt(lat, lon)!!, 1e-6)
        tile.close()
    }

    /**
     * THE CACHE GUARANTEE. The first call seeds the cache; every later call for the same cell
     * is served from it. If the fix had quantised the query, these would all collapse onto one
     * value and the test would fail — which is the point of walking sub-post distances.
     */
    @Test
    fun `cached lookups return exactly what an uncached lookup returned`() {
        val dir = tempDir()
        val file = writeTile(dir)

        // Fresh tile per query: nothing is ever cached, so this is the uncached truth.
        val expected = (0..20).map { i ->
            val t = DtedTile.open(file)!!
            val v = t.elevationAt(10.0 + (2 + i / 20.0) * step, -1.0 + (1 + i / 20.0) * step)
            t.close()
            v
        }

        // One tile for every query: the first seeds the cache, the rest hit it.
        val tile = DtedTile.open(file)!!
        val actual = (0..20).map { i ->
            tile.elevationAt(10.0 + (2 + i / 20.0) * step, -1.0 + (1 + i / 20.0) * step)
        }
        tile.close()

        assertEquals(expected, actual)
        // And the walk really did move — otherwise this test would pass on a quantising fix.
        assertEquals(21, expected.distinct().size)
    }

    @Test
    fun `a void post yields null, cached and uncached alike`() {
        val dir = tempDir()
        val tile = DtedTile.open(writeTile(dir, voidAt = 1 to 1))!!
        val lat = 10.0 + 1 * step
        val lon = -1.0 + 1 * step
        assertNull(tile.elevationAt(lat, lon))
        assertNull("the miss must be cached as a miss, not as an elevation", tile.elevationAt(lat, lon))
        tile.close()
    }

    @Test
    fun `a point outside the tile is null and costs no read`() {
        val dir = tempDir()
        val tile = DtedTile.open(writeTile(dir))!!
        assertNull(tile.elevationAt(20.0, -1.0))
        assertNull(tile.elevationAt(10.0, 5.0))
        tile.close()
    }

    /** [DtedIndex.invalidate] calls close() when the pilot imports or deletes terrain; the tile
     *  must still answer afterwards, from a fresh handle. */
    @Test
    fun `close is idempotent and the tile still answers after it`() {
        val dir = tempDir()
        val tile = DtedTile.open(writeTile(dir))!!
        val before = tile.elevationAt(10.0 + 2 * step, -1.0 + 2 * step)
        tile.close()
        tile.close()
        assertEquals(before, tile.elevationAt(10.0 + 2 * step, -1.0 + 2 * step))
        tile.close()
    }

    /** The LRU bound must not change an answer — only how much is kept. nLon*nLat here is far
     *  under the cap, so this is the eviction path's shape, not its threshold. */
    @Test
    fun `every post is still correct after the whole grid has been walked twice`() {
        val dir = tempDir()
        val tile = DtedTile.open(writeTile(dir))!!
        repeat(2) {
            for (col in 0 until nLon) {
                for (row in 0 until nLat) {
                    assertEquals(
                        postValue(col, row).toDouble(),
                        tile.elevationAt(10.0 + row * step, -1.0 + col * step)!!,
                        1e-6,
                    )
                }
            }
        }
        tile.close()
    }
}
