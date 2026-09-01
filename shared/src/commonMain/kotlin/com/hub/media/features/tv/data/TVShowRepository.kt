package com.hub.media.features.tv.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.TVDetailsEntity
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.error
import com.hub.media.core.util.newId
import com.hub.media.core.util.warn
import com.hub.media.features.media.data.MediaWithDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

private const val TAG = "TVShowRepository"

/**
 * One season being created as part of [TVShowRepository.addShow], in whichever of the two forms the
 * caller actually has.
 *
 * The two exist because the app populates a season from two genuinely different inputs, and #75
 * settled that they are different operations rather than one with a flag. A person typing into a
 * form knows only how many episodes there were ([SeasonQuickFill]); a provider hands over the
 * episodes themselves ([SeasonEpisodes]). Modelling that as one type with an optional list would
 * make `episodeCount = 10` and a list of three representable at once, which is a state nothing
 * downstream could resolve.
 *
 * Sealed rather than two parameters on [TVShowRepository.addShow] for the same reason: two lists
 * could both name season 1, and a single list makes a repeated season the ordinary duplicate check
 * it already was.
 */
public sealed interface NewSeason {
    /** Which season this is. `0` means specials -- see [TVMetadataValidation.validateSeasonNumber]. */
    public val seasonNumber: Int
}

/**
 * One season's quick-fill request: "Season [seasonNumber] has [episodeCount] episodes." Backs
 * [TVShowRepository.addShow], which turns a list of these into the generated
 * [EpisodeEntity] rows described on [com.hub.media.core.database.entities.EpisodeEntity]'s KDoc --
 * unknown title/airDate, unwatched, ready to be ticked off individually.
 */
public data class SeasonQuickFill(
    override val seasonNumber: Int,
    val episodeCount: Int,
) : NewSeason

/**
 * One season whose episodes are already known, each with whatever metadata came with it -- the
 * add-by-search counterpart of [SeasonQuickFill] (ROADMAP Task 13 Phase D).
 *
 * ### Why this is a creation path and not the backfill
 * #75 draws a hard line between *enrichment* (filling `null` columns on rows that already exist) and
 * *population* (creating rows). The backfill only ever enriches, because it runs against a library
 * the user has already been ticking off and must never move a denominator or touch `watchedAt`.
 * Adding a show by search has no prior state to disturb, so it populates -- and it populates with
 * titles already in place, rather than creating blank rows and immediately enriching them, which
 * would be two passes over data the caller is already holding and would show the user a screen of
 * untitled episodes in between.
 *
 * ### Specials are not filtered here
 * [seasonNumber] `0` is accepted, exactly as [TVMetadataValidation.validateSeasonNumber] accepts it.
 * Which seasons an operation creates is that operation's policy (#75 creates regular seasons only;
 * #122 tracks revisiting that), and a repository that quietly dropped season 0 would take that
 * decision invisibly, away from the place it is actually made. This mirrors
 * [com.hub.media.features.tv.network.TmdbClient.showDetails], which returns TMDB's season list
 * unfiltered for the same reason.
 *
 * @property episodes The season's episodes. May be empty: TMDB lists announced-but-unaired seasons
 *   with `episode_count: 0` (Severance's season 3), and a season that creates no rows is a truthful
 *   representation of one that has no episodes yet -- not an error to reject.
 */
public data class SeasonEpisodes(
    override val seasonNumber: Int,
    val episodes: List<NewEpisode>,
) : NewSeason

/**
 * One episode's worth of provider metadata, ready to become an [EpisodeEntity] row.
 *
 * Every field except [episodeNumber] is optional, because every one of them is legitimately absent
 * from a real TMDB response: an unaired episode has no runtime and no air date, and an obscure one
 * has no synopsis and no votes. A missing value is stored as `null` ("not known"), never as a zero
 * or empty-string stand-in -- the rule [EpisodeEntity] states for itself.
 *
 * [EpisodeEntity.watchedAt] is deliberately absent from this type. A newly created episode has not
 * been watched, and no provider can say otherwise; leaving it un-settable means no add path can ever
 * claim a viewing the user did not have.
 *
 * [EpisodeEntity.stillImageHash] is absent for a different reason: it holds the hash of a *locally
 * stored* file, so it cannot be known until an image has actually been downloaded. It is filled by
 * whatever fetches stills, not by whoever describes an episode.
 *
 * @property communityRating Must already be on the 0-10 scale [EpisodeEntity.communityRating]
 *   declares. TMDB's `vote_average` is out of 10 and needs no conversion; a provider that scores out
 *   of 5 must be converted by its own mapping layer, not stored raw.
 */
public data class NewEpisode(
    val episodeNumber: Int,
    val title: String? = null,
    val airDate: Instant? = null,
    val runtimeMinutes: Int? = null,
    val overview: String? = null,
    val communityRating: Double? = null,
)

/**
 * What [TVShowRepository.setSeasonLength] actually changed.
 *
 * @property episodesRemoved How many episode rows a shrink deleted — 0 when the season grew or was
 *   already the requested length. Reported rather than inferred so the UI can tell the user what a
 *   confirmed shrink cost, including the case where their answer turned out to remove nothing.
 */
public data class SeasonLengthChange(
    val episodesRemoved: Int,
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
     * Adds a show, its details, its quick-filled episodes, and any provider mappings in one
     * transaction.
     *
     * Values still arrive fully formed from whoever is calling -- this validates and writes them,
     * it does not fetch. Manual entry passes what the user typed; an add-by-search path
     * (ROADMAP Task 13 Phase D) passes what it read from [com.hub.media.features.tv.network.TmdbClient]
     * plus the show's TMDB id in [externalIdentifiers].
     *
     * @param totalSeasons Advisory season count, or null for "unknown" -- see [TVDetailsEntity.totalSeasons].
     * @param seasons One [NewSeason] per season being populated now, in whichever form the caller
     *   has: [SeasonQuickFill] generates numbered rows with no metadata (manual entry), while
     *   [SeasonEpisodes] carries the episodes themselves (add-by-search). The two may be mixed in one
     *   call -- nothing here couples a show to a single source, and a show quick-filled by hand whose
     *   later seasons came from a provider is a perfectly coherent row.
     *
     *   May be empty -- a show can be added with no episodes yet and have seasons quick-filled later
     *   via [setSeasonLength].
     * @param externalIdentifiers Optional (provider, externalId) mappings recording which catalog
     *   record this row came from -- normally a single [IdentifierProvider.TMDB] pair carrying the
     *   show id as its decimal string. Defaults to empty, which is a hand-entered show: correct, and
     *   distinguishable from one added by search precisely because it holds no mapping.
     *
     *   Not validated here, and deliberately so. The composite `(mediaId, provider)` primary key
     *   already rejects a duplicate provider under ABORT and rolls the whole insert back with it, and
     *   unlike a repeated season number -- which a user can type into a form and deserves a sentence
     *   naming the season -- a repeated provider can only come from a caller assembling this list
     *   wrongly. Duplicating [com.hub.media.features.books.data.BookRepository.addBook]'s handling of
     *   the same parameter, rather than inventing a second rule for it.
     * @return [Resource.Success] with the new media id, or [Resource.Error] (never throws).
     */
    public suspend fun addShow(
        title: String,
        releaseYear: Int? = null,
        purchasePrice: Double? = null,
        totalSeasons: Int? = null,
        coverImageHash: String? = null,
        seasons: List<NewSeason> = emptyList(),
        externalIdentifiers: List<Pair<IdentifierProvider, String>> = emptyList(),
    ): Resource<String> {
        TVMetadataValidation.validateTitle(title)?.let { return Resource.Error(it) }
        TVMetadataValidation.validateReleaseYear(releaseYear)?.let { return Resource.Error(it) }
        TVMetadataValidation.validatePurchasePrice(purchasePrice)?.let { return Resource.Error(it) }
        TVMetadataValidation.validateTotalSeasons(totalSeasons)?.let { return Resource.Error(it) }
        for (season in seasons) {
            TVMetadataValidation.validateSeasonNumber(season.seasonNumber)?.let { return Resource.Error(it) }
            validateSeasonContents(season)?.let { return Resource.Error(it) }
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
            val episodes = seasons.flatMap { season -> episodeRowsFor(mediaId, season) }
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
            logger.error(TAG, e) { "Failed to add a TV show" }
            Resource.Error("Failed to add TV show: ${e.message ?: "Unknown error"}", cause = e)
        }
    }

    /**
     * Validates whatever one [NewSeason] carries beyond its season number, returning a rejection
     * message or `null`.
     *
     * Split out of [addShow] so the two shapes' rules sit side by side and the difference between
     * them is visible, rather than being an `is` check buried in a validation run.
     *
     * ### Why [SeasonEpisodes] has no length ceiling
     * [SeasonQuickFill] is capped at [TVMetadataValidation.MAX_EPISODE_COUNT] because its count is a
     * number a person typed, and `1000000` for `10` is a realistic typo that would otherwise reach a
     * row-generating loop. A [SeasonEpisodes] list is not typed, it is enumerated -- every entry
     * already exists as an object, so the same typo cannot produce one. Applying the cap anyway would
     * reject exactly the case [TVMetadataValidation.validateEpisodeNumber] documents as legitimate:
     * a long-running series catalogued as one continuous season, numbering past 500.
     */
    private fun validateSeasonContents(season: NewSeason): String? =
        when (season) {
            is SeasonQuickFill -> TVMetadataValidation.validateEpisodeCount(season.episodeCount)
            is SeasonEpisodes -> validateEpisodes(season)
        }

    private fun validateEpisodes(season: SeasonEpisodes): String? {
        for (episode in season.episodes) {
            TVMetadataValidation.validateEpisodeNumber(episode.episodeNumber)?.let { return it }
            TVMetadataValidation.validateEpisodeRuntimeMinutes(episode.runtimeMinutes)?.let { return it }
            TVMetadataValidation.validateCommunityRating(episode.communityRating)?.let { return it }
        }
        // Caught here rather than left to the unique (mediaId, seasonNumber, episodeNumber) index,
        // for the reason the duplicate-season check gives: the constraint failure would name three
        // columns and not the episode that was listed twice. A provider repeating an episode number
        // within one season is a mapping bug, and this is the sentence that says so.
        val numbers = season.episodes.map { it.episodeNumber }
        val duplicate = numbers.firstOrNull { number -> numbers.count { it == number } > 1 }
        return duplicate?.let { "Season ${season.seasonNumber} lists episode $it more than once" }
    }

    /**
     * Turns one validated [NewSeason] into the [EpisodeEntity] rows it describes.
     *
     * [EpisodeEntity.watchedAt] is left `null` by both branches and is not settable from either input
     * type: a show being created has been watched by nobody, and no provider can say otherwise.
     *
     * Blank strings are normalised to `null` rather than stored. `EpisodeEntity` documents `null` as
     * "not known", and a provider that answers with an empty title is saying it does not know one --
     * storing `""` would make that indistinguishable from a real title that happens to be empty, and
     * would defeat the backfill, which fills only columns that are `null`.
     */
    private fun episodeRowsFor(
        mediaId: String,
        season: NewSeason,
    ): List<EpisodeEntity> =
        when (season) {
            is SeasonQuickFill ->
                (1..season.episodeCount).map { episodeNumber ->
                    EpisodeEntity(
                        id = newId(),
                        mediaId = mediaId,
                        seasonNumber = season.seasonNumber,
                        episodeNumber = episodeNumber,
                    )
                }
            is SeasonEpisodes ->
                season.episodes.map { episode ->
                    EpisodeEntity(
                        id = newId(),
                        mediaId = mediaId,
                        seasonNumber = season.seasonNumber,
                        episodeNumber = episode.episodeNumber,
                        title = episode.title?.takeIf { it.isNotBlank() },
                        airDate = episode.airDate,
                        runtimeMinutes = episode.runtimeMinutes,
                        overview = episode.overview?.takeIf { it.isNotBlank() },
                        communityRating = episode.communityRating,
                    )
                }
        }

    /**
     * Makes one season exactly [episodeCount] episodes long, whether that means growing it,
     * shrinking it, or nothing at all.
     *
     * Growing keeps what is already there: only the missing episode numbers are inserted, so
     * correcting a season from 10 to 12 adds two rows and leaves the ten that exist — and everything
     * watched on them — untouched. See [EpisodeEntity]'s "rows exist before their titles do" KDoc.
     *
     * Shrinking **deletes** the episodes numbered above [episodeCount], and their watched dates with
     * them. That is the point of it: before this existed a season could only grow, so quick-filling
     * 20 episodes when 10 were meant left ten rows that could never be removed and a show that could
     * never read as finished. The only escape was deleting the show, which costs every watched date
     * on every season. This is the smaller loss, but it is still a loss, which is why the caller is
     * expected to put the count in front of the user first — [SeasonLengthChange] reports what it
     * actually cost.
     *
     * @param episodeCount The season's total length as the user now understands it, not a number of
     *   rows to add. Passing the same value twice does nothing the second time.
     * @return [Resource.Error] if [mediaId] does not resolve to an existing TV show, or a validation
     *   rule is violated.
     */
    public suspend fun setSeasonLength(
        mediaId: String,
        seasonNumber: Int,
        episodeCount: Int,
    ): Resource<SeasonLengthChange> {
        TVMetadataValidation.validateSeasonNumber(seasonNumber)?.let { return Resource.Error(it) }
        TVMetadataValidation.validateEpisodeCount(episodeCount)?.let { return Resource.Error(it) }

        return try {
            val show = db.mediaItemDao().getById(mediaId)
            if (show == null || show.type != MediaType.TV_SHOW) {
                return Resource.Error("TV show with id=$mediaId not found")
            }
            // Every row the season would have if it were empty; the DAO decides which of them are
            // actually missing inside the same transaction as the insert, so two quick-fills of one
            // season cannot both act on the same answer. Ids are generated here rather than there
            // because newId() is not the database's concern -- the unused ones are simply discarded.
            val candidates =
                (1..episodeCount).map { episodeNumber ->
                    EpisodeEntity(
                        id = newId(),
                        mediaId = mediaId,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                    )
                }
            val removed = db.tvWriteDao().setSeasonLength(mediaId, seasonNumber, episodeCount, candidates)
            Resource.Success(SeasonLengthChange(episodesRemoved = removed))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to set the length of season $seasonNumber for show: id=$mediaId" }
            Resource.Error("Failed to update season: ${e.message ?: "Unknown error"}", cause = e)
        }
    }

    /**
     * Removes a whole season and every episode in it.
     *
     * For a season added by mistake — the wrong number, or one that never existed. Destructive in
     * the same way a shrink through [setSeasonLength] is, and for the same reason acceptable: the
     * alternative was deleting the entire show. The caller confirms; this reports.
     *
     * Validates [seasonNumber] the way [setSeasonLength] does, so both destructive entry points
     * reject the same inputs. There is no matching "does this show exist" read: deleting by
     * `(mediaId, seasonNumber)` cannot touch another show's rows, and a mediaId that resolves to
     * nothing simply deletes nothing, which the zero case below already reports.
     *
     * @return [Resource.Error] if that season of that show has no episodes, so "removed nothing"
     *   cannot be mistaken for "removed a season".
     */
    public suspend fun removeSeason(
        mediaId: String,
        seasonNumber: Int,
    ): Resource<SeasonLengthChange> {
        TVMetadataValidation.validateSeasonNumber(seasonNumber)?.let { return Resource.Error(it) }

        return try {
            val removed = db.tvWriteDao().deleteSeason(mediaId, seasonNumber)
            if (removed == 0) {
                // The id belongs in the log, not in front of the user. Reaching here means the
                // screen offered a season that is already gone, so say that rather than quoting
                // the row it failed to find.
                logger.warn(TAG) { "No episodes to remove for season $seasonNumber of show: id=$mediaId" }
                Resource.Error("Season $seasonNumber no longer exists")
            } else {
                Resource.Success(SeasonLengthChange(episodesRemoved = removed))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to remove season $seasonNumber for show: id=$mediaId" }
            Resource.Error("Failed to remove season: ${e.message ?: "Unknown error"}", cause = e)
        }
    }

    /**
     * Sets or clears one episode's watched state, stamping [clock] `now()` when [watched] is true
     * and clearing to `null` when false.
     *
     * Re-ticking an already-watched episode must not bump its timestamp, mirroring
     * [com.hub.media.features.movies.data.MovieRepository.resolveWatchedAt]'s rule. That decision is
     * made in SQL (`COALESCE`, see [com.hub.media.core.database.dao.TVWriteDao.markEpisodeWatched])
     * rather than by reading the row and deciding here: reading first would leave a window between
     * the read and the write for a second tick to land in, and the read is not needed for anything
     * else. `clock.now()` is passed unconditionally and simply ignored by the database when the
     * episode already carries a date.
     *
     * @return [Resource.Error] if [episodeId] does not resolve to an existing episode.
     */
    public suspend fun setEpisodeWatched(
        episodeId: String,
        watched: Boolean,
    ): Resource<Unit> =
        try {
            val rows =
                if (watched) {
                    db.tvWriteDao().markEpisodeWatched(episodeId, clock.now())
                } else {
                    db.tvWriteDao().clearEpisodeWatched(episodeId)
                }
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
