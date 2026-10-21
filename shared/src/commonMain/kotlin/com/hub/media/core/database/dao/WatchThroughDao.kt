package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hub.media.core.database.entities.WatchThroughEntity
import kotlinx.coroutines.flow.Flow

/** A show's watch-throughs (#231). Writes that also move ticks live in [TVWriteDao]'s transactions. */
@Dao
interface WatchThroughDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(run: WatchThroughEntity)

    /** Creates watch-through #1 unless the show already has it (the unique index makes this a no-op). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFirstIfMissing(run: WatchThroughEntity): Long

    @Query("SELECT * FROM watch_throughs WHERE mediaId = :mediaId ORDER BY number")
    fun observeForMedia(mediaId: String): Flow<List<WatchThroughEntity>>

    @Query("SELECT * FROM watch_throughs WHERE mediaId = :mediaId ORDER BY number")
    suspend fun getForMedia(mediaId: String): List<WatchThroughEntity>

    @Query("SELECT * FROM watch_throughs ORDER BY mediaId, number")
    fun observeAll(): Flow<List<WatchThroughEntity>>

    @Query("SELECT * FROM watch_throughs ORDER BY mediaId, number")
    suspend fun getAll(): List<WatchThroughEntity>

    @Query("SELECT * FROM watch_throughs WHERE mediaId = :mediaId ORDER BY number DESC LIMIT 1")
    suspend fun getLatest(mediaId: String): WatchThroughEntity?

    @Query("SELECT * FROM watch_throughs WHERE id = :id")
    suspend fun getById(id: String): WatchThroughEntity?

    @Query("UPDATE watch_throughs SET abandoned = :abandoned WHERE id = :id")
    suspend fun setAbandoned(
        id: String,
        abandoned: Boolean,
    ): Int

    @Query("UPDATE watch_throughs SET rating = :rating WHERE id = :id")
    suspend fun updateRating(
        id: String,
        rating: Int?,
    ): Int

    @Query("SELECT COUNT(*) FROM watch_logs WHERE watchThroughId = :id")
    suspend fun viewingCount(id: String): Int

    /** Deletes [id] only if it is its show's latest run, not #1, and has no viewings -- one statement, no race. */
    @Query(
        "DELETE FROM watch_throughs WHERE id = :id AND number > 1 " +
            "AND number = (SELECT MAX(w.number) FROM watch_throughs w WHERE w.mediaId = watch_throughs.mediaId) " +
            "AND NOT EXISTS (SELECT 1 FROM watch_logs l WHERE l.watchThroughId = :id)",
    )
    suspend fun deleteIfRemovable(id: String): Int
}
