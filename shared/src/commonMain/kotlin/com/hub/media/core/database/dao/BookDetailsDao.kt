package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.hub.media.core.database.entities.BookDetailsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDetailsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(details: BookDetailsEntity)

    @Update
    suspend fun update(details: BookDetailsEntity)

    @Delete
    suspend fun delete(details: BookDetailsEntity)

    @Query("DELETE FROM book_details WHERE mediaId = :mediaId")
    suspend fun deleteByMediaId(mediaId: String)

    @Query("SELECT * FROM book_details WHERE mediaId = :mediaId")
    suspend fun getByMediaId(mediaId: String): BookDetailsEntity?

    @Query("SELECT * FROM book_details WHERE mediaId = :mediaId")
    fun observeByMediaId(mediaId: String): Flow<BookDetailsEntity?>

    @Query("SELECT * FROM book_details")
    fun observeAll(): Flow<List<BookDetailsEntity>>

    /**
     * One-shot (non-reactive) counterpart of [observeAll] (ROADMAP Task 14 Phase A) -- see
     * [MediaItemDao.getAllByType]'s KDoc for why a library-wide scan needs a one-shot read rather
     * than a [Flow] subscription.
     */
    @Query("SELECT * FROM book_details")
    suspend fun getAll(): List<BookDetailsEntity>
}
