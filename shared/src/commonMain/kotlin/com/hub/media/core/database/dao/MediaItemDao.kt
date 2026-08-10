package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaItemDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: MediaItemEntity)

    @Update
    suspend fun update(item: MediaItemEntity)

    /**
     * Targeted single-column update of [MediaItemEntity.coverImageHash] only (see
     * [com.hub.media.features.books.data.BookRepository.updateCoverImageHash]'s KDoc for why: a
     * read-modify-write full-row [update] would silently revert any other field changed by a
     * concurrent writer between the read and the write). Touches nothing else on the row.
     *
     * @return The number of rows affected: `1` if [mediaId] resolved to an existing row, `0`
     *   otherwise (no such media item) -- the caller's only source of "not found" now that this
     *   no longer reads the row first.
     */
    @Query("UPDATE media_items SET coverImageHash = :coverImageHash WHERE id = :mediaId")
    suspend fun updateCoverImageHash(mediaId: String, coverImageHash: String?): Int

    @Delete
    suspend fun delete(item: MediaItemEntity)

    @Query("DELETE FROM media_items WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Deletes every media item whose id is in [ids], cascading to child tables via FK constraints
     * exactly as [deleteById] does. One statement rather than a loop of [deleteById] calls, so a
     * bulk delete is atomic at the database level: it cannot half-apply and leave the user looking
     * at a partially deleted selection (ROADMAP Task 14 Phase B).
     *
     * @return The number of rows actually removed, which can be lower than `ids.size` if an id no
     *   longer exists -- see [com.hub.media.features.books.domain.DeleteBooksUseCase] for why that
     *   is reported rather than treated as an error.
     */
    @Query("DELETE FROM media_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    /**
     * The distinct, non-null cover hashes referenced by [ids]. Read *before* the rows are deleted,
     * since afterwards there is nothing left to ask -- these are the only files a delete could
     * possibly make unreferenced (ROADMAP Task 14 Phase B).
     */
    @Query("SELECT DISTINCT coverImageHash FROM media_items WHERE id IN (:ids) AND coverImageHash IS NOT NULL")
    suspend fun getCoverHashesForIds(ids: List<String>): List<String>

    /**
     * How many media items still reference [coverImageHash].
     *
     * Covers are content-addressed (AGENTS.md §4), so identical artwork is stored once and shared:
     * two books with the same cover point at the same file. Deleting that file because *one* of
     * them was removed would silently blank the other's cover. This is the check that prevents it
     * -- called *after* the delete, so a zero result genuinely means nothing references the file
     * any more (ROADMAP Task 14 Phase B).
     */
    @Query("SELECT COUNT(*) FROM media_items WHERE coverImageHash = :coverImageHash")
    suspend fun countByCoverHash(coverImageHash: String): Int

    @Query("SELECT * FROM media_items WHERE id = :id")
    suspend fun getById(id: String): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE id = :id")
    fun observeById(id: String): Flow<MediaItemEntity?>

    @Query("SELECT * FROM media_items ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE type = :type ORDER BY title ASC")
    fun observeByType(type: MediaType): Flow<List<MediaItemEntity>>

    /**
     * One-shot (non-reactive) counterpart of [observeByType] (ROADMAP Task 14 Phase A), for
     * [com.hub.media.features.books.data.BookRepository.getAllBooksWithDetails] -- a single
     * library-wide scan for candidates
     * ([com.hub.media.features.books.domain.BulkBackfillUseCase] seeding its resume state) has no
     * use for an ongoing [Flow] subscription, unlike every reactive UI-facing read in this DAO.
     */
    @Query("SELECT * FROM media_items WHERE type = :type ORDER BY title ASC")
    suspend fun getAllByType(type: MediaType): List<MediaItemEntity>
}
