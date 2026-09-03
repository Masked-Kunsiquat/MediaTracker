package com.hub.media.features.tv.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.dao.EpisodeMetadataFill
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.network.toInstantOrNull
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.error
import com.hub.media.core.util.info
import com.hub.media.features.tv.data.TVShowRepository
import com.hub.media.features.tv.network.TmdbClient
import com.hub.media.features.tv.network.TmdbShowWithSeasons
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "BackfillShowEpisodes"

/**
 * A season whose local episode count disagrees with the provider's.
 *
 * Reported, never acted on -- see [BackfillShowEpisodesUseCase]. #123 holds the question of what a
 * user should be able to do about one.
 */
public data class SeasonCountMismatch(
    public val seasonNumber: Int,
    public val localEpisodes: Int,
    public val providerEpisodes: Int,
)

/**
 * What one backfill pass over a show actually did.
 *
 * @property episodesFilled Rows the update touched. Counts episodes *matched*, not columns changed:
 *   a row already holding every value is still a match, and reporting it as such is honest about
 *   what the pass covered.
 * @property mismatches Seasons where the counts disagree, scoped to `seasonNumber >= 1`.
 * @property seasonsNotFetched Regular seasons the provider declares that this pass did not receive,
 *   because one request carries at most [com.hub.media.features.tv.network.MAX_APPENDED_SEASONS] of
 *   them. Their episodes are untouched rather than wrongly reported as having nothing to add.
 */
public data class EpisodeBackfillReport(
    public val episodesFilled: Int = 0,
    public val mismatches: List<SeasonCountMismatch> = emptyList(),
    public val seasonsNotFetched: List<Int> = emptyList(),
)

/**
 * Fills real episode titles, air dates, runtimes, synopses and scores onto episode rows a user
 * quick-filled by hand (ROADMAP Task 13 Phase D, the last clause of #75).
 *
 * ### This enriches; it never creates, deletes or re-dates
 * The distinction is the whole design, settled on #75 before any of it was written. Enrichment only
 * ever writes columns that are `null`, so it cannot change `watchedAt`, cannot change any count, and
 * therefore cannot change a show's completion status. **A background pass can never silently alter
 * what your library says you have watched** — that is the property being protected, and it holds
 * whichever way #88's specials question is set.
 *
 * The guarantee is not this class being careful. It is
 * [com.hub.media.core.database.dao.TVWriteDao.fillEpisodeMetadata], whose statement `COALESCE`s
 * every column it writes and does not mention `watchedAt` at all. This class could be rewritten
 * carelessly and still not be able to destroy a watch date.
 *
 * ### Population is somebody else's job
 * When the provider lists more episodes for a season than exist locally, that is **reported**, not
 * filled in. Creating rows moves the denominator, turning a finished show into an unfinished one
 * without the user doing anything; #123 owns what to offer instead, and `setSeasonLength` already
 * exists for a user who decides to act. Reports, never silent action — the same shape
 * `ImportSummary.notes` uses for a low-confidence match.
 *
 * ### One request, not one per season
 * [TmdbClient.showWithSeasons] folds the show and up to
 * [com.hub.media.features.tv.network.MAX_APPENDED_SEASONS] seasons into a single response. A show
 * beyond that cap has its remaining seasons listed in [EpisodeBackfillReport.seasonsNotFetched]
 * rather than quietly skipped, because "nothing to fill" and "never looked" are different answers.
 *
 * ### Specials are not fetched, so they are not compared
 * `showWithSeasons` never requests season 0, so a user's hand-entered specials are invisible to this
 * pass — which is exactly right. #88 established that TMDB's own totals exclude specials, so
 * comparing them against local rows would report a phantom mismatch on every show where the user
 * tracks any. Every count here is scoped to `seasonNumber >= 1`.
 *
 * ### Pacing belongs to the caller, not here
 * This takes no rate limiter. One show is one request, and an interactive "refresh this show" should
 * not pay an interval. A pass over a whole library *is* a burst, which is the concern #42 fixed for
 * the book backfill and which applies here for the same reason — but that belongs to whatever runs
 * the pass, by handing a paced [TmdbClient] in, rather than to a class that does one show.
 */
public class BackfillShowEpisodesUseCase(
    private val db: AppDatabase,
    private val tmdbClient: TmdbClient,
    private val tvShowRepository: TVShowRepository,
    private val logger: Logger = AppLogger,
) {
    /**
     * Runs one pass over [mediaId].
     *
     * @return [Resource.Error] when the show has no TMDB mapping (nothing to ask about) or the
     *   request fails. A show with nothing left to fill is a [Resource.Success] carrying an empty
     *   report, not an error: having complete metadata is not a failure.
     */
    public suspend fun execute(mediaId: String): Resource<EpisodeBackfillReport> {
        return try {
            val tmdbId =
                tvShowRepository.findExternalId(mediaId, IdentifierProvider.TMDB)
                    ?: return Resource.Error(
                        "This show was not added from TMDB, so there is nothing to look up.",
                    )

            // A stored id that is not a number cannot address anything at TMDB. Refused here rather
            // than coerced: the CSV importer validates the *provider* against the enum but accepts
            // any non-blank string as the id, so "TMDB:abc" is importable -- and coercing it would
            // spend a request on /tv/-1 and report a 404, which describes neither the cause nor the
            // remedy.
            val numericId =
                tmdbId.toIntOrNull()
                    ?: return Resource.Error("This show's stored TMDB id is not a number: \"$tmdbId\"")

            val fetched =
                when (val result = tmdbClient.showWithSeasons(numericId)) {
                    is Resource.Error -> return result
                    is Resource.Success -> result.data
                }

            val local = db.episodeDao().getByMediaId(mediaId).filter { it.seasonNumber >= 1 }
            val fills =
                local.mapNotNull { episode ->
                    val provided =
                        fetched.seasons[episode.seasonNumber]
                            ?.episodes
                            ?.firstOrNull { it.episodeNumber == episode.episodeNumber }
                            ?: return@mapNotNull null
                    EpisodeMetadataFill(
                        seasonNumber = episode.seasonNumber,
                        episodeNumber = episode.episodeNumber,
                        title = provided.name?.takeIf { it.isNotBlank() },
                        airDate = provided.airDate.toInstantOrNull(),
                        runtimeMinutes = provided.runtime?.takeIf { it > 0 },
                        overview = provided.overview?.takeIf { it.isNotBlank() },
                        communityRating = provided.voteAverage?.takeIf { (provided.voteCount ?: 0) > 0 },
                    )
                }
            val filled = db.tvWriteDao().fillEpisodeMetadata(mediaId, fills)

            val report =
                EpisodeBackfillReport(
                    episodesFilled = filled,
                    mismatches = mismatchesFor(local.groupBy { it.seasonNumber }, fetched),
                    seasonsNotFetched = fetched.missingSeasonNumbers,
                )
            logger.info(TAG) {
                "Backfilled show from TMDB $tmdbId: ${report.episodesFilled} episode(s), " +
                    "${report.mismatches.size} season(s) disagreeing"
            }
            Resource.Success(report)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to backfill episodes for show id=$mediaId" }
            Resource.Error("Failed to backfill episodes: ${e.message ?: "Unknown error"}", cause = e)
        }
    }

    /**
     * Seasons the two sides disagree about, compared **per season** rather than against the show's
     * own totals.
     *
     * #88 is emphatic about this and gave a number for it: TMDB's `number_of_episodes` excludes
     * specials, and on real data it can also simply be stale — Judy Justice reports 446 while its
     * four seasons sum to 458. A pass trusting the headline would report a mismatch on a show that
     * has none.
     *
     * Only seasons this pass actually received are compared. One that was not fetched is absent from
     * both this list and the local comparison, and is reported separately.
     */
    private fun mismatchesFor(
        localBySeason: Map<Int, List<EpisodeEntity>>,
        fetched: TmdbShowWithSeasons,
    ): List<SeasonCountMismatch> =
        fetched.seasons
            .filterKeys { it >= 1 }
            .mapNotNull { (number, season) ->
                val localCount = localBySeason[number]?.size ?: 0
                val providerCount = season.episodes.size
                if (localCount == providerCount) {
                    null
                } else {
                    SeasonCountMismatch(number, localCount, providerCount)
                }
            }.sortedBy { it.seasonNumber }
}
