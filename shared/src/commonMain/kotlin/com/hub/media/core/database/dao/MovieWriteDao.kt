package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MovieDetailsEntity
import com.hub.media.core.database.entities.WatchLogEntity
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.util.newId
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
     * Inserts one provider mapping for a movie, mirroring [TVWriteDao.insertExternalIdentifier] and
     * [BookWriteDao.insertExternalIdentifier] — ABORT for the same reason: a duplicate
     * `(mediaId, provider)` inside a single add is a caller bug, and rolling the whole insert back
     * is the right answer to it.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExternalIdentifier(identifier: ExternalIdentifierEntity)

    /**
     * Inserts a movie's [MediaItemEntity], [MovieDetailsEntity] and any [ExternalIdentifierEntity]
     * mappings in one transaction. If any part fails, none of it remains — a movie that exists in
     * the library but has no details row (or the reverse) is not a state any caller should have to
     * handle.
     *
     * [externalIdentifiers] joins the transaction rather than being written afterwards, for the
     * reason [TVWriteDao.insertShowAtomically] gives at length: the mapping is what tells a
     * looked-up row apart from a hand-typed one, and a process death between two separate writes
     * produces exactly the untraceable row it exists to prevent. A film's stakes are lower than a
     * show's — nothing backfills episode titles onto it — but re-fetching its poster or runtime
     * still needs to know which TMDB record it came from.
     */
    @Transaction
    suspend fun insertMovieAtomically(
        item: MediaItemEntity,
        details: MovieDetailsEntity,
        externalIdentifiers: List<ExternalIdentifierEntity>,
    ) {
        insertMediaItem(item)
        insertMovieDetails(details)
        externalIdentifiers.forEach { insertExternalIdentifier(it) }
        // Added as already watched (#192): that watch is the film's first viewing.
        val watchedAt = details.watchedAt
        if (details.status == WatchStatus.WATCHED && watchedAt != null) insertViewing(filmViewing(item.id, watchedAt))
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
     * Fills a film's provider metadata **onto columns that are still null**, scoped to `MOVIE` rows.
     *
     * The film half of the rule [TVWriteDao.fillEpisodeMetadata] states for episodes, and enforced
     * the same way: every column is wrapped in `COALESCE(column, :value)`, so a value already
     * present wins and a `null` argument changes nothing. A year the user corrected by hand survives
     * a later pass, and no future edit to a caller can make this statement overwrite one.
     *
     * `title` is deliberately absent from the SET list even though TMDB supplies one. It is `NOT
     * NULL`, so `COALESCE` could never fire on it — including it would be a line that reads like a
     * fill and can only ever be a no-op, which is worse than not having it.
     *
     * @return rows affected: `1` when the film exists, `0` when [mediaId] is not a `MOVIE` row.
     */
    @Query(
        "UPDATE media_items SET " +
            "releaseYear = COALESCE(releaseYear, :releaseYear), " +
            "communityRating = COALESCE(communityRating, :communityRating), " +
            "synopsis = COALESCE(synopsis, :synopsis) " +
            "WHERE id = :mediaId AND type = 'MOVIE'",
    )
    suspend fun fillMediaItemMetadata(
        mediaId: String,
        releaseYear: Int?,
        communityRating: Double?,
        synopsis: String?,
    ): Int

    /**
     * The `movie_details` half of [fillMovieMetadata]. No `type` predicate, and none is needed:
     * `movie_details` holds movie rows only — the same reasoning [updateWatchStatusFields] gives.
     *
     * Neither `status` nor `watchedAt` is in the SET list, and both are absent rather than guarded.
     * That is the property #75 settled and this inherits: **a background pass can never alter what
     * your library says you have watched.**
     */
    @Query(
        "UPDATE movie_details SET runtimeMinutes = COALESCE(runtimeMinutes, :runtimeMinutes) WHERE mediaId = :mediaId",
    )
    suspend fun fillMovieDetailMetadata(
        mediaId: String,
        runtimeMinutes: Int?,
    ): Int

    /**
     * Applies both halves of one film's fill in a single transaction.
     *
     * ### A missing `movie_details` row is left missing
     * [updateMovieMetadataAtomically] self-heals that case by inserting one; this deliberately does
     * not. Enrichment fills `null` columns and never creates rows (#75, and #140 inherits the
     * guarantee rather than reopening it) — and a details row cannot be created without inventing a
     * [WatchStatus] for it, which is exactly the kind of value a background pass must not invent. The
     * film simply stays a candidate; the user's own next edit repairs it through the path that is
     * allowed to.
     *
     * @return the number of `media_items` rows affected, so a caller can tell "no such film" (0)
     *   from a fill that landed.
     */
    @Transaction
    suspend fun fillMovieMetadata(
        mediaId: String,
        releaseYear: Int?,
        communityRating: Double?,
        synopsis: String?,
        runtimeMinutes: Int?,
    ): Int {
        val mediaRows = fillMediaItemMetadata(mediaId, releaseYear, communityRating, synopsis)
        if (mediaRows > 0) {
            fillMovieDetailMetadata(mediaId, runtimeMinutes)
        }
        return mediaRows
    }

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

    // ---- Film viewings (#192) -------------------------------------------------------------------
    // A film viewing is a `watch_logs` row with no episode. Every write below ends in
    // [recomputeFilmWatchedAt], so a Watched film's `watchedAt` is always its latest viewing and any
    // other status has none -- while the viewings themselves outlive a status change.

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertViewing(log: WatchLogEntity)

    @Query("SELECT * FROM watch_logs WHERE id = :logId")
    suspend fun viewingById(logId: String): WatchLogEntity?

    @Query("DELETE FROM watch_logs WHERE id = :logId")
    suspend fun deleteViewingRow(logId: String): Int

    @Query("UPDATE watch_logs SET watchedAt = :at WHERE id = :logId")
    suspend fun setViewingDate(
        logId: String,
        at: Instant,
    ): Int

    @Query("SELECT COUNT(*) FROM watch_logs WHERE mediaId = :mediaId AND episodeId IS NULL")
    suspend fun filmViewingCount(mediaId: String): Int

    @Query("SELECT status FROM movie_details WHERE mediaId = :mediaId")
    suspend fun statusOf(mediaId: String): WatchStatus?

    @Query("UPDATE movie_details SET status = :status WHERE mediaId = :mediaId")
    suspend fun setStatus(
        mediaId: String,
        status: WatchStatus,
    ): Int

    /** The one derivation: a Watched film's `watchedAt` is its latest viewing; otherwise none. */
    @Query(
        "UPDATE movie_details SET watchedAt = CASE WHEN status = 'WATCHED' THEN " +
            "(SELECT MAX(watchedAt) FROM watch_logs WHERE mediaId = :mediaId AND episodeId IS NULL) " +
            "ELSE NULL END WHERE mediaId = :mediaId",
    )
    suspend fun recomputeFilmWatchedAt(mediaId: String): Int

    /**
     * Changes a film's status. Only a move *into* Watched records a viewing, at [at]; staying Watched
     * adds nothing, and leaving it keeps the history.
     *
     * @return The previous status, or `null` when there is no such film.
     */
    @Transaction
    suspend fun changeStatus(
        mediaId: String,
        status: WatchStatus,
        at: Instant,
    ): WatchStatus? {
        val previous = statusOf(mediaId) ?: return null
        setStatus(mediaId, status)
        if (status == WatchStatus.WATCHED && previous != WatchStatus.WATCHED) insertViewing(filmViewing(mediaId, at))
        recomputeFilmWatchedAt(mediaId)
        return previous
    }

    /** "Watched again": one more viewing. Only for a Watched film; anything else is a status change. */
    @Transaction
    suspend fun addFilmViewing(
        mediaId: String,
        at: Instant,
    ): Boolean {
        if (statusOf(mediaId) != WatchStatus.WATCHED) return false
        insertViewing(filmViewing(mediaId, at))
        recomputeFilmWatchedAt(mediaId)
        return true
    }

    /**
     * Deletes one film viewing, returning it and the status before, for Undo. Deleting a Watched
     * film's last viewing moves it to Watchlist, the way an episode un-ticks when its last one goes.
     */
    @Transaction
    suspend fun deleteFilmViewing(logId: String): FilmViewingDeletion? {
        val log = viewingById(logId)?.takeIf { it.episodeId == null } ?: return null
        val before = statusOf(log.mediaId) ?: return null
        deleteViewingRow(logId)
        if (before == WatchStatus.WATCHED && filmViewingCount(log.mediaId) == 0) {
            setStatus(log.mediaId, WatchStatus.WATCHLIST)
        }
        recomputeFilmWatchedAt(log.mediaId)
        return FilmViewingDeletion(log, before)
    }

    @Transaction
    suspend fun updateFilmViewingDate(
        logId: String,
        at: Instant,
    ): Boolean {
        val log = viewingById(logId)?.takeIf { it.episodeId == null } ?: return false
        setViewingDate(logId, at)
        recomputeFilmWatchedAt(log.mediaId)
        return true
    }

    /**
     * Undo for [deleteFilmViewing]. A no-op when the film is gone -- re-inserting would violate the
     * foreign key. Puts Watched back only if the delete is what moved the film to Watchlist, so a
     * status the user chose since wins.
     */
    @Transaction
    suspend fun restoreFilmViewings(
        logs: List<WatchLogEntity>,
        statusBefore: WatchStatus,
    ): Boolean {
        val mediaId = logs.firstOrNull()?.mediaId ?: return false
        val current = statusOf(mediaId) ?: return false
        logs.filter { viewingById(it.id) == null }.forEach { insertViewing(it) }
        if (statusBefore == WatchStatus.WATCHED && current == WatchStatus.WATCHLIST) {
            setStatus(mediaId, WatchStatus.WATCHED)
        }
        recomputeFilmWatchedAt(mediaId)
        return true
    }

    /**
     * Undo for leaving Watched: the status comes back and, unlike [changeStatus], adds no viewing.
     * Only while the film is still at [expectedCurrent] -- a status chosen since wins -- and never
     * back to Watched once no viewing is left, which would be a Watched film with no date.
     */
    @Transaction
    suspend fun restoreFilmStatus(
        mediaId: String,
        status: WatchStatus,
        expectedCurrent: WatchStatus,
    ): Boolean {
        if (statusOf(mediaId) != expectedCurrent) return false
        if (status == WatchStatus.WATCHED && filmViewingCount(mediaId) == 0) return false
        setStatus(mediaId, status)
        recomputeFilmWatchedAt(mediaId)
        return true
    }

    @Query(
        "SELECT * FROM movie_details WHERE status = 'WATCHED' AND watchedAt IS NOT NULL AND NOT EXISTS " +
            "(SELECT 1 FROM watch_logs w WHERE w.mediaId = movie_details.mediaId AND w.episodeId IS NULL)",
    )
    suspend fun watchedFilmsWithoutViewings(): List<MovieDetailsEntity>

    /** The film half of the startup backfill: one viewing, at `watchedAt`, for each Watched film with none. */
    @Transaction
    suspend fun ensureViewingsForWatchedFilms(): Int =
        watchedFilmsWithoutViewings()
            .onEach { insertViewing(filmViewing(it.mediaId, checkNotNull(it.watchedAt))) }
            .size

    /**
     * The Edit form's save, with [changeStatus]'s rule: a move into Watched records a viewing at [at];
     * re-saving an already-Watched film adds nothing and keeps its date.
     */
    @Transaction
    suspend fun updateMovieMetadataWithViewing(
        mediaId: String,
        title: String,
        releaseYear: Int?,
        purchasePrice: Double?,
        runtimeMinutes: Int?,
        status: WatchStatus,
        at: Instant,
    ): Int {
        val before = statusOf(mediaId)
        val rows =
            updateMovieMetadataAtomically(
                mediaId = mediaId,
                title = title,
                releaseYear = releaseYear,
                purchasePrice = purchasePrice,
                runtimeMinutes = runtimeMinutes,
                status = status,
                watchedAt = null,
            )
        if (rows == 0) return 0
        if (status == WatchStatus.WATCHED && before != WatchStatus.WATCHED) insertViewing(filmViewing(mediaId, at))
        recomputeFilmWatchedAt(mediaId)
        return rows
    }
}

/** What [MovieWriteDao.deleteFilmViewing] removed, and the status before it, so Undo can restore both. */
public data class FilmViewingDeletion(
    val log: WatchLogEntity,
    val statusBefore: WatchStatus,
)

private fun filmViewing(
    mediaId: String,
    at: Instant,
) = WatchLogEntity(id = newId(), mediaId = mediaId, episodeId = null, watchedAt = at)
