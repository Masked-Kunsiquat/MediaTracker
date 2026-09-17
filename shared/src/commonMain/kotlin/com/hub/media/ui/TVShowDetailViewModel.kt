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
import com.hub.media.features.tv.domain.SeasonCountMismatch
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
         * Whether this show records a TMDB id that can actually address something, and so has
         * something to refresh against.
         *
         * `false` for a show typed in by hand, and also for one carrying an id that is not a number
         * -- the CSV importer validates the *provider* against the enum but accepts any non-blank
         * string as the id, so `TMDB:abc` is importable. The use case refuses such an id before
         * spending a request, which is right, but a button that is always refused is the very thing
         * hiding this control is meant to avoid.
         */
        val canRefreshMetadata: Boolean = false,
        val isRefreshingMetadata: Boolean = false,
        /**
         * Seasons TMDB's last refresh disagreed with the library about, in season order (#167).
         *
         * **In-memory only, and scoped to this screen's own lifetime** -- populated by
         * [TVShowDetailViewModel.refreshMetadata], never read from or written to the store
         * [com.hub.media.features.media.domain.ReconcileMismatchesUseCase] backs. Leaving the screen
         * and coming back starts empty; that is deliberate, per #167's decision 2: a show nobody has
         * quick-filled would otherwise fill Settings -> Review differences with "you have 0" rows for
         * every show that was simply never set up, mixed in with genuine drift.
         */
        val seasonFindings: List<SeasonCountMismatch> = emptyList(),
        /** Season numbers currently being created from [seasonFindings] -- see [TVShowDetailViewModel.addMissingEpisodes]. */
        val addingSeasonNumbers: Set<Int> = emptySet(),
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

    /** Backs [TVShowDetailUiState.Ready.seasonFindings] -- see that property's KDoc. */
    private val seasonFindings = MutableStateFlow<List<SeasonCountMismatch>>(emptyList())

    /** Backs [TVShowDetailUiState.Ready.addingSeasonNumbers]. */
    private val addingSeasonNumbers = MutableStateFlow<Set<Int>>(emptySet())

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

    // Five sources is combine()'s named-arity limit, and this screen already needed all five --
    // adding seasonFindings/addingSeasonNumbers as a sixth and seventh means nesting rather than
    // widening this call. The inner combine produces the base Ready/NotFound shape exactly as
    // before; the outer one only attaches the two in-memory, per-refresh fields onto a Ready result.
    public val uiState: StateFlow<TVShowDetailUiState> =
        combine(
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
                        canRefreshMetadata = tmdb?.toIntOrNull() != null,
                        isRefreshingMetadata = refreshing,
                    )
                }
            },
            seasonFindings,
            addingSeasonNumbers,
        ) { base, findings, adding ->
            if (base is TVShowDetailUiState.Ready) {
                base.copy(seasonFindings = findings, addingSeasonNumbers = adding)
            } else {
                base
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
     *
     * On success, [TVShowDetailUiState.Ready.seasonFindings] is replaced with this pass's
     * [EpisodeBackfillReport.mismatches] wholesale (#167) -- a stale finding from a previous refresh
     * must not survive one that no longer sees it. A failure leaves the prior findings alone: they
     * came from a real pass and remain true, so a transient network error should not blank them.
     */
    public fun refreshMetadata() {
        if (isRefreshing.value) return
        isRefreshing.value = true
        viewModelScope.launch {
            when (val result = backfillUseCase.execute(showId)) {
                is Resource.Success -> {
                    errorMessage.value = result.data.describe()
                    seasonFindings.value = result.data.mismatches
                }
                is Resource.Error -> errorMessage.value = result.message
            }
            isRefreshing.value = false
        }
    }

    /**
     * Creates the episode rows one [seasonFindings] entry says are missing, through the same
     * [TVShowRepository.addMissingEpisodes] call [com.hub.media.features.media.domain.ReconcileMismatchesUseCase]
     * makes for Review differences -- a second creation path here would be a second place to keep
     * that call's guarantees straight (#167).
     *
     * A season that succeeds drops out of [seasonFindings] without a manual re-refresh: the episode
     * list this screen renders is a live [TVShowRepository.observeEpisodes] flow, so the new rows
     * appear on their own, and re-running the whole TMDB pass just to notice a database write this
     * screen already knows happened would be a wasted request.
     *
     * A second tap while [seasonNumber] is already being created is ignored, the way
     * [MismatchReviewViewModel.addMissingEpisodes]'s `busyKeys` guard is -- see that function's KDoc.
     */
    public fun addMissingEpisodes(seasonNumber: Int) {
        if (seasonNumber in addingSeasonNumbers.value) return
        val finding = seasonFindings.value.firstOrNull { it.seasonNumber == seasonNumber } ?: return

        addingSeasonNumbers.value = addingSeasonNumbers.value + seasonNumber
        viewModelScope.launch {
            // finally, matching MismatchReviewViewModel's busyKeys: a cancelled add must not leave a
            // row disabled with nothing left to clear it.
            try {
                addOneSeason(finding)
            } finally {
                addingSeasonNumbers.value = addingSeasonNumbers.value - seasonNumber
            }
        }
    }

    /**
     * Applies every current [seasonFindings] entry in order, stopping at the first failure and
     * surfacing it -- a season that already succeeded before the failure stays applied and stays
     * dropped from [seasonFindings]; the ones after it are left for another attempt.
     *
     * Every season is marked busy up front, not one at a time as the loop reaches it. Marking them
     * individually left the rows this run had not reached yet still tappable, so a per-row Add could
     * run against a season the loop was about to take -- harmless at the database
     * ([com.hub.media.core.database.dao.TVWriteDao.insertMissingEpisodes] decides and inserts in one
     * transaction, so the loser inserts nothing) but an affordance that invites work already under
     * way. A second "add all" is ignored for the same reason.
     */
    public fun addAllMissingEpisodes() {
        if (addingSeasonNumbers.value.isNotEmpty()) return
        val batch = seasonFindings.value.toList()
        if (batch.isEmpty()) return

        addingSeasonNumbers.value = batch.map { it.seasonNumber }.toSet()
        viewModelScope.launch {
            try {
                for (finding in batch) {
                    val succeeded = addOneSeason(finding)
                    addingSeasonNumbers.value = addingSeasonNumbers.value - finding.seasonNumber
                    if (!succeeded) break
                }
            } finally {
                // Whatever the loop did or did not reach -- including a cancellation mid-season.
                addingSeasonNumbers.value = emptySet()
            }
        }
    }

    /**
     * The one call both [addMissingEpisodes] and [addAllMissingEpisodes] make.
     *
     * @return `true` on success, so [addAllMissingEpisodes] knows whether to continue to the next
     *   season.
     */
    private suspend fun addOneSeason(finding: SeasonCountMismatch): Boolean =
        when (
            val result =
                tvShowRepository.addMissingEpisodes(
                    mediaId = showId,
                    seasonNumber = finding.seasonNumber,
                    episodeCount = finding.providerEpisodes,
                )
        ) {
            is Resource.Error -> {
                errorMessage.value = result.message
                false
            }
            is Resource.Success -> {
                // The row has served its purpose: the episodes now exist, and the list below re-emits
                // on its own. Dropping it here is what stops the offer inviting the same add twice.
                seasonFindings.value = seasonFindings.value.filterNot { it.seasonNumber == finding.seasonNumber }
                true
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
 * ### The per-season disagreements are not said here anymore (#167)
 * They used to be appended as "Season N: you have X, TMDB lists Y" sentences, read once in a
 * snackbar that faded, with no way to act on any of them. [TVShowDetailUiState.Ready.seasonFindings]
 * now carries the same [mismatches] into a section that stays on screen and can create what is
 * missing, so restating them here would be the same information twice -- once actionable, once not.
 *
 * ### The zero case is about episode details, not about seasons
 * [episodesFilled] counts episode rows that gained a title, air date or the like -- it says nothing
 * about whether a season could be *added*. The old wording, "Nothing to add," was technically about
 * that narrower fact but read as a verdict on the whole show, which is exactly backwards on a show
 * like #167's: zero details filled and six whole seasons waiting to be created. This says what
 * `episodesFilled` actually means instead of a claim the seasons section can contradict a line below.
 */
internal fun EpisodeBackfillReport.describe(): String {
    val parts = mutableListOf<String>()
    parts +=
        when (episodesFilled) {
            0 -> "No episode details needed filling in."
            1 -> "Updated 1 episode."
            else -> "Updated $episodesFilled episodes."
        }
    if (seasonsNotFetched.isNotEmpty()) {
        parts += "Season${if (seasonsNotFetched.size == 1) "" else "s"} " +
            seasonsNotFetched.joinToString(", ") + " could not be checked in one request."
    }
    return parts.joinToString(" ")
}
