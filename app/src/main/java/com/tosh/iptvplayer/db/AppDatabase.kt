package com.tosh.iptvplayer.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tosh.iptvplayer.model.Channel
import com.tosh.iptvplayer.model.PlaylistSource
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val playlistLocation: String,
    val playlistIsFile: Boolean,
    val epgLocation: String?,
    val epgIsFile: Boolean
)

@Entity(
    tableName = "channels",
    indices = [Index("sourceId"), Index("tvgId")]
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val groupTitle: String?,
    val tvgId: String?,
    val tvgName: String?,
    val catchupDays: Int
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    // Keyed by the channel's base name (quality suffix stripped), not a specific ChannelEntity
    // row id: multiple quality variants share one base name, and the "representative" row shown
    // per channel can change (e.g. once EPG data arrives) — keying by row id would make a
    // favorite silently vanish when that happens. The base name is stable.
    @PrimaryKey val baseName: String
)

@Entity(
    tableName = "programmes",
    indices = [Index("channelTvgId")]
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelTvgId: String,
    val title: String,
    val description: String?,
    val startMillis: Long,
    val stopMillis: Long
)

fun SourceEntity.toModel() = PlaylistSource(id, name, playlistLocation, playlistIsFile, epgLocation, epgIsFile)
fun PlaylistSource.toEntity() = SourceEntity(id, name, playlistLocation, playlistIsFile, epgLocation, epgIsFile)

fun ChannelEntity.toModel() = Channel(id, sourceId, name, streamUrl, logoUrl, groupTitle, tvgId, tvgName, catchupDays)
fun Channel.toEntity() = ChannelEntity(id, sourceId, name, streamUrl, logoUrl, groupTitle, tvgId, tvgName, catchupDays)

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY id DESC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources")
    suspend fun getAll(): List<SourceEntity>

    @Insert
    suspend fun insert(source: SourceEntity): Long

    @Delete
    suspend fun delete(source: SourceEntity)

    @Query("UPDATE sources SET epgLocation = :epgLocation, epgIsFile = :epgIsFile WHERE id = :sourceId")
    suspend fun updateEpg(sourceId: Long, epgLocation: String?, epgIsFile: Boolean)
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY groupTitle, name")
    fun observeAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId")
    suspend fun getForSource(sourceId: Long): List<ChannelEntity>

    @Insert
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    /** Atomically swaps a single source's channels — used when re-syncing a playlist, so a
     * failure partway through can't leave that source with only some of its channels. */
    @Transaction
    suspend fun replaceForSource(sourceId: Long, channels: List<ChannelEntity>) {
        deleteForSource(sourceId)
        insertAll(channels)
    }
}

@Dao
interface FavoriteDao {
    @Query("SELECT baseName FROM favorites")
    fun observeAllNames(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE baseName = :baseName)")
    suspend fun isFavorite(baseName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE baseName = :baseName")
    suspend fun remove(baseName: String)
}

@Dao
interface ProgrammeDao {
    @Query("SELECT * FROM programmes")
    suspend fun getAll(): List<ProgrammeEntity>

    @Query("DELETE FROM programmes")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(programmes: List<ProgrammeEntity>)

    /** Wraps the replace in one atomic transaction so a failure partway through (e.g. the app
     * getting backgrounded/killed mid-sync) can't leave the table half-deleted. */
    @Transaction
    suspend fun replaceAll(programmes: List<ProgrammeEntity>) {
        deleteAll()
        insertAll(programmes)
    }
}

@Database(
    entities = [SourceEntity::class, ChannelEntity::class, FavoriteEntity::class, ProgrammeEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun channelDao(): ChannelDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun programmeDao(): ProgrammeDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /** Adds the `favorites` table (v1 -> v2). Matches FavoriteEntity's Room-generated schema
         * exactly: a single TEXT primary key column, no other fields. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorites` (`baseName` TEXT NOT NULL, PRIMARY KEY(`baseName`))"
                )
            }
        }

        /** Adds the `programmes` table + its index (v2 -> v3), matching ProgrammeEntity. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `programmes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`channelTvgId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`description` TEXT, " +
                        "`startMillis` INTEGER NOT NULL, " +
                        "`stopMillis` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_programmes_channelTvgId` ON `programmes` (`channelTvgId`)"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "iptv_player.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    // Only as a last-resort safety net for a *downgrade* (e.g. a debug build
                    // pointing at a DB created by a newer version) — upgrades always go through
                    // the real migrations above, so existing sources/favorites/EPG survive.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { INSTANCE = it }
            }
    }
}
