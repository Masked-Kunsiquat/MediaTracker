package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MovieDetailsEntity
import com.hub.media.core.database.entities.WatchStatus
import kotlin.time.Instant

/**
 * Multi-table movie writes that must be atomic (ROADMAP Task 13 Phase B) — the movie counterpart of
 * [BookWriteDao].
 *
 * Every insert uses [OnConflictStrategy.ABORT] so a constraint violation throws rather than being
 * silently replaced, which is what lets [insertMovieAtomically] roll the whole operation back.
 */
@Dao
interface MovieWriteDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMediaItem(item: MediaItemEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMovieDetails(details: MovieDetailsEntity)

    /**
     * Inserts a movie's [MediaItemEntity] and [MovieDetailsEntity] in one transaction. If either
     * fails, neither row remains — a movie that exists in the library but has no details row (or
     * the reverse) is not a state any caller should have to handle.
     */
    @Transaction
    suspend fun insertMovieAtomically(
        item: MediaItemEntity,
        details: MovieDetailsEntity,
    ) {
        insertMediaItem(item)
        insertMovieDetails(details)
    }

    @Query(
        "UPDATE media_items SET title = :title, releaseYear = :releaseYear, " +
            "purchasePrice = :purchasePrice WHERE id = :mediaId",
    )
    suspend fun updateMediaItemFields(
        mediaId: String,
        title: String,
        releaseYear: Int?,
        purchasePrice: Double?,
    ): Int

    @Query(
        "UPDATE movie_details SET runtimeMinutes = :runtimeMinutes, status = :status, " +
            "watchedAt = :watchedAt WHERE mediaId = :mediaId",
    )
    suspend fun updateMovieDetailFields(
        mediaId: String,
        runtimeMinutes: Int?,
        status: WatchStatus,
        watchedAt: Instant?,
    ): Int

    /**
     * Targeted update of just the editable columns across both tables, in one transaction — the
     * same shape as [BookWriteDao.updateBookMetadataAtomically], and for the same reason: writing a
     * full-row copy back would silently revert a concurrent writer's change to some other column.
     *
     * @return the number of `media_items` rows affected, so a caller can tell "no such movie" (0)
     *   from a successful update.
     */
    @Transaction
    suspend fun updateMovieMetadataAtomically(
        mediaId: String,
        title: String,
        releaseYear: Int?,
        purchasePrice: Double?,
        runtimeMinutes: Int?,
        status: WatchStatus,
        watchedAt: Instant?,
    ): Int {
        val mediaRows = updateMediaItemFields(mediaId, title, releaseYear, purchasePrice)
        if (mediaRows > 0) {
            updateMovieDetailFields(mediaId, runtimeMinutes, status, watchedAt)
        }
        return mediaRows
    }
}
