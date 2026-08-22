package com.hub.media.features.tv.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.TVDetailsEntity
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

private const val TAG = "TVShowRepository"

/**
 * One season's quick-fill request: "Season [seasonNumber] has [episodeCount] episodes." Backs
 * [TVShowRepository.addShow], which turns a list of these into the generated
 * [EpisodeEntity] rows described on [com.hub.media.core.database.entities.EpisodeEntity]'s KDoc --
 * unknown title/airDate, unwatched, ready to be ticked off individually.
 */
public data class SeasonQuickFill(
    val seasonNumber: Int,
    val episodeCount: Int,
)

/**
 * TV show data operations (ROADMAP Task 13 Phase C) -- the TV counterpart of
 * [com.hub.media.features.movies.data.MovieRepository], deliberately mirroring its shape so the
 * two behave identically where they overlap. The one structural difference is that TV tracks
 * progress per episode rather than per show (see [TVDetailsEntity]'s KDoc), so this repository
 * additionally owns quick-fill (generating [EpisodeEntity] rows) and the per-episode watched tick.
 *
 * Per AGENTS.md §5, every write is wrapped in [Resource] rather than throwing.
 *
 * @param clock Source of "now" for the WATCHED-transition timestamp stamped onto
 *   [EpisodeEntity.watchedAt]. Injected for the same reason
 *   [com.hub.media.features.movies.data.MovieRepository] injects it: so a test can assert on a
 *   deterministic timestamp rather than a wall-clock read.
 */
public class TVShowRepository(
    private val db: AppDatabase,
    private val clock: Clock = Clock.System,
    private val logger: Logger = AppLogger,
) {
    /** Observes every TV show with its details, ordered by title. */
    public fun observeAllShowsWithDetails(): Flow<List<MediaWithDetails.TVShow>> =
        combine(
            db.mediaItemDao().observeByType(MediaType.TV_SHOW),
            db.tvDetailsDao().observeAll(),
        ) { mediaItems, allDetails ->
            val detailsByMediaId = allDetails.associateBy { it.mediaId }
            mediaItems.map { MediaWithDetails.TVShow(item = it, details = detailsByMediaId[it.id]) }
        }

    /**
     * Observes one show with its details. Emits null once [id] is missing, or if [id] resolves to
     * something that is not a show -- the same type gate
     * [com.hub.media.features.movies.data.MovieRepository.observeMovieDetail] applies, so a book or
     * movie id handed to a TV screen yields "not found" rather than a mislabelled row.
     */
    public fun observeShowDetail(id: String): Flow<MediaWithDetails.TVShow?> =
        combine(
            db.mediaItemDao().observeById(id),
            db.tvDetailsDao().observeByMediaId(id),
        ) { mediaItem, details ->
            if (mediaItem != null && mediaItem.type == MediaType.TV_SHOW) {
                MediaWithDetails.TVShow(item = mediaItem, details = details)
            } else {
                null
            }
        }

    /** Observes one show's episodes, ordered by season then episode number. */
    public fun observeEpisodes(mediaId: String): Flow<List<EpisodeEntity>> = db.episodeDao().observeByMediaId(mediaId)

    /**
     * Adds a show, its details, and its quick-filled episodes in one transaction. Manual entry
     * only -- no provider is involved at this phase (TMDB backfill is Phase D), so every field is
     * whatever the user typed, and every generated [EpisodeEntity] has [EpisodeEntity.title] and
     * [EpisodeEntity.airDate] `null` per that entity's "rows exist before their titles do" rule.
     *
     * @param totalSeasons Advisory season count, or null for "unknown" -- see [TVDetailsEntity.totalSeasons].
     * @param seasons The quick-fill request: one [SeasonQuickFill] per season being pre-populated
     *   with episode rows now. May be empty -- a show can be added with no episodes yet and have
     *   seasons quick-filled later via [addSeason].
     * @return [Resource.Success] with the new media id, or [Resource.Error] (never throws).
     */
    public suspend fun addShow(
        title: String,
        releaseYear: Int? = null,
        purchasePrice: Double? = null,
        totalSeasons: Int? = null,
        coverImageHash: String? = null,
        seasons: List<SeasonQuickFill> = emptyList(),
    ): Resource<String> {
        TVMetadataValidation.validateTitle(title)?.let { return Resource.Error(it) }
        TVMetadataValidation.validateReleaseYear(releaseYear)?.let { return Resource.Error(it) }
        TVMetadataValidation.validatePurchasePrice(purchasePrice)?.let { return Resource.Error(it) }
        TVMetadataValidation.validateTotalSeasons(totalSeasons)?.let { return Resource.Error(it) }
        for (season in seasons) {
            TVMetadataValidation.validateSeasonNumber(season.seasonNumber)?.let { return Resource.Error(it) }
            TVMetadataValidation.validateEpisodeCount(season.episodeCount)?.let { return Resource.Error(it) }
        }
        // Caught here rather than left to the unique (mediaId, seasonNumber, episodeNumber) index.
        // The insert would abort and roll back cleanly either way -- nothing is corrupted -- but the
        // user would be told "UNIQUE constraint failed: episodes.mediaId, episodes.seasonNumber,
        // episodes.episodeNumber", which says nothing about the two season rows they typed.
        val seasonNumbers = seasons.map { it.seasonNumber }
        val duplicateSeason = seasonNumbers.firstOrNull { number -> seasonNumbers.count { it == number } > 1 }
        if (duplicateSeason != null) {
            return Resource.Error("Season $duplicateSeason is listed more than once")
        }

        return try {
            val mediaId = newId()
            val now = clock.now()
            val episodes =
                seasons.flatMap { season ->
                    (1..season.episodeCount).map { episodeNumber ->
                        EpisodeEntity(
                            id = newId(),
                            mediaId = mediaId,
                            seasonNumber = season.seasonNumber,
                            episodeNumber = episodeNumber,
                        )
                    }
                }
            db.tvWriteDao().insertShowAtomically(
                item =
                    MediaItemEntity(
                        id = mediaId,
                        type = MediaType.TV_SHOW,
                        title = title,
                        releaseYear = releaseYear,
                        purchasePrice = purchasePrice,
                        createdAt = now,
                        coverImageHash = coverImageHash,
                    ),
                details =
                    TVDetailsEntity(
                        mediaId = mediaId,
                        totalSeasons = totalSeasons,
                        status = WatchStatus.WATCHLIST,
                    ),
                episodes = episodes,
            )
            Resource.Success(mediaId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to add a TV show" }
            Resource.Error("Failed to add TV show: ${e.message ?: "Unknown error"}", cause = e)
        }
    }

    /**
     * Quick-fills a season onto an existing show. Reads the season's existing episodes first and
     * inserts only the episode numbers not already present, so correcting a season from 10 episodes
     * to 12 adds two rows rather than duplicating (or re-creating, which would destroy
     * [EpisodeEntity.watchedAt]) all twelve -- see [EpisodeEntity]'s "rows exist before their titles
     * do" KDoc and the unique `(mediaId, seasonNumber, episodeNumber)` index it documents.
     *
     * @param episodeCount The season's *total* episode count as the user now understands it, not a
     *   number of rows to append. Passing the same value twice is a no-op the second time.
     * @return [Resource.Error] if [mediaId] does not resolve to an existing TV show, or a
     *   validation rule is violated.
     */
    public suspend fun addSeason(
        mediaId: String,
        seasonNumber: Int,
        episodeCount: Int,
    ): Resource<Unit> {
        TVMetadataValidation.validateSeasonNumber(seasonNumber)?.let { return Resource.Error(it) }
        TVMetadataValidation.validateEpisodeCount(episodeCount)?.let { return Resource.Error(it) }

        return try {
            val show = db.mediaItemDao().getById(mediaId)
            if (show == null || show.type != MediaType.TV_SHOW) {
                return Resource.Error("TV show with id=$mediaId not found")
            }
            val existing = db.episodeDao().getByMediaIdAndSeason(mediaId, seasonNumber)
            val existingNumbers = existing.map { it.episodeNumber }.toSet()
            val missing =
                (1..episodeCount)
                    .filterNot { it in existingNumbers }
                    .map { episodeNumber ->
                        EpisodeEntity(
                            id = newId(),
                            mediaId = mediaId,
                            seasonNumber = seasonNumber,
                            episodeNumber = episodeNumber,
                        )
                    }
            if (missing.isNotEmpty()) {
                db.tvWriteDao().insertEpisodes(missing)
            }
            Resource.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to add season $seasonNumber for show: id=$mediaId" }
            Resource.Error("Failed to add season: ${e.message ?: "Unknown error"}", cause = e)
        }
    }

    /**
     * Sets or clears one episode's watched state, stamping [clock] `now()` when [watched] is true
     * and clearing to `null` when false.
     *
     * Re-ticking an already-watched episode must not bump its timestamp, mirroring
     * [com.hub.media.features.movies.data.MovieRepository.resolveWatchedAt]'s rule -- so the
     * existing row is read first, and a prior non-null [EpisodeEntity.watchedAt] is preserved
     * rather than overwritten with a fresh [clock] read.
     *
     * @return [Resource.Error] if [episodeId] does not resolve to an existing episode.
     */
    public suspend fun setEpisodeWatched(
        episodeId: String,
        watched: Boolean,
    ): Resource<Unit> =
        try {
            val existing = db.episodeDao().getById(episodeId)
            val watchedAt =
                when {
                    !watched -> null
                    existing?.watchedAt != null -> existing.watchedAt
                    else -> clock.now()
                }
            val rows = db.tvWriteDao().setEpisodeWatchedAt(episodeId, watchedAt)
            if (rows == 0) Resource.Error("Episode with id=$episodeId not found") else Resource.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to update watched state for episode: id=$episodeId" }
            Resource.Error("Failed to update episode: ${e.message ?: "Unknown error"}", cause = e)
        }

    /**
     * Sets or clears the watched state of every episode in one season — the bulk counterpart of
     * [setEpisodeWatched], for the common case of finishing a season you were never ticking off
     * individually.
     *
     * Marking watched leaves already-watched episodes alone rather than restamping them, so a
     * season you finished months ago does not become "watched today" because you ticked its last
     * episode; see [com.hub.media.core.database.dao.TVWriteDao.markSeasonWatched]. That means the
     * affected-row count cannot distinguish "already fully watched" (0 rows, a success) from "no
     * such season" (0 rows, an error), so the season's existence is established by reading it
     * first rather than inferred from the count.
     *
     * @return [Resource.Error] if that season of that show has no episodes at all.
     */
    public suspend fun setSeasonWatched(
        mediaId: String,
        seasonNumber: Int,
        watched: Boolean,
    ): Resource<Unit> =
        try {
            if (db.episodeDao().getByMediaIdAndSeason(mediaId, seasonNumber).isEmpty()) {
                Resource.Error("Season $seasonNumber of show id=$mediaId has no episodes")
            } else {
                if (watched) {
                    db.tvWriteDao().markSeasonWatched(mediaId, seasonNumber, clock.now())
                } else {
                    db.tvWriteDao().clearSeasonWatched(mediaId, seasonNumber)
                }
                Resource.Success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to bulk-update season $seasonNumber for show: id=$mediaId" }
            Resource.Error("Failed to update season: ${e.message ?: "Unknown error"}", cause = e)
        }

    /**
     * Corrects an existing show's metadata across both tables in one transaction, the same way
     * [com.hub.media.features.movies.data.MovieRepository.updateMovieMetadata] does -- a failure
     * partway through can never leave the two tables individually valid but mutually inconsistent.
     *
     * @return [Resource.Error] if [mediaId] does not exist or a validation rule is violated.
     */
    public suspend fun updateShowMetadata(
        mediaId: String,
        title: String,
        releaseYear: Int? = null,
        purchasePrice: Double? = null,
        totalSeasons: Int? = null,
        status: WatchStatus,
    ): Resource<Unit> {
        TVMetadataValidation.validateTitle(title)?.let { return Resource.Error(it) }
        TVMetadataValidation.validateReleaseYear(releaseYear)?.let { return Resource.Error(it) }
        TVMetadataValidation.validatePurchasePrice(purchasePrice)?.let { return Resource.Error(it) }
        TVMetadataValidation.validateTotalSeasons(totalSeasons)?.let { return Resource.Error(it) }

        return try {
            val rows =
                db.tvWriteDao().updateShowMetadataAtomically(
                    mediaId = mediaId,
                    title = title,
                    releaseYear = releaseYear,
                    purchasePrice = purchasePrice,
                    totalSeasons = totalSeasons,
                    status = status,
                )
            if (rows == 0) Resource.Error("TV show with id=$mediaId not found") else Resource.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to update metadata for show: id=$mediaId" }
            Resource.Error("Failed to update show metadata: ${e.message ?: "Unknown error"}", cause = e)
        }
    }

    /**
     * Changes only a show's [WatchStatus].
     *
     * Separate from [updateShowMetadata] rather than a call into it, because a status tap is not an
     * edit of everything else -- same reasoning as
     * [com.hub.media.features.movies.data.MovieRepository.updateWatchStatus]'s KDoc: re-sending
     * title/year/price/totalSeasons to change one column would put those values back through
     * [TVMetadataValidation], and could no longer change status alone on a row whose stored
     * release year predates [TVMetadataValidation.MIN_RELEASE_YEAR] or is otherwise
     * unreachable through this app's own forms. Writing only the status column also means a status
     * tap can no longer overwrite a title someone edited in between.
     *
     * Unlike the movie equivalent, there is no `watchedAt` to derive here: TV progress is never
     * stored on [TVDetailsEntity] (see its KDoc) -- only the per-episode [EpisodeEntity.watchedAt]
     * columns [setEpisodeWatched] manages are timestamped.
     *
     * @return [Resource.Error] if [mediaId] has no `tv_details` row (deleted, or never a show).
     */
    public suspend fun updateWatchStatus(
        mediaId: String,
        status: WatchStatus,
    ): Resource<Unit> =
        try {
            val rows = db.tvWriteDao().updateStatusFields(mediaId, status)
            if (rows == 0) Resource.Error("TV show with id=$mediaId not found") else Resource.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to update status for show: id=$mediaId" }
            Resource.Error("Failed to update show status: ${e.message ?: "Unknown error"}", cause = e)
        }
}
