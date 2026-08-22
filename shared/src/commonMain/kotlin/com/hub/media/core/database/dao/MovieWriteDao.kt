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

    /**
     * Targeted update of `media_items`' editable columns, scoped to `MOVIE` rows.
     *
     * The `type` predicate is what makes this DAO's affected-row count mean "no such *movie*"
     * rather than merely "no such row". Without it, a book's id reaching
     * [com.hub.media.features.movies.data.MovieRepository.updateMovieMetadata] would overwrite that
     * book's title/releaseYear/purchasePrice with movie-form values and still report success — only
     * the `movie_details` half would miss, and that half's count is deliberately not the one
     * [updateMovieMetadataAtomically] returns.
     *
     * The literal matches `Converters.mediaTypeToName`, which persists a `MediaType` as its
     * `name` — the same spelling Room's own generated binding uses for a typed parameter.
     */
    @Query(
        "UPDATE media_items SET title = :title, releaseYear = :releaseYear, " +
            "purchasePrice = :purchasePrice WHERE id = :mediaId AND type = 'MOVIE'",
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
     * Targeted update of only the watch-status columns, leaving `runtimeMinutes` alone. Backs
     * [com.hub.media.features.movies.data.MovieRepository.updateWatchStatus], which exists so a
     * status tap does not have to re-send every other field just to change this one.
     *
     * No `type` predicate, and none is needed: `movie_details` holds movie rows only, so its
     * affected-row count already means "no such movie" on its own.
     */
    @Query("UPDATE movie_details SET status = :status, watchedAt = :watchedAt WHERE mediaId = :mediaId")
    suspend fun updateWatchStatusFields(
        mediaId: String,
        status: WatchStatus,
        watchedAt: Instant?,
    ): Int

    /**
     * Targeted update of just the editable columns across both tables, in one transaction — the
     * same shape as [BookWriteDao.updateBookMetadataAtomically], and for the same reason: writing a
     * full-row copy back would silently revert a concurrent writer's change to some other column.
     *
     * Self-heals a missing details row rather than reporting a success that wrote half the values.
     * A `media_items` row without its `movie_details` half is the data-integrity edge
     * [com.hub.media.features.media.data.MediaWithDetails.Movie.details] documents as possible;
     * before this, the `UPDATE` there matched nothing, runtime/status/watchedAt went nowhere, and
     * the count this returns still said "updated". Inserting inside the same transaction is what
     * makes the repair atomic with the half that did land.
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
            val detailRows = updateMovieDetailFields(mediaId, runtimeMinutes, status, watchedAt)
            if (detailRows == 0) {
                insertMovieDetails(
                    MovieDetailsEntity(
                        mediaId = mediaId,
                        runtimeMinutes = runtimeMinutes,
                        status = status,
                        watchedAt = watchedAt,
                    ),
                )
            }
        }
        return mediaRows
    }
}
