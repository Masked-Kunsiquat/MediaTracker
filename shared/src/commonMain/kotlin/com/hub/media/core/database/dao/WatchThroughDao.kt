package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hub.media.core.database.entities.WatchThroughEntity
import kotlinx.coroutines.flow.Flow

/**
 * A show's watch-throughs (#231). Writes that also move ticks -- Watch again, Remove this watch-through --
 * live in [TVWriteDao]'s transactions, and only there.
 */
@Dao
interface WatchThroughDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(run: WatchThroughEntity)

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

    /**
     * Abandon or Resume the show's latest run, in one statement (#231 review): finding the latest and
     * updating it as two steps could flag an older run if Watch again landed in between.
     *
     * @return 0 when the show has no run.
     */
    @Query(
        "UPDATE watch_throughs SET abandoned = :abandoned WHERE id = " +
            "(SELECT w.id FROM watch_throughs w WHERE w.mediaId = :mediaId ORDER BY w.number DESC LIMIT 1)",
    )
    suspend fun setLatestAbandoned(
        mediaId: String,
        abandoned: Boolean,
    ): Int

    @Query("UPDATE watch_throughs SET rating = :rating WHERE id = :id")
    suspend fun updateRating(
        id: String,
        rating: Int?,
    ): Int

    @Query("SELECT COUNT(*) FROM watch_logs WHERE watchThroughId = :id")
    suspend fun viewingCount(id: String): Int
}
