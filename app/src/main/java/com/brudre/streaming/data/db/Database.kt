package com.brudre.streaming.data.db

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
import com.brudre.streaming.data.Kind
import com.brudre.streaming.data.Title
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val titleId: String,
    val name: String,
    val kind: String,
    val posterUrl: String?,
    val year: String?,
    val addedAt: Long,
) {
    fun toTitle() = Title(id = titleId, kind = Kind.parse(kind), name = name, year = year, posterUrl = posterUrl, bannerUrl = posterUrl)
}

@Entity(tableName = "progress", primaryKeys = ["titleId", "episodeKey"])
data class ProgressEntity(
    val titleId: String,
    val episodeKey: String,
    val titleName: String,
    val episodeLabel: String,
    val kind: String,
    val posterUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    fun toTitle() = Title(id = titleId, kind = Kind.parse(kind), name = titleName, posterUrl = posterUrl, bannerUrl = posterUrl)
}

fun ProgressEntity.fraction(): Float = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

@Entity(tableName = "catalog_cache")
data class CatalogCacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val fetchedAt: Long,
)

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE titleId = :titleId)")
    fun observeIsFavorite(titleId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE titleId = :titleId)")
    suspend fun isFavorite(titleId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE titleId = :titleId")
    suspend fun delete(titleId: String)
}

@Dao
interface ProgressDao {
    /** Último episódio assistido de cada título, ainda não concluído. */
    @Query(
        """
        SELECT * FROM progress p
        WHERE p.updatedAt = (SELECT MAX(updatedAt) FROM progress WHERE titleId = p.titleId)
          AND p.positionMs > 15000
          AND (p.durationMs = 0 OR p.positionMs < p.durationMs * 0.95)
        ORDER BY p.updatedAt DESC LIMIT 20
        """
    )
    fun observeContinueWatching(): Flow<List<ProgressEntity>>

    @Query(
        """
        SELECT * FROM progress p
        WHERE p.updatedAt = (SELECT MAX(updatedAt) FROM progress WHERE titleId = p.titleId)
        ORDER BY p.updatedAt DESC LIMIT 60
        """
    )
    fun observeHistory(): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE titleId = :titleId")
    fun observeForTitle(titleId: String): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE titleId = :titleId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestForTitle(titleId: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE titleId = :titleId AND episodeKey = :episodeKey")
    suspend fun get(titleId: String, episodeKey: String): ProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProgressEntity)

    @Query("DELETE FROM progress")
    suspend fun clear()
}

@Dao
interface CatalogCacheDao {
    @Query("SELECT * FROM catalog_cache WHERE `key` = :key")
    suspend fun get(key: String): CatalogCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: CatalogCacheEntity)

    @Query("DELETE FROM catalog_cache")
    suspend fun clear()

    @Query("SELECT * FROM catalog_cache")
    suspend fun all(): List<CatalogCacheEntity>

    @Query("DELETE FROM catalog_cache WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Database(
    entities = [FavoriteEntity::class, ProgressEntity::class, CatalogCacheEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class BruDreDatabase : RoomDatabase() {
    abstract fun favorites(): FavoriteDao
    abstract fun progress(): ProgressDao
    abstract fun catalogCache(): CatalogCacheDao

    companion object {
        fun create(context: Context): BruDreDatabase =
            Room.databaseBuilder(context, BruDreDatabase::class.java, "brudre.db")
                .fallbackToDestructiveMigration()
                .build()

        fun inMemory(context: Context): BruDreDatabase =
            Room.inMemoryDatabaseBuilder(context, BruDreDatabase::class.java).build()
    }
}
