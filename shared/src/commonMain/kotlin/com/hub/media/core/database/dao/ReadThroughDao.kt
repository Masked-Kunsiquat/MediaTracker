package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hub.media.core.database.entities.ReadThroughEntity
import com.hub.media.core.database.entities.ReadingStatus
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** Reads of a book (#230). See [ReadThroughEntity]. */
@Dao
interface ReadThroughDao {
    /** ABORT: a second read with the same (mediaId, number) -- a double-tapped Read again -- throws. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(read: ReadThroughEntity)

    @Query("SELECT * FROM read_throughs WHERE mediaId = :mediaId ORDER BY number")
    fun observeForMedia(mediaId: String): Flow<List<ReadThroughEntity>>

    @Query("SELECT * FROM read_throughs WHERE mediaId = :mediaId ORDER BY number")
    suspend fun getForMedia(mediaId: String): List<ReadThroughEntity>

    @Query("SELECT * FROM read_throughs ORDER BY mediaId, number")
    fun observeAll(): Flow<List<ReadThroughEntity>>

    @Query("SELECT * FROM read_throughs ORDER BY mediaId, number")
    suspend fun getAll(): List<ReadThroughEntity>

    @Query("SELECT * FROM read_throughs WHERE mediaId = :mediaId ORDER BY number DESC LIMIT 1")
    suspend fun getLatest(mediaId: String): ReadThroughEntity?

    @Query("SELECT * FROM read_throughs WHERE id = :id")
    suspend fun getById(id: String): ReadThroughEntity?

    @Query("UPDATE read_throughs SET status = :status, finishedAt = :finishedAt WHERE id = :id")
    suspend fun updateStatus(
        id: String,
        status: ReadingStatus,
        finishedAt: Instant?,
    ): Int

    @Query("UPDATE read_throughs SET rating = :rating WHERE id = :id")
    suspend fun updateRating(
        id: String,
        rating: Int?,
    ): Int

    @Query("SELECT COUNT(*) FROM reading_sessions WHERE readThroughId = :id")
    suspend fun sessionCount(id: String): Int

    @Query("DELETE FROM read_throughs WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
