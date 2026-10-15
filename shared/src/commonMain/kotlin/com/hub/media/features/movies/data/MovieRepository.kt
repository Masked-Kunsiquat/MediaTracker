package com.hub.media.features.movies.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.dao.FilmViewingDeletion
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.MovieDetailsEntity
import com.hub.media.core.database.entities.WatchLogEntity
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.guardResource
import com.hub.media.core.util.newId
import com.hub.media.features.media.data.MediaWithDetails
import com.hub.media.features.tv.domain.WatchedWhen
import com.hub.media.features.tv.domain.resolveWatchedAt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.TimeZone
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
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
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
     * The id of the film already recorded as coming from [externalId] at [provider], or `null`.
     *
     * Backs the add-by-search duplicate check, and is scoped to `MOVIE` rows by
     * [com.hub.media.core.database.dao.ExternalIdentifierDao.findMediaIdByExternalId] -- see that
     * query on why a show sharing the number must not answer here.
     */
    public suspend fun findMovieIdByExternalId(
        provider: IdentifierProvider,
        externalId: String,
    ): String? = db.externalIdentifierDao().findMediaIdByExternalId(provider, externalId, MediaType.MOVIE)

    /**
     * Adds a movie, its details, and any provider mappings in one transaction.
     *
     * Values arrive fully formed from whoever is calling — this validates and writes them, it does
     * not fetch. Manual entry passes what the user typed; an add-by-search path (ROADMAP Task 13
     * Phase D) passes what it read from [com.hub.media.features.tv.network.TmdbClient] plus the
     * film's TMDB id in [externalIdentifiers].
     *
     * @param runtimeMinutes Length in minutes, or null for "unknown". Never 0 as a stand-in.
     * @param communityRating The provider's aggregate score for the film, already normalised to
     *   0-10, or null. **Not** the user's own rating -- see
     *   [com.hub.media.core.database.entities.MediaItemEntity.communityRating], which keeps that
     *   for ROADMAP Task 10.
     * @param synopsis The film's synopsis from a provider, or null. Blank is stored as null, the
     *   same judgement [com.hub.media.features.tv.data.TVShowRepository.addShow] makes: a provider
     *   answering with an empty string does not know a synopsis.
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
        communityRating: Double? = null,
        synopsis: String? = null,
        watchedWhen: WatchedWhen? = null,
    ): Resource<String> {
        MovieMetadataValidation.validateTitle(title)?.let { return Resource.Error(it) }
        MovieMetadataValidation.validateReleaseYear(releaseYear)?.let { return Resource.Error(it) }
        MovieMetadataValidation.validatePurchasePrice(purchasePrice)?.let { return Resource.Error(it) }
        MovieMetadataValidation.validateRuntimeMinutes(runtimeMinutes)?.let { return Resource.Error(it) }
        MovieMetadataValidation.validateCommunityRating(communityRating)?.let { return Resource.Error(it) }

        return logger.guardResource(
            tag = TAG,
            failure = "Failed to add movie",
            logMessage = { "Failed to add a movie" },
        ) {
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
                        communityRating = communityRating,
                        // Blank becomes null: a provider answering with "" does not know a synopsis.
                        synopsis = synopsis?.takeIf { it.isNotBlank() },
                    ),
                details =
                    MovieDetailsEntity(
                        mediaId = mediaId,
                        runtimeMinutes = runtimeMinutes,
                        status = status,
                        // A movie added as already watched gets the day the form's prompt chose (#192),
                        // Now by default; insertMovieAtomically records it as the first viewing.
                        watchedAt = if (status == WatchStatus.WATCHED) instantFor(watchedWhen) else null,
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
        watchedWhen: WatchedWhen? = null,
    ): Resource<Unit> {
        MovieMetadataValidation.validateTitle(title)?.let { return Resource.Error(it) }
        MovieMetadataValidation.validateReleaseYear(releaseYear)?.let { return Resource.Error(it) }
        MovieMetadataValidation.validatePurchasePrice(purchasePrice)?.let { return Resource.Error(it) }
        MovieMetadataValidation.validateRuntimeMinutes(runtimeMinutes)?.let { return Resource.Error(it) }

        return logger.guardResource(
            tag = TAG,
            failure = "Failed to update movie metadata",
            logMessage = { "Failed to update metadata for movie: id=$mediaId" },
        ) {
            val rows =
                db.movieWriteDao().updateMovieMetadataWithViewing(
                    mediaId = mediaId,
                    title = title,
                    releaseYear = releaseYear,
                    purchasePrice = purchasePrice,
                    runtimeMinutes = runtimeMinutes,
                    status = status,
                    at = instantFor(watchedWhen),
                )
            if (rows == 0) Resource.Error("Movie with id=$mediaId not found") else Resource.Success(Unit)
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
     * A move into Watched records a viewing at [watchedWhen] (Now when omitted); leaving Watched
     * keeps the viewings (#192). See [com.hub.media.core.database.dao.MovieWriteDao.changeStatus].
     *
     * @return The status **before** the change, so a caller can offer Undo; [Resource.Error] if
     *   [mediaId] has no `movie_details` row (deleted, or never a movie).
     */
    public suspend fun updateWatchStatus(
        mediaId: String,
        status: WatchStatus,
        watchedWhen: WatchedWhen? = null,
    ): Resource<WatchStatus> =
        logger.guardResource(
            tag = TAG,
            failure = "Failed to update movie status",
            logMessage = { "Failed to update status for movie: id=$mediaId" },
        ) {
            val previous = db.movieWriteDao().changeStatus(mediaId, status, instantFor(watchedWhen))
            if (previous == null) Resource.Error("Movie with id=$mediaId not found") else Resource.Success(previous)
        }

    // ---- Watch history (#192) -------------------------------------------------------------------

    /** A film's viewings, newest first. */
    public fun observeViewings(mediaId: String): Flow<List<WatchLogEntity>> = db.watchLogDao().observeForFilm(mediaId)

    /** "Watched again": one more viewing of a Watched film. */
    public suspend fun addViewing(
        mediaId: String,
        watchedWhen: WatchedWhen,
    ): Resource<Unit> =
        write("record a viewing of movie id=$mediaId") {
            if (db.movieWriteDao().addFilmViewing(mediaId, instantFor(watchedWhen))) {
                Resource.Success(Unit)
            } else {
                Resource.Error("Movie with id=$mediaId not found, or not watched")
            }
        }

    /** Deletes one viewing; the result carries what Undo needs. */
    public suspend fun deleteViewing(logId: String): Resource<FilmViewingDeletion> =
        write("delete viewing id=$logId") {
            db.movieWriteDao().deleteFilmViewing(logId)?.let { Resource.Success(it) }
                ?: Resource.Error("Viewing not found")
        }

    /** Undo for [deleteViewing]; quietly does nothing for a film deleted since. */
    public suspend fun restoreViewings(
        logs: List<WatchLogEntity>,
        demoted: Boolean,
    ): Resource<Unit> =
        write("restore ${logs.size} viewing(s)") {
            db.movieWriteDao().restoreFilmViewings(logs, demoted)
            Resource.Success(Unit)
        }

    /**
     * Undo for leaving Watched: puts [status] back without recording a new viewing, provided the film
     * is still at [expectedCurrent] and, for Watched, still has a viewing.
     */
    public suspend fun restoreStatus(
        mediaId: String,
        status: WatchStatus,
        expectedCurrent: WatchStatus,
    ): Resource<Unit> =
        write("restore status of movie id=$mediaId") {
            if (db.movieWriteDao().restoreFilmStatus(mediaId, status, expectedCurrent)) {
                Resource.Success(Unit)
            } else {
                Resource.Error("Couldn't undo: the film changed since")
            }
        }

    /** Corrects one viewing's day. */
    public suspend fun editViewingDate(
        logId: String,
        watchedWhen: WatchedWhen,
    ): Resource<Unit> =
        write("edit viewing id=$logId") {
            if (db.movieWriteDao().updateFilmViewingDate(logId, instantFor(watchedWhen))) {
                Resource.Success(Unit)
            } else {
                Resource.Error("Viewing not found")
            }
        }

    /**
     * The instant a film was watched: Now, or a picked day at noon local, clamped to now -- the same
     * rule as an episode's (#131). Films store no release date, so `OnAirDate` falls back to now.
     */
    private fun instantFor(watchedWhen: WatchedWhen?): Instant {
        val now = clock.now()
        return resolveWatchedAt(watchedWhen ?: WatchedWhen.Now, airDate = null, now = now, zone = zone) ?: now
    }

    private inline fun <T> write(
        what: String,
        block: () -> Resource<T>,
    ): Resource<T> =
        logger.guardResource(
            tag = TAG,
            failure = "Failed to $what",
            logMessage = { "Failed to $what" },
        ) {
            block()
        }
}
