package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.hub.media.core.database.entities.MovieDetailsEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reads [MovieDetailsEntity] (ROADMAP Task 13 Phase B). The movie counterpart of
 * [BookDetailsDao], and deliberately the same shape so the two read the same way at call sites.
 *
 * **Read-only on purpose.** Every movie write goes through [MovieWriteDao], because each one spans
 * both `media_items` and `movie_details` and has to land atomically — including the repair of a
 * parent row whose details row is missing, which is
 * [MovieWriteDao.updateMovieMetadataAtomically]'s job rather than a standalone insert here. A
 * write method on this DAO would only be a way to make half of one of those changes.
 */
@Dao
interface MovieDetailsDao {
    @Query("SELECT * FROM movie_details WHERE mediaId = :mediaId")
    fun observeByMediaId(mediaId: String): Flow<MovieDetailsEntity?>

    @Query("SELECT * FROM movie_details")
    fun observeAll(): Flow<List<MovieDetailsEntity>>

    @Query("SELECT * FROM movie_details WHERE mediaId = :mediaId")
    suspend fun getByMediaId(mediaId: String): MovieDetailsEntity?

    /**
     * One-shot whole-table read. No production caller today — it exists for the tests that assert a
     * rejected write left *nothing* behind, which is a claim about the table rather than about any
     * one id they could look up.
     */
    @Query("SELECT * FROM movie_details")
    suspend fun getAll(): List<MovieDetailsEntity>
}
