package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.hub.media.core.database.entities.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: ReadingSessionEntity)

    /**
     * @return The number of rows affected: `1` if [session]'s id resolved to an existing row,
     *   `0` otherwise. Room's generated `@Update` silently no-ops (affects zero rows) rather than
     *   throwing when the primary key doesn't match an existing row -- e.g. the row was deleted by
     *   another writer between an earlier `getById` and this call -- so callers that need to
     *   distinguish "updated" from "no matching row" must check this return value rather than
     *   assuming success (see
     *   [com.hub.media.features.books.data.ReadingSessionRepository.updateSession]).
     */
    @Update
    suspend fun update(session: ReadingSessionEntity): Int

    @Delete
    suspend fun delete(session: ReadingSessionEntity)

    @Query("DELETE FROM reading_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM reading_sessions WHERE id = :id")
    suspend fun getById(id: String): ReadingSessionEntity?

    @Query("SELECT * FROM reading_sessions ORDER BY timestampStart DESC")
    fun observeAll(): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions WHERE mediaId = :mediaId ORDER BY timestampStart DESC")
    fun observeSessionsForMedia(mediaId: String): Flow<List<ReadingSessionEntity>>
}
