package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.util.Resource
import com.hub.media.features.media.data.MediaWithDetails
import com.hub.media.features.media.domain.BulkDeleteUseCase
import com.hub.media.features.tv.data.TVShowRepository
import com.hub.media.features.tv.domain.BackfillShowEpisodesUseCase
import com.hub.media.features.tv.domain.EpisodeBackfillReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * One season's episodes, grouped for display (ROADMAP Task 13 Phase C).
 *
 * @property watchedCount Derived from [episodes], never stored — see [TVShowDetailUiState.Ready]'s
 *   KDoc for why.
 */
public data class SeasonGroup(
    val seasonNumber: Int,
    val episodes: List<EpisodeEntity>,
    val watchedCount: Int,
)

/** State of the TV show detail screen (ROADMAP Task 13 Phase C). */
public sealed class TVShowDetailUiState {
    /** The first emission has not arrived yet. */
    public data object Loading : TVShowDetailUiState()

    /**
     * No show with this id. Reached when the id was deleted, never existed, **or belongs to a
     * different media type** — [TVShowRepository.observeShowDetail] gates on
     * [com.hub.media.core.database.entities.MediaType.TV_SHOW], so a book or movie id routed here
     * shows "not found" rather than a mislabelled row, mirroring [MovieDetailUiState.NotFound].
     */
    public data object NotFound : TVShowDetailUiState()

    /**
     * @property seasons The show's episodes grouped by season, ascending season then episode order
     *   — see [com.hub.media.core.database.dao.EpisodeDao.observeByMediaId]'s ordering.
     * @property watchedEpisodes @property totalEpisodes Overall progress, **derived by summing
     *   [seasons]' episode lists on every emission, never read from a stored counter**. Storing one
     *   would be a second source of truth for something [EpisodeEntity.watchedAt] already records —
     *   see [com.hub.media.core.database.entities.TVDetailsEntity]'s KDoc for why that shape of bug
     *   is refused here specifically.
     * @property isAbandoned Whether the show's stored status is [WatchStatus.ABANDONED] — see
     *   [setAbandoned] for why this is the only piece of [com.hub.media.core.database.entities.TVDetailsEntity.status]
     *   this screen reads.
     */
    public data class Ready(
        val show: MediaWithDetails.TVShow,
        val seasons: List<SeasonGroup>,
        val watchedEpisodes: Int,
        val totalEpisodes: Int,
        val isAbandoned: Boolean,
        val errorMessage: String? = null,
        /**
         * Whether this show records which TMDB record it came from, and so has something to refresh
         * against. `false` for a show typed in by hand -- the action is hidden rather than offered
         * and then refused, because a control that always fails is worse than one that is absent.
         */
        val canRefreshMetadata: Boolean = false,
        val isRefreshingMetadata: Boolean = false,
    ) : TVShowDetailUiState()
}

/**
 * Drives the TV show detail screen (ROADMAP Task 13 Phase C) — the TV counterpart of
 * [MovieDetailViewModel], and deliberately the same shape where the two overlap: deletion routes
 * through [BulkDeleteUseCase] for the same reference-aware poster cleanup a movie's delete gets, and
 * [uiState] is built the same way, by combining the repository's observers with a local error
 * channel and sharing with [SharingStarted.WhileSubscribed].
 *
 * The one structural difference is per-episode progress: [TVShowDetailUiState.Ready] additionally
 * carries the show's episodes grouped by season, and the overall watched/total counts, both derived
 * fresh from [TVShowRepository.observeEpisodes] on every emission rather than read from a stored
 * field. See that state's KDoc.
 */
public class TVShowDetailViewModel(
    private val showId: String,
    private val tvShowRepository: TVShowRepository,
    private val deleteMediaUseCase: BulkDeleteUseCase,
    private val backfillUseCase: BackfillShowEpisodesUseCase,
) : ViewModel() {
    private val errorMessage = MutableStateFlow<String?>(null)
    private val isRefreshing = MutableStateFlow(false)

    /**
     * The show's TMDB id, read once rather than observed.
     *
     * A provider mapping is written when the show is created and never edited afterwards, so there
     * is nothing to watch for -- and an observer here would add a fourth database query to a screen
     * that already runs three, for a value that cannot change while it is open.
     */
    private val tmdbId =
        MutableStateFlow<String?>(null).also { flow ->
            viewModelScope.launch {
                flow.value = tvShowRepository.findExternalId(showId, IdentifierProvider.TMDB)
            }
        }

    public val uiState: StateFlow<TVShowDetailUiState> =
        combine(
            tvShowRepository.observeShowDetail(showId),
            tvShowRepository.observeEpisodes(showId),
            errorMessage,
            tmdbId,
            isRefreshing,
        ) { show, episodes, error, tmdb, refreshing ->
            if (show == null) {
                TVShowDetailUiState.NotFound
            } else {
                val seasons =
                    episodes
                        .groupBy { it.seasonNumber }
                        .toSortedMap()
                        .map { (seasonNumber, seasonEpisodes) ->
                            SeasonGroup(
                                seasonNumber = seasonNumber,
                                episodes = seasonEpisodes,
                                watchedCount = seasonEpisodes.count { it.watchedAt != null },
                            )
                        }
                TVShowDetailUiState.Ready(
                    show = show,
                    seasons = seasons,
                    watchedEpisodes = episodes.count { it.watchedAt != null },
                    totalEpisodes = episodes.size,
                    isAbandoned = show.details?.status == WatchStatus.ABANDONED,
                    errorMessage = error,
                    canRefreshMetadata = tmdb != null,
                    isRefreshingMetadata = refreshing,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5.seconds),
            initialValue = TVShowDetailUiState.Loading,
        )

    /** Ticks or unticks one episode. Errors surface on [TVShowDetailUiState.Ready.errorMessage]. */
    public fun setEpisodeWatched(
        episodeId: String,
        watched: Boolean,
    ) {
        viewModelScope.launch {
            val result = tvShowRepository.setEpisodeWatched(episodeId, watched)
            if (result is Resource.Error) errorMessage.value = result.message
        }
    }

    /**
     * Bulk-marks (or clears) a whole season. Marking watched leaves an already-watched episode's
     * timestamp alone — see [TVShowRepository.setSeasonWatched]'s KDoc — this call does nothing to
     * undo that; it only forwards the request.
     */
    public fun setSeasonWatched(
        seasonNumber: Int,
        watched: Boolean,
    ) {
        viewModelScope.launch {
            val result = tvShowRepository.setSeasonWatched(showId, seasonNumber, watched)
            if (result is Resource.Error) errorMessage.value = result.message
        }
    }

    /**
     * Sets a season's length — quick-filling a new season, growing an existing one, or shrinking a
     * mistyped one. See [TVShowRepository.setSeasonLength]; a shrink deletes episodes and their
     * watched dates, so the caller is expected to have confirmed it first.
     */
    public fun setSeasonLength(
        seasonNumber: Int,
        episodeCount: Int,
    ) {
        viewModelScope.launch {
            val result = tvShowRepository.setSeasonLength(showId, seasonNumber, episodeCount)
            if (result is Resource.Error) errorMessage.value = result.message
        }
    }

    /**
     * Removes a season and every episode in it. Destructive and unconfirmed here by design — the
     * screen owns the confirmation, since it is the half that knows how much watched history the
     * user is about to lose.
     */
    public fun removeSeason(seasonNumber: Int) {
        viewModelScope.launch {
            val result = tvShowRepository.removeSeason(showId, seasonNumber)
            if (result is Resource.Error) errorMessage.value = result.message
        }
    }

    /**
     * Toggles between "abandoned" and "watchlist" — deliberately an Abandon/Resume toggle rather
     * than the four-way [com.hub.media.core.database.entities.WatchStatus] picker
     * [MovieDetailScreen] offers, because a show's place on the library shelf is **derived** from
     * its episodes, not read from this column — see
     * [LibraryStatusFilter.ofShow]'s KDoc. Offering WATCHING/WATCHED alongside WATCHLIST/ABANDONED
     * here would let the user set a value the library chip then silently ignores (every value but
     * ABANDONED is), which reads as a bug: "I set it to Watched but it still shows as in progress."
     * Abandon/Resume is the one axis this column actually controls, so it is the only one exposed.
     *
     * Writes [WatchStatus.ABANDONED] for `true`, [WatchStatus.WATCHLIST] for `false` — WATCHLIST
     * rather than the show's prior derived placement, because there is no prior derived value to
     * restore: [WatchStatus.WATCHING]/[WatchStatus.WATCHED] are never written by this app (see
     * [com.hub.media.core.database.entities.TVDetailsEntity]'s KDoc) and the filter recomputes the
     * real placement from episodes regardless of which of those the column holds once it is not
     * ABANDONED.
     */
    public fun setAbandoned(abandoned: Boolean) {
        viewModelScope.launch {
            val status = if (abandoned) WatchStatus.ABANDONED else WatchStatus.WATCHLIST
            val result = tvShowRepository.updateWatchStatus(showId, status)
            if (result is Resource.Error) errorMessage.value = result.message
        }
    }

    /** Deletes this show via [BulkDeleteUseCase], so an unreferenced poster file is cleaned up. */
    public fun deleteShow() {
        viewModelScope.launch {
            val result = deleteMediaUseCase.execute(listOf(showId))
            if (result is Resource.Error) errorMessage.value = result.message
        }
    }

    /** Acknowledges an error once shown, so it is not re-displayed on the next recomposition. */

    /**
     * Fills real episode metadata onto this show's existing rows from TMDB.
     *
     * Reports its outcome through the same channel as an error, deliberately: what a user needs to
     * see afterwards is one sentence about what happened, and a screen with two message channels
     * shows two snackbars when a pass both fills something and finds a disagreement.
     *
     * The pass itself cannot change any watched date, count or status -- see
     * [com.hub.media.features.tv.domain.BackfillShowEpisodesUseCase]. That is why this needs no
     * confirmation before running: there is nothing to undo.
     */
    public fun refreshMetadata() {
        if (isRefreshing.value) return
        isRefreshing.value = true
        viewModelScope.launch {
            when (val result = backfillUseCase.execute(showId)) {
                is Resource.Success -> errorMessage.value = result.data.describe()
                is Resource.Error -> errorMessage.value = result.message
            }
            isRefreshing.value = false
        }
    }

    public fun consumeError() {
        errorMessage.value = null
    }
}

/**
 * One sentence describing what a backfill pass did.
 *
 * Built here rather than in the composable because it is a *report*, not a label: which clauses
 * appear depends on what happened, and assembling that in a `@Composable` would put branching logic
 * where it cannot be tested without a device.
 *
 * Deliberately plain about the two things a user would otherwise have to infer. "Nothing to add"
 * is said outright rather than shown as an absence, because a screen that changes in no visible way
 * looks like a button that did not work. And a disagreement names both numbers — a count without
 * the one it disagrees with is not actionable, and #123 exists precisely because acting on it is a
 * decision this app does not make for you.
 */
internal fun EpisodeBackfillReport.describe(): String {
    val parts = mutableListOf<String>()
    parts +=
        when (episodesFilled) {
            0 -> "Nothing to add — every episode already has its details."
            1 -> "Updated 1 episode."
            else -> "Updated $episodesFilled episodes."
        }
    for (mismatch in mismatches) {
        parts +=
            "Season ${mismatch.seasonNumber}: you have ${mismatch.localEpisodes}, " +
            "TMDB lists ${mismatch.providerEpisodes}."
    }
    if (seasonsNotFetched.isNotEmpty()) {
        parts += "Season${if (seasonsNotFetched.size == 1) "" else "s"} " +
            seasonsNotFetched.joinToString(", ") + " could not be checked in one request."
    }
    return parts.joinToString(" ")
}
