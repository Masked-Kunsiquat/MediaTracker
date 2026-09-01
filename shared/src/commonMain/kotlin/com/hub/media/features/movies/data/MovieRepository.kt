package com.hub.media.features.movies.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.MovieDetailsEntity
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.error
import com.hub.media.core.util.newId
import com.hub.media.features.media.data.MediaWithDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

private const val TAG = "MovieRepository"

/**
 * Movie data operations (ROADMAP Task 13 Phase B) — the movie counterpart of
 * [com.hub.media.features.books.data.BookRepository], deliberately mirroring its shape so the two
 * behave identically where they overlap.
 *
 * Per AGENTS.md §5, every write is wrapped in [Resource] rather than throwing.
 *
 * @param clock Source of "now" for [resolveWatchedAt]'s WATCHED-transition timestamp. Injected for
 *   the same reason [com.hub.media.features.books.data.BookRepository] injects it: so a test can
 *   assert on a deterministic timestamp rather than a wall-clock read.
 */
public class MovieRepository(
    private val db: AppDatabase,
    private val clock: Clock = Clock.System,
    private val logger: Logger = AppLogger,
) {
    /** Observes every movie with its details, ordered by title. */
    public fun observeAllMoviesWithDetails(): Flow<List<MediaWithDetails.Movie>> =
        combine(
            db.mediaItemDao().observeByType(MediaType.MOVIE),
            db.movieDetailsDao().observeAll(),
        ) { mediaItems, allDetails ->
            val detailsByMediaId = allDetails.associateBy { it.mediaId }
            mediaItems.map { MediaWithDetails.Movie(item = it, details = detailsByMediaId[it.id]) }
        }

    /**
     * Observes one movie with its details. Emits null once [id] is missing, or if [id] resolves to
     * something that is not a movie — the same type gate
     * [com.hub.media.features.books.data.BookRepository.observeBookDetail] applies, so a book id
     * handed to a movie screen yields "not found" rather than a mislabelled row.
     */
    public fun observeMovieDetail(id: String): Flow<MediaWithDetails.Movie?> =
        combine(
            db.mediaItemDao().observeById(id),
            db.movieDetailsDao().observeByMediaId(id),
        ) { mediaItem, details ->
            if (mediaItem != null && mediaItem.type == MediaType.MOVIE) {
                MediaWithDetails.Movie(item = mediaItem, details = details)
            } else {
                null
            }
        }

    /**
     * Adds a movie, its details, and any provider mappings in one transaction.
     *
     * Values arrive fully formed from whoever is calling — this validates and writes them, it does
     * not fetch. Manual entry passes what the user typed; an add-by-search path (ROADMAP Task 13
     * Phase D) passes what it read from [com.hub.media.features.tv.network.TmdbClient] plus the
     * film's TMDB id in [externalIdentifiers].
     *
     * @param runtimeMinutes Length in minutes, or null for "unknown". Never 0 as a stand-in.
     * @param externalIdentifiers Optional (provider, externalId) mappings recording which catalog
     *   record this row came from — normally a single [IdentifierProvider.TMDB] pair carrying the
     *   film id as its decimal string. Defaults to empty, which is a hand-entered film: correct, and
     *   distinguishable from one added by search precisely because it holds no mapping.
     *
     *   Not validated here, and deliberately so. The composite `(mediaId, provider)` primary key
     *   already rejects a duplicate provider under ABORT and rolls the whole insert back with it,
     *   and a repeated provider can only come from a caller assembling this list wrongly, never from
     *   something a user typed. Duplicating [com.hub.media.features.books.data.BookRepository.addBook]
     *   and [com.hub.media.features.tv.data.TVShowRepository.addShow]'s handling of the same
     *   parameter, rather than inventing a third rule for it.
     * @return [Resource.Success] with the new media id, or [Resource.Error] (never throws).
     */
    public suspend fun addMovie(
        title: String,
        releaseYear: Int? = null,
        purchasePrice: Double? = null,
        runtimeMinutes: Int? = null,
        status: WatchStatus = WatchStatus.WATCHLIST,
        coverImageHash: String? = null,
        externalIdentifiers: List<Pair<IdentifierProvider, String>> = emptyList(),
    ): Resource<String> {
        MovieMetadataValidation.validateTitle(title)?.let { return Resource.Error(it) }
        MovieMetadataValidation.validateReleaseYear(releaseYear)?.let { return Resource.Error(it) }
        MovieMetadataValidation.validatePurchasePrice(purchasePrice)?.let { return Resource.Error(it) }
        MovieMetadataValidation.validateRuntimeMinutes(runtimeMinutes)?.let { return Resource.Error(it) }

        return try {
            val mediaId = newId()
            val now = clock.now()
            db.movieWriteDao().insertMovieAtomically(
                item =
                    MediaItemEntity(
                        id = mediaId,
                        type = MediaType.MOVIE,
                        title = title,
                        releaseYear = releaseYear,
                        purchasePrice = purchasePrice,
                        createdAt = now,
                        coverImageHash = coverImageHash,
                    ),
                details =
                    MovieDetailsEntity(
                        mediaId = mediaId,
                        runtimeMinutes = runtimeMinutes,
                        status = status,
                        // A movie added as already-watched is watched as of now; there is no other
                        // date to claim, and leaving it null would lose the fact entirely.
                        watchedAt = if (status == WatchStatus.WATCHED) now else null,
                    ),
                externalIdentifiers =
                    externalIdentifiers.map { (provider, externalId) ->
                        ExternalIdentifierEntity(
                            mediaId = mediaId,
                            provider = provider,
                            externalId = externalId,
                        )
                    },
            )
            Resource.Success(mediaId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to add a movie" }
            Resource.Error("Failed to add movie: ${e.message ?: "Unknown error"}", cause = e)
        }
    }

    /**
     * Corrects an existing movie's metadata across both tables in one transaction, the same way
     * [com.hub.media.features.books.data.BookRepository.updateBookMetadata] does — a failure
     * partway through can never leave the two tables individually valid but mutually inconsistent.
     *
     * @return [Resource.Error] if [mediaId] does not exist or a validation rule is violated.
     */
    public suspend fun updateMovieMetadata(
        mediaId: String,
        title: String,
        releaseYear: Int? = null,
        purchasePrice: Double? = null,
        runtimeMinutes: Int? = null,
        status: WatchStatus,
    ): Resource<Unit> {
        MovieMetadataValidation.validateTitle(title)?.let { return Resource.Error(it) }
        MovieMetadataValidation.validateReleaseYear(releaseYear)?.let { return Resource.Error(it) }
        MovieMetadataValidation.validatePurchasePrice(purchasePrice)?.let { return Resource.Error(it) }
        MovieMetadataValidation.validateRuntimeMinutes(runtimeMinutes)?.let { return Resource.Error(it) }

        return try {
            val existing = db.movieDetailsDao().getByMediaId(mediaId)
            val watchedAt =
                resolveWatchedAt(
                    newStatus = status,
                    oldStatus = existing?.status ?: WatchStatus.WATCHLIST,
                    oldWatchedAt = existing?.watchedAt,
                    clock = clock,
                )
            val rows =
                db.movieWriteDao().updateMovieMetadataAtomically(
                    mediaId = mediaId,
                    title = title,
                    releaseYear = releaseYear,
                    purchasePrice = purchasePrice,
                    runtimeMinutes = runtimeMinutes,
                    status = status,
                    watchedAt = watchedAt,
                )
            if (rows == 0) Resource.Error("Movie with id=$mediaId not found") else Resource.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to update metadata for movie: id=$mediaId" }
            Resource.Error("Failed to update movie metadata: ${e.message ?: "Unknown error"}", cause = e)
        }
    }

    /**
     * Changes only a movie's [WatchStatus], and the [MovieDetailsEntity.watchedAt] that follows
     * from it.
     *
     * Separate from [updateMovieMetadata] rather than a call into it, because a status tap is not
     * an edit of everything else. Re-sending title/year/price/runtime to change one column put
     * those values back through [MovieMetadataValidation], so a row whose stored release year is
     * outside [MovieMetadataValidation.MIN_RELEASE_YEAR]..[MovieMetadataValidation.MAX_RELEASE_YEAR]
     * — not reachable through this app's own forms, but reachable by a row that arrived some other
     * way — could not have its status changed at all, and failed with a complaint about a field the
     * user had not touched. Writing only the two columns that actually change also means a status
     * tap can no longer overwrite a title someone edited in between.
     *
     * @return [Resource.Error] if [mediaId] has no `movie_details` row (deleted, or never a movie).
     */
    public suspend fun updateWatchStatus(
        mediaId: String,
        status: WatchStatus,
    ): Resource<Unit> =
        try {
            val existing = db.movieDetailsDao().getByMediaId(mediaId)
            val watchedAt =
                resolveWatchedAt(
                    newStatus = status,
                    oldStatus = existing?.status ?: WatchStatus.WATCHLIST,
                    oldWatchedAt = existing?.watchedAt,
                    clock = clock,
                )
            val rows = db.movieWriteDao().updateWatchStatusFields(mediaId, status, watchedAt)
            if (rows == 0) Resource.Error("Movie with id=$mediaId not found") else Resource.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to update status for movie: id=$mediaId" }
            Resource.Error("Failed to update movie status: ${e.message ?: "Unknown error"}", cause = e)
        }

    public companion object {
        /**
         * Derives [MovieDetailsEntity.watchedAt] for a [WatchStatus] transition. Identical in shape
         * to [com.hub.media.features.books.data.BookRepository.resolveFinishedAt], and for the same
         * reasons:
         * - Moving to anything other than [WatchStatus.WATCHED] clears it.
         * - Staying [WatchStatus.WATCHED] preserves the original date, so re-saving an unrelated
         *   field never silently rewrites when the film was actually watched.
         * - Transitioning *into* [WatchStatus.WATCHED] stamps now.
         */
        internal fun resolveWatchedAt(
            newStatus: WatchStatus,
            oldStatus: WatchStatus,
            oldWatchedAt: Instant?,
            clock: Clock,
        ): Instant? =
            when {
                newStatus != WatchStatus.WATCHED -> null
                oldStatus == WatchStatus.WATCHED && oldWatchedAt != null -> oldWatchedAt
                else -> clock.now()
            }
    }
}
