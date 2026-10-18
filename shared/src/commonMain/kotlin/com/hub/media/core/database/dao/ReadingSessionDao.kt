package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.hub.media.core.database.entities.ReadingSessionEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.util.newId
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

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

    @Query("SELECT id FROM read_throughs WHERE mediaId = :mediaId ORDER BY number DESC LIMIT 1")
    suspend fun latestReadId(mediaId: String): String?

    /** Read #1 for a book that has none yet (#230); a no-op when it exists, via the unique (mediaId, number) index. */
    @Query(
        "INSERT OR IGNORE INTO read_throughs (id, mediaId, number, status, startedAt, finishedAt, rating) " +
            "VALUES (:id, :mediaId, 1, :status, NULL, :finishedAt, NULL)",
    )
    suspend fun insertFirstReadIfMissing(
        id: String,
        mediaId: String,
        status: ReadingStatus,
        finishedAt: Instant?,
    )

    /**
     * Inserts [session] into its book's latest read (#230): new sessions always belong to the current read.
     *
     * A book with no read at all -- which every write path now prevents, but a row from before them or a
     * hand-built one could still be -- gets read #1 at Reading first, the rule `MIGRATION_7_8` applies to a
     * book with sessions. Self-healing rather than failing, as the details-row edge case is handled.
     */
    @Transaction
    suspend fun insertIntoLatestRead(session: ReadingSessionEntity) {
        val readId =
            latestReadId(session.mediaId)
                ?: newId().also { insertFirstReadIfMissing(it, session.mediaId, ReadingStatus.READING, null) }
        insert(session.copy(readThroughId = readId))
    }
}
