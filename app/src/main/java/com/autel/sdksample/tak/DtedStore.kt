package com.autel.sdksample.tak

import android.content.Context
import android.net.Uri
import com.taklite.util.AppLog
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Stores pilot-uploaded DTED (Digital Terrain Elevation Data, .dt0 to .dt5) files in a
 * single shared `terrain/` directory (no per-region subfolders — see [TerrainDatabase]'s doc
 * for why), while [TerrainDao] tracks which imported "region" (one row per uploaded .zip, e.g.
 * "North Valley") references which tile filenames. Users manage regions, not individual tiles;
 * overlapping tiles between regions are physically stored once and shared.
 *
 * [DtedIndex] reads the pool for every terrain lookup (the slant-range solve, the AR overlay,
 * the terrain-corrected height). The import runs on a worker thread ([importAsync]).
 *
 * ⚠ **ONE IMPORT AT A TIME, AND WHILE IT RUNS NOTHING ELSE TOUCHES THE POOL**.
 * [isImportRunning] is process-wide, not a property of a button: re-opening Pre-Flight
 * (or the mode-change rebuild) during an import used to drop the running import's database row
 * as "interrupted", and Clean and Delete could remove the files it had just written.
 */
object DtedStore {
    private const val TAG = "DtedStore"
    private const val DIR_NAME = "terrain"
    private const val LEGACY_DIR_NAME = "dted" // pre-Room per-region-folder layout, discarded
    /**
     * The tile extensions an import accepts.
     *
     * ⚠ **`dt3`-`dt5` ARE ACCEPTED, AND NOTHING ELSE HAD TO CHANGE FOR THEM.**
     * `DtedTile.open` takes the post spacing, the grid size and the origin from the UHL HEADER
     * and never looks at the filename, so a finer tile was always readable — the importer was
     * the only thing refusing it. NGA defines levels past 2.
     *
     * ⚠ **THE FAULT THIS FIXES WAS SILENT, AND THAT IS WHY IT MATTERED.** A zip holding ONLY
     * unaccepted tiles fails loudly ("No DTED tiles found"). A MIXED zip did not: the accepted
     * entries imported, the region appeared with a tile count, and the rest were dropped with
     * no mention anywhere. `DtedIndex`'s `loaded N/M` line could not reveal it either — `M`
     * counts files already in the pool, and a refused tile never reaches the pool.
     */
    private val TILE_EXTENSIONS = setOf("dt0", "dt1", "dt2", "dt3", "dt4", "dt5")

    data class ImportResult(val importedCount: Int, val error: String? = null)

    private val importRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True while an import is writing the pool. The screen disables Import, Clean and Delete. */
    val isImportRunning: Boolean get() = importRunning.get()

    /** A zip with more entries than this is not a terrain archive. */
    private const val MAX_ZIP_ENTRIES = 20_000

    /** Stop an import before the controller's storage falls below this. */
    private const val FREE_SPACE_RESERVE_BYTES = 512L * 1024 * 1024

    /** A tile being written. Hidden from [listFiles] (leading dot), renamed when it is whole. */
    private fun partFile(pool: File, name: String) = File(pool, ".$name.part")
    data class Region(
        val id: Long,
        val name: String,
        val importedAtMs: Long,
        val fileCount: Int,
        val totalBytes: Long,
    )

    fun dir(context: Context): File {
        wipeLegacyDirOnce(context)
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** One-shot: the old per-region-folder layout is being retired wholesale (no migration —
     *  region zips are re-imported fresh against the new mechanism). */
    @Volatile private var legacyWiped = false
    private fun wipeLegacyDirOnce(context: Context) {
        if (legacyWiped) return
        legacyWiped = true
        val legacy = File(context.filesDir, LEGACY_DIR_NAME)
        if (legacy.exists()) {
            val ok = legacy.deleteRecursively()
            AppLog.i(TAG, "wiped legacy DTED dir ($LEGACY_DIR_NAME) -> $ok")
        }
    }

    /** Every tile file in the shared pool — flat, for [DtedIndex]'s elevation lookups (which
     *  don't care which region a tile came from, only where on Earth it is). Also sweeps any
     *  `pending` import row left behind by a crashed/interrupted import (see [TerrainDatabase]
     *  doc) — its partially-extracted files, if any, are harmless unreferenced leftovers. */
    fun listFiles(context: Context): List<File> {
        reconcileIncompleteImports(context)
        return dir(context).listFiles { f -> f.isFile && !f.name.startsWith(".") }
            ?.sortedBy { it.name } ?: emptyList()
    }

    /** One entry per completed import ("region"), name-sorted. */
    fun listRegions(context: Context): List<Region> {
        reconcileIncompleteImports(context)
        return TerrainDatabase.get(context).terrainDao().listImports()
            .map { Region(it.id, it.displayName, it.importedAtMs, it.fileCount, it.totalBytes) }
    }

    private fun reconcileIncompleteImports(context: Context) {
        // A pending row is "interrupted" only if no import is running in THIS process.
        if (importRunning.get()) return
        val dao = TerrainDatabase.get(context).terrainDao()
        val stuck = dao.listIncompleteImports()
        for (imp in stuck) {
            AppLog.w(TAG, "dropping incomplete import #${imp.id} \"${imp.displayName}\" (interrupted mid-import)")
            dao.deleteImportRow(imp.id)
        }
    }

    /** Deletes any tile file in the shared pool with zero remaining references — leftovers
     *  from an interrupted import or an interrupted deletion (see [TerrainDatabase] doc).
     *  Returns the number of files removed. */
    fun cleanUnreferencedTiles(context: Context): Int {
        // The running import's tiles are not referenced until it finishes.
        if (importRunning.get()) return 0
        val dao = TerrainDatabase.get(context).terrainDao()
        if (dao.listIncompleteImports().isNotEmpty()) return 0
        val referenced = dao.allReferencedTiles().toHashSet()
        val orphaned = dir(context).listFiles { f -> f.isFile && f.name !in referenced } ?: emptyArray()
        var removed = 0
        for (f in orphaned) if (runCatching { f.delete() }.getOrDefault(false)) removed++
        if (removed > 0) {
            AppLog.i(TAG, "cleaned $removed unreferenced tile file(s)")
            DtedIndex.invalidate()
        }
        return removed
    }

    /** Imports the picked document as a new region named after [displayName] (extension
     *  stripped): a .zip has every .dt0-.dt5 entry extracted into the shared pool
     *  (overwriting any same-named tile in place — the newest import always wins, no
     *  confirmation needed, by design); anything else is imported as a lone-tile region.
     *  Crash-safe: the import row is inserted `pending` before any file I/O and only flipped
     *  to `complete` after every tile is on disk — see [TerrainDatabase]. */
    /**
     * [import] on a worker thread, with both callbacks delivered on the MAIN thread.
     *
     * ⚠ **THE IMPORT USED TO RUN SYNCHRONOUSLY IN `onActivityResult`, AND A REAL REGION
     * PROVED IT WRONG.** An import of 39 IFSAR tiles — a 1.1 GB zip, 3.16 GB extracted — turned
     * the flight application's window BLACK for minutes while the main thread decompressed and
     * wrote every one. The controller itself stayed responsive, which is how it differs from a
     * compositor freeze: only this application's UI thread was blocked.
     *
     * It was always wrong and the size is what made it visible. A handful of 26 MB DTED2 tiles
     * blocked for a second or two and nobody reported it.
     *
     * ⚠ The progress callback exists because the screen showed its PREVIOUS text for the whole
     * operation — the status line was only written after the call returned, so a working
     * import and a dead screen looked identical.
     */
    fun importAsync(
        context: Context,
        uri: Uri,
        displayName: String,
        onProgress: (Int) -> Unit = {},
        onDone: (ImportResult) -> Unit,
    ) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        if (!importRunning.compareAndSet(false, true)) {
            main.post { onDone(ImportResult(0, "An import is already running")) }
            return
        }
        Thread {
            val r = try {
                import(context, uri, displayName) { tiles -> main.post { onProgress(tiles) } }
            } catch (t: Throwable) {
                AppLog.w(TAG, "DTED import failed: ${t.message}")
                ImportResult(0, t.message ?: "Import failed")
            } finally {
                importRunning.set(false)
                DtedIndex.invalidate()   // every exit, success or not
            }
            main.post { onDone(r) }
        }.start()
    }

    fun import(
        context: Context,
        uri: Uri,
        displayName: String,
        onProgress: (Int) -> Unit = {},
    ): ImportResult {
        val regionName = sanitizeRegionName(displayName.substringBeforeLast('.'))
        val dao = TerrainDatabase.get(context).terrainDao()
        val importId = dao.insertImport(
            ImportEntity(
                displayName = regionName,
                importedAtMs = System.currentTimeMillis(),
                fileCount = 0,
                totalBytes = 0,
                status = "pending",
            )
        )
        val pool = dir(context)
        // A zip by its CONTENT, not its name: a SAF display name is whatever the
        // provider says.
        val result = if (isZip(context, uri)) {
            importZip(context, uri, pool, onProgress)
        } else {
            importSingleFile(context, uri, displayName, pool)
        }
        if (result.tileNames.isEmpty()) {
            dao.deleteImportRow(importId) // don't leave an empty/failed region behind
            return ImportResult(0, result.error ?: "No DTED tiles found (.dt0 to .dt5)")
        }
        val totalBytes = result.tileNames.sumOf { File(pool, it).length() }
        dao.finishImport(importId, result.tileNames, totalBytes)
        DtedIndex.invalidate()
        AppLog.i(TAG, "import #$importId \"$regionName\": ${result.tileNames.size} tile(s), $totalBytes bytes")
        return ImportResult(result.tileNames.size, result.error)
    }

    private fun sanitizeRegionName(name: String): String {
        val cleaned = name.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_")
        return cleaned.ifEmpty { "Region-${System.currentTimeMillis()}" }
    }

    private data class ExtractResult(val tileNames: List<String>, val error: String? = null)

    private fun isZip(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { s ->
            val b = ByteArray(4)
            s.read(b) == 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() &&
                b[2] == 3.toByte() && b[3] == 4.toByte()
        } ?: false
    }.getOrDefault(false)

    /**
     * The pool name for an incoming name: separators flattened, leading dots stripped, only a
     * tile extension accepted, and the result checked to sit INSIDE the pool.
     * Null means refuse it.
     */
    internal fun poolName(raw: String): String? {
        val flat = raw.replace('/', '_').replace('\\', '_').trimStart('.', '_', ' ')
        if (flat.isEmpty()) return null
        val ext = flat.substringAfterLast('.', "").lowercase()
        return if (ext in TILE_EXTENSIONS) flat else null
    }

    private fun inPool(pool: File, f: File): Boolean =
        runCatching { f.canonicalFile.parentFile == pool.canonicalFile }.getOrDefault(false)

    /**
     * Copies [input] to a hidden part file, checks the result against its own header and
     * renames it into the pool. Returns null on success, or the reason it was refused. The part
     * file never survives a failure, so a cut-short tile cannot reach [DtedIndex].
     */
    private fun writeTile(input: java.io.InputStream, pool: File, name: String): String? {
        val dest = File(pool, name)
        val part = partFile(pool, name)
        if (!inPool(pool, dest) || !inPool(pool, part)) return "refused name $name"
        try {
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var sinceCheck = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sinceCheck += n
                    // Do not fill the controller. Checked every 64 MiB.
                    if (sinceCheck >= (64L shl 20)) {
                        sinceCheck = 0
                        if (pool.usableSpace < FREE_SPACE_RESERVE_BYTES)
                            throw java.io.IOException("the controller's storage is nearly full")
                    }
                }
            }
            val expected = DtedTile.expectedLength(part)
                ?: return "$name is not a DTED tile".also { part.delete() }
            if (part.length() != expected) {
                part.delete()
                return "$name is incomplete (${part.length()} of $expected bytes)"
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) { part.delete(); return "could not store $name" }
            return null
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
    }

    private fun importSingleFile(context: Context, uri: Uri, displayName: String, pool: File): ExtractResult {
        val name = poolName(displayName)
            ?: return ExtractResult(emptyList(), "Not a DTED tile or zip: $displayName")
        return try {
            val err = context.contentResolver.openInputStream(uri)?.use { input ->
                writeTile(input, pool, name)
            } ?: "Failed to read $displayName"
            if (err != null) ExtractResult(emptyList(), err) else ExtractResult(listOf(name))
        } catch (t: Throwable) {
            AppLog.w(TAG, "DTED file import failed: ${t.message}")
            ExtractResult(emptyList(), t.message)
        }
    }

    /** Extracts every .dt0-.dt5 entry from the zip into the shared pool, flattening each
     *  entry's path ("w101/n45.dt2" -> "w101_n45.dt2") so same-named tiles from different
     *  longitude folders don't collide with each other WITHIN this zip. Across zips, an
     *  identical flattened name is treated as the same physical tile and overwritten (the
     *  intended "duplicate imports are fine, newest wins" behavior). */
    private fun importZip(
        context: Context,
        uri: Uri,
        pool: File,
        onProgress: (Int) -> Unit = {},
    ): ExtractResult {
        val tileNames = mutableListOf<String>()
        val refused = mutableListOf<String>()
        var entries = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (++entries > MAX_ZIP_ENTRIES)
                            throw java.io.IOException("more than $MAX_ZIP_ENTRIES entries — not a terrain archive")
                        if (!entry.isDirectory) {
                            val flatName = poolName(entry.name)
                            if (flatName != null) {
                                // A declared size that cannot fit is refused before it is written.
                                if (entry.size > 0 &&
                                    pool.usableSpace - entry.size < FREE_SPACE_RESERVE_BYTES)
                                    throw java.io.IOException("not enough free storage for ${entry.name}")
                                val err = writeTile(zip, pool, flatName)
                                if (err == null) {
                                    tileNames.add(flatName)
                                    onProgress(tileNames.size)
                                } else {
                                    AppLog.w(TAG, "zip entry refused: $err")
                                    refused.add(entry.name)
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return ExtractResult(emptyList(), "Could not open zip")
        } catch (t: Throwable) {
            AppLog.w(TAG, "DTED zip import failed: ${t.message}")
            return ExtractResult(tileNames, "Zip import failed: ${t.message}")
        }
        if (refused.isNotEmpty()) {
            return ExtractResult(tileNames, "${refused.size} tile(s) refused (incomplete or not DTED)")
        }
        return ExtractResult(tileNames)
    }

    /** Deletes a region: removes its DB row and any tile file that only it referenced, leaving
     *  tiles still used by another region intact. */
    fun deleteRegion(context: Context, region: Region): Boolean {
        if (importRunning.get()) return false   // an import is writing the pool
        val dao = TerrainDatabase.get(context).terrainDao()
        val orphans = dao.deleteImportAndReturnOrphans(region.id)
        val pool = dir(context)
        for (name in orphans) runCatching { File(pool, name).delete() }
        AppLog.i(TAG, "deleted region \"${region.name}\" (#${region.id}): ${orphans.size} tile(s) freed")
        DtedIndex.invalidate()
        return true
    }
}
