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

    @Query("SELECT * FROM media_items WHERE id = :id")
    suspend fun getById(id: String): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE id = :id")
    fun observeById(id: String): Flow<MediaItemEntity?>

    @Query("SELECT * FROM media_items ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE type = :type ORDER BY title ASC")
    fun observeByType(type: MediaType): Flow<List<MediaItemEntity>>
}
