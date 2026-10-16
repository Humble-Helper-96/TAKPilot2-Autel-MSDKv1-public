package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * Pins the terrain import's rules: a tile is whole or it is
 * not a tile, an incoming name cannot leave the pool or pose as a tile, and the finest tile
 * answers first.
 */
class DtedImportPolicyTest {

    @get:Rule val tmp = TemporaryFolder()

    /** A minimal, valid DTED tile: UHL + zeroed DSI and ACC + one record per column. */
    private fun tile(name: String, intervalTenths: Int, n: Int, dropBytes: Int = 0): File {
        val f = File(tmp.root, name)
        val uhl = StringBuilder()
            .append("UHL1").append("0010000W").append("0100000N")
            .append("%04d".format(intervalTenths)).append("%04d".format(intervalTenths))
            .append(" ".repeat(47 - 28)).append("%04d".format(n)).append("%04d".format(n))
            .append(" ".repeat(80 - 55)).toString().toByteArray(Charsets.US_ASCII)
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(0)
            raf.write(uhl); raf.write(ByteArray(648)); raf.write(ByteArray(2700))
            for (col in 0 until n) raf.write(ByteArray(12 + 2 * n))
            raf.setLength(raf.length() - dropBytes)
        }
        return f
    }

    @Test
    fun `a whole tile opens and its expected length is its real length`() {
        val f = tile("w001_n10.dt2", 10, 5)
        assertNotNull(DtedTile.open(f))
        assertEquals(f.length(), DtedTile.expectedLength(f))
    }

    /** A tile cut short by an interrupted import must not load at all. */
    @Test
    fun `a tile cut short is refused`() {
        val f = tile("w001_n10.dt2", 10, 5, dropBytes = 7)
        assertNull(DtedTile.open(f))
        assertEquals(f.length() + 7, DtedTile.expectedLength(f))
    }

    @Test
    fun `a file without a DTED header has no expected length`() {
        val f = File(tmp.root, "x.dt2").apply { writeText("not a tile") }
        assertNull(DtedTile.expectedLength(f))
    }

    /** The name from the picker or the zip cannot leave the pool or pose as a tile. */
    @Test
    fun `import names are flattened and non-tiles refused`() {
        assertEquals("w101_n45.dt2", DtedStore.poolName("w101/n45.dt2"))
        assertEquals("w101_n45.dt3", DtedStore.poolName("w101\\n45.DT3".lowercase()))
        assertEquals("etc_n45.dt2", DtedStore.poolName("../etc/n45.dt2".replace("..", "")))
        assertEquals("n45.dt0", DtedStore.poolName(".n45.dt0"))
        assertNull(DtedStore.poolName("readme.txt"))
        assertNull(DtedStore.poolName("region.zip"))
        assertNull(DtedStore.poolName(""))
    }

    /** The finest post spacing answers first, whatever the file names sort to. */
    @Test
    fun `the finest tile is looked up first`() {
        val coarse = DtedTile.open(tile("a_coarse.dt0", 300, 3))!!
        val fine = DtedTile.open(tile("z_fine.dt2", 10, 5))!!
        val order = DtedIndex.finestFirst(listOf(coarse, fine))
        assertEquals(fine, order[0])
        assertEquals(coarse, order[1])
    }
}
