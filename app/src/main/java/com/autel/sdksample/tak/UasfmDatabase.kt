package com.autel.sdksample.tak

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

/**
 * Stores the FAA UAS Facility Map (UASFM) altitude ceilings the pilot has downloaded for their
 * operating area — see [UasfmStore] for the fetch and [UasfmIndex] for the lookup.
 *
 * **Why a separate database from [TerrainDatabase] rather than two more tables in it:** that DB
 * is built with `fallbackToDestructiveMigration()`, so bumping its schema version would silently
 * wipe the pilot's imported DTED regions. UASFM data is independent of terrain data, has its own
 * lifecycle (re-downloaded when the FAA updates, not imported once), and nothing joins across
 * the two — so there's no reason to put the DTED tiles at risk to store it.
 *
 * **Why no multi-region management (unlike DTED):** cells live in a single global grid keyspace
 * ([UasfmIndex]'s 1/120° row/col), so overlapping downloads would merge into the same rows and
 * there'd be no honest way to say which download "owns" a cell when the pilot deletes one. A
 * refcount scheme like the DTED junction table would work, but the coverage question here is
 * simpler than for terrain: the pilot wants ceilings for the area they're flying, and asking for
 * a bigger area is one download away. So this is deliberately a single dataset, replaced wholesale
 * on each download, with one [UasfmMetaEntity] row describing what's currently loaded.
 */
@Entity(tableName = "uasfm_cells", primaryKeys = ["gridRow", "gridCol"])
data class UasfmCellEntity(
    val gridRow: Int,
    val gridCol: Int,
    /** Ceiling in feet AGL, straight from the service's CEILING field (UNIT is always "Feet"). */
    val ceilingFt: Int,
)

/** Single row (id is always 1) describing the currently-loaded dataset. */
@Entity(tableName = "uasfm_meta")
data class UasfmMetaEntity(
    @PrimaryKey val id: Int = 1,
    val areaLabel: String,
    val downloadedAtMs: Long,
    val cellCount: Int,
    /** FAA MAP_EFF effective date(s) across the downloaded cells, pre-formatted for display. */
    val effectiveLabel: String,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)

@Dao
interface UasfmDao {
    // Non-suspend to match TerrainDao's convention (see its note); UasfmStore does its network
    // + DB work on a background thread of its own, so nothing here runs on the main thread
    // except the cheap reads (meta/allCells) that UasfmIndex does at load time.

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertCells(cells: List<UasfmCellEntity>)

    @Query("DELETE FROM uasfm_cells")
    fun deleteAllCells()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun setMeta(meta: UasfmMetaEntity)

    @Query("DELETE FROM uasfm_meta")
    fun deleteMeta()

    @Query("SELECT * FROM uasfm_cells")
    fun allCells(): List<UasfmCellEntity>

    @Query("SELECT * FROM uasfm_meta WHERE id = 1")
    fun meta(): UasfmMetaEntity?

    @Query("SELECT COUNT(*) FROM uasfm_cells")
    fun cellCount(): Int

    /** Swaps in a freshly downloaded dataset atomically — the old one stays queryable until
     *  this commits, so a crash mid-write can't leave the pilot with a half-replaced set of
     *  ceilings that looks complete. */
    @Transaction
    fun replaceAll(cells: List<UasfmCellEntity>, meta: UasfmMetaEntity) {
        deleteAllCells()
        // Chunked because SQLite caps host parameters per statement (999 on older Android
        // versions) and a statewide download is tens of thousands of rows.
        cells.chunked(500).forEach { insertCells(it) }
        setMeta(meta)
    }

    @Transaction
    fun clearAll() {
        deleteAllCells()
        deleteMeta()
    }
}

/**
 * **Version 2: bumped deliberately to WIPE existing data, not because the schema
 * changed.** v1 was populated from the stale `FAA_UAS_FacilityMap_Data_V5` layer and contained
 * ceilings up to four years out of date — it reported 0 ft in a real 200 ft grid. With
 * `fallbackToDestructiveMigration()` this bump drops those rows, so the app falls back to
 * showing "no FAA data downloaded" and the pilot re-downloads from the corrected source.
 *
 * Wiping is the right call over migrating: there is no way to correct the old rows in place,
 * and an airspace advisory silently serving wrong ceilings is worse than one that admits it has
 * nothing. Bump this again if the source layer ever changes.
 */
@Database(entities = [UasfmCellEntity::class, UasfmMetaEntity::class], version = 2, exportSchema = false)
abstract class UasfmDatabase : RoomDatabase() {
    abstract fun uasfmDao(): UasfmDao

    companion object {
        @Volatile private var instance: UasfmDatabase? = null

        fun get(context: Context): UasfmDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, UasfmDatabase::class.java, "uasfm.db"
            )
                .allowMainThreadQueries()
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
