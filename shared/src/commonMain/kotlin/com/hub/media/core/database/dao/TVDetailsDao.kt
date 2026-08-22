package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.hub.media.core.database.entities.TVDetailsEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reads [TVDetailsEntity] (ROADMAP Task 13 Phase C). The TV counterpart of [MovieDetailsDao],
 * deliberately the same shape so the two read the same way at call sites.
 *
 * **Read-only on purpose.** Every TV write goes through [TVWriteDao], because each one spans
 * `media_items` and `tv_details` (and, for quick-fill, `episodes` too) and has to land atomically
 * -- including the repair of a parent row whose details row is missing, which is
 * [TVWriteDao.updateShowMetadataAtomically]'s job rather than a standalone insert here. A write
 * method on this DAO would only be a way to make half of one of those changes.
 */
@Dao
interface TVDetailsDao {
    @Query("SELECT * FROM tv_details WHERE mediaId = :mediaId")
    fun observeByMediaId(mediaId: String): Flow<TVDetailsEntity?>

    @Query("SELECT * FROM tv_details")
    fun observeAll(): Flow<List<TVDetailsEntity>>

    @Query("SELECT * FROM tv_details WHERE mediaId = :mediaId")
    suspend fun getByMediaId(mediaId: String): TVDetailsEntity?

    /**
     * One-shot whole-table read. No production caller today -- it exists for the tests that assert
     * a rejected write left *nothing* behind, which is a claim about the table rather than about
     * any one id they could look up.
     */
    @Query("SELECT * FROM tv_details")
    suspend fun getAll(): List<TVDetailsEntity>
}
