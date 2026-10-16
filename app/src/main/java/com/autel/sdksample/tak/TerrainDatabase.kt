package com.autel.sdksample.tak

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

/**
 * Tracks imported DTED "regions" (one row per imported .zip, e.g. "North Valley") against a
 * single shared pool of tile files on disk (see [DtedStore] — one `terrain/` directory, no
 * per-region subfolders, so overlapping tiles between regions are stored once).
 *
 * Deliberately just ONE junction table ([ImportTileEntity]) rather than a forward index plus a
 * separately-maintained reverse index / refcount column: a stored refcount can drift out of
 * sync with reality if any write path forgets to update it, where a `COUNT()`/`NOT IN` query
 * over the junction table is always correct by construction and cheap at the row counts this
 * app deals with (tens of regions, hundreds–thousands of tiles).
 *
 * Crash safety: an [ImportEntity] is inserted with `status = "pending"` BEFORE any tile
 * extraction happens, and only flipped to `"complete"` (with the tile rows) once extraction
 * finishes — see [DtedStore.import]. If the process dies mid-import, the leftover `pending`
 * row is swept on the next [DtedStore.listRegions] call; any tile files it managed to extract
 * before dying are simply unreferenced, harmless leftovers in the shared pool (cleanable via
 * [DtedStore.cleanUnreferencedTiles]).
 */
@Entity(tableName = "imports")
data class ImportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val importedAtMs: Long,
    val fileCount: Int,
    val totalBytes: Long,
    val status: String, // "pending" | "complete"
)

@Entity(
    tableName = "import_tiles",
    primaryKeys = ["importId", "tileName"],
    foreignKeys = [ForeignKey(
        entity = ImportEntity::class,
        parentColumns = ["id"],
        childColumns = ["importId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("tileName"), Index("importId")],
)
data class ImportTileEntity(
    val importId: Long,
    val tileName: String,
)

@Dao
interface TerrainDao {
    // Plain (non-suspend) queries deliberately — mirrors the existing DtedStore.import() call
    // pattern, which already runs zip extraction synchronously on the caller's thread (see
    // TakConnectActivity.onActivityResult); adding coroutines here would be new architecture
    // for no benefit. TerrainDatabase.get() enables allowMainThreadQueries() to match.

    @Insert
    fun insertImport(import: ImportEntity): Long

    @Query("DELETE FROM imports WHERE id = :importId")
    fun deleteImportRow(importId: Long)

    @Insert
    fun insertTiles(tiles: List<ImportTileEntity>)

    @Query("UPDATE imports SET fileCount = :fileCount, totalBytes = :totalBytes, status = 'complete' WHERE id = :importId")
    fun markComplete(importId: Long, fileCount: Int, totalBytes: Long)

    @Query("SELECT * FROM imports WHERE status = 'complete' ORDER BY displayName COLLATE NOCASE")
    fun listImports(): List<ImportEntity>

    @Query("SELECT * FROM imports WHERE status != 'complete'")
    fun listIncompleteImports(): List<ImportEntity>

    @Query("SELECT DISTINCT tileName FROM import_tiles")
    fun allReferencedTiles(): List<String>

    @Query(
        "SELECT tileName FROM import_tiles WHERE importId = :importId " +
            "AND tileName NOT IN (SELECT tileName FROM import_tiles WHERE importId != :importId)"
    )
    fun orphanedTilesForImport(importId: Long): List<String>

    @Transaction
    fun finishImport(importId: Long, tileNames: List<String>, totalBytes: Long) {
        insertTiles(tileNames.map { ImportTileEntity(importId, it) })
        markComplete(importId, tileNames.size, totalBytes)
    }

    /** Deletes the import row (cascades to its import_tiles rows) and returns the tile names
     *  that were ONLY referenced by this import — the caller deletes those files from disk
     *  after this transaction commits, so a crash mid-file-deletion leaves harmless orphaned
     *  files rather than a DB row pointing at a file that no longer exists. */
    @Transaction
    fun deleteImportAndReturnOrphans(importId: Long): List<String> {
        val orphans = orphanedTilesForImport(importId)
        deleteImportRow(importId)
        return orphans
    }
}

@Database(entities = [ImportEntity::class, ImportTileEntity::class], version = 1, exportSchema = false)
abstract class TerrainDatabase : RoomDatabase() {
    abstract fun terrainDao(): TerrainDao

    companion object {
        @Volatile private var instance: TerrainDatabase? = null

        fun get(context: Context): TerrainDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, TerrainDatabase::class.java, "terrain.db"
            )
                .allowMainThreadQueries()
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
