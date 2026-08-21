package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.hub.media.core.database.entities.MovieDetailsEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes [MovieDetailsEntity] (ROADMAP Task 13 Phase B). The movie counterpart of
 * [BookDetailsDao], and deliberately the same shape so the two read the same way at call sites.
 *
 * Creating a movie does **not** go through this DAO's [insert]: a movie is two rows across two
 * tables and must land atomically, which is [MovieWriteDao]'s job. [insert] exists for the
 * self-healing case where a [com.hub.media.core.database.entities.MediaItemEntity] exists without
 * its details row.
 */
@Dao
interface MovieDetailsDao {
    @Query("SELECT * FROM movie_details WHERE mediaId = :mediaId")
    fun observeByMediaId(mediaId: String): Flow<MovieDetailsEntity?>

    @Query("SELECT * FROM movie_details")
    fun observeAll(): Flow<List<MovieDetailsEntity>>

    @Query("SELECT * FROM movie_details WHERE mediaId = :mediaId")
    suspend fun getByMediaId(mediaId: String): MovieDetailsEntity?

    @Query("SELECT * FROM movie_details")
    suspend fun getAll(): List<MovieDetailsEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(details: MovieDetailsEntity)

    @Update
    suspend fun update(details: MovieDetailsEntity)
}
