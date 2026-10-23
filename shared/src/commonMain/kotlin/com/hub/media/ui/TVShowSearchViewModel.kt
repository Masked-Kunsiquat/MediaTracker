package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.info
import com.hub.media.core.util.warn
import com.hub.media.features.tv.data.TVShowRepository
import com.hub.media.features.tv.domain.FetchPosterUseCase
import com.hub.media.features.tv.domain.TmdbShowMapping
import com.hub.media.features.tv.domain.toShowMapping
import com.hub.media.features.tv.network.TmdbClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "TVShowSearchViewModel"

/**
 * @property hasSearched Whether a search has completed at least once. The empty list means two
 *   different things -- "you have not searched yet" and "nothing matched" -- and a screen that
 *   cannot tell them apart has to either show "no results" before the user has done anything or
 *   stay silent when a search genuinely found nothing.
 * @property addingTmdbId The result being looked up; every row is unresponsive while it is set.
 * @property review The looked-up show, open for checking and editing before anything is saved
 *   (#214). `null` shows the search itself.
 * @property savedMediaId Set once a show has been created; the route reads it and navigates. The
 *   save is asynchronous, so the tapped row cannot know the id at click time.
 */
public data class TVShowSearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val results: List<TmdbSearchResult> = emptyList(),
    val searchError: String? = null,
    val addingTmdbId: Int? = null,
    val addError: String? = null,
    val review: ShowReview? = null,
    val savedMediaId: String? = null,
)

/**
 * A show picked from TMDB search, waiting on the user's Save (#214).
 *
 * [mapping] is everything TMDB said, kept whole because the save writes far more than the form
 * shows -- every season and episode with its titles and air dates, the airing status and dates, the
 * TMDB id, rating, overview and poster path. [form] is what the user sees and edits; its values win
 * over [mapping]'s at the save. It has no quick-fill rows: the seasons come from [mapping].
 */
public data class ShowReview(
    val mapping: TmdbShowMapping,
    val form: ShowFormState,
    val isSaving: Boolean = false,
    val saveError: String? = null,
)

/**
 * Backs adding a TV show by looking it up on TMDB (ROADMAP Task 13 Phase D).
 *
 * ### Searching is an explicit action, not a keystroke
 * No debounce, no search-as-you-type. TMDB's interactive paths are deliberately unpaced -- see
 * [TmdbClient]'s KDoc on why a user waiting on one lookup should not pay a rate-limiter's interval --
 * and search-as-you-type would turn one lookup into one per keystroke against that same unpaced
 * path. A debounce would only reduce that, not bound it. The user presses search; one request goes.
 *
 * ### Selecting a result is three steps, and the middle one is where the thinking is
 * Fetch the show and its seasons in one request, translate it with
 * [com.hub.media.features.tv.domain.toShowMapping], then write it. All of the judgement about what
 * TMDB meant lives in that translation, which is why this class is as thin as it looks: it is
 * sequencing and error reporting, not interpretation.
 *
 * ### What this logs, and what it deliberately does not
 * A failed search or a failed fetch is already logged by [TmdbClient] with its status code, and a
 * rejected write by [TVShowRepository]; repeating either here would put the same failure in the log
 * twice with less detail. What is logged is what nothing else can see: that a show entered the
 * library *from a provider* rather than by hand, and the one failure that is neither a request nor a
 * write -- a record TMDB returned that could not be translated.
 *
 * The search text is never logged. It is something the user typed, it adds nothing a TMDB id does
 * not, and this app has an in-app log viewer and a log export.
 *
 * ### The credential is not checked up front
 * [TmdbClient] answers a missing credential with a [Resource.Error] carrying a sentence that names
 * Settings, so it arrives through the same channel as any other failure and the screen shows it the
 * same way. Checking separately would mean a second source of truth for "is TMDB usable", and it
 * could be stale by the time the request went out anyway.
 */
public class TVShowSearchViewModel(
    private val tmdbClient: TmdbClient,
    private val tvShowRepository: TVShowRepository,
    private val fetchPosterUseCase: FetchPosterUseCase,
    private val logger: Logger = AppLogger,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TVShowSearchUiState())
    public val uiState: StateFlow<TVShowSearchUiState> = _uiState.asStateFlow()

    public fun onQueryChange(value: String) {
        _uiState.value = _uiState.value.copy(query = value)
    }

    /**
     * Runs one search. A blank query is ignored rather than sent -- [TmdbClient.searchShows] would
     * answer it with an empty list without spending a request, but showing "nothing matched" for a
     * question the user never asked is worse than doing nothing.
     */
    public fun search() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty() || _uiState.value.isSearching) return

        _uiState.value = _uiState.value.copy(isSearching = true, searchError = null, addError = null)
        viewModelScope.launch {
            when (val result = tmdbClient.searchShows(query)) {
                is Resource.Success ->
                    _uiState.value =
                        _uiState.value.copy(
                            isSearching = false,
                            hasSearched = true,
                            results = result.data.results.mapNotNull { it.toSearchResult() },
                        )
                is Resource.Error ->
                    _uiState.value =
                        _uiState.value.copy(
                            isSearching = false,
                            hasSearched = true,
                            results = emptyList(),
                            searchError = result.message,
                        )
            }
        }
    }

    /**
     * Looks up the chosen show and opens it for review (#214), unless it is already in the library.
     * Nothing is saved here: [saveReview] does that once the user has checked the form.
     */
    public fun reviewShow(tmdbId: Int) {
        if (_uiState.value.addingTmdbId != null || _uiState.value.review != null) return
        _uiState.value = _uiState.value.copy(addingTmdbId = tmdbId, addError = null)

        viewModelScope.launch {
            // Checked before the request, not after: a show already held is a question the local
            // database can answer, and spending a round trip to learn it is one the user waits on.
            val existing =
                tvShowRepository.findShowIdByExternalId(IdentifierProvider.TMDB, tmdbId.toString())
            if (existing != null) {
                val title =
                    _uiState.value.results
                        .firstOrNull { it.tmdbId == tmdbId }
                        ?.title
                failAdd(
                    title?.let { "$it is already in your library" }
                        ?: "That show is already in your library",
                )
                return@launch
            }

            when (val fetched = tmdbClient.showWithSeasons(tmdbId)) {
                is Resource.Error -> failAdd(fetched.message)
                is Resource.Success -> {
                    val mapping = fetched.data.toShowMapping()
                    if (mapping == null) {
                        // Only reachable when TMDB returned a record with no usable name. Reported
                        // rather than silently skipped: the user tapped a row and is owed an answer.
                        // Logged because nothing else sees it -- the request succeeded and no write
                        // was attempted, so neither TmdbClient nor TVShowRepository has anything to
                        // say about it.
                        logger.warn(TAG) { "TMDB record $tmdbId could not be translated: no usable title" }
                        failAdd("TMDB returned a show record without a title")
                        return@launch
                    }
                    logger.info(
                        TAG,
                    ) { "Looked up show from TMDB $tmdbId for review: ${mapping.seasons.size} season(s)" }
                    _uiState.value =
                        _uiState.value.copy(addingTmdbId = null, review = ShowReview(mapping, mapping.toShowForm()))
                }
            }
        }
    }

    public fun onReviewTitleChange(value: String): Unit = updateReviewForm { it.copy(title = value) }

    public fun onReviewReleaseYearChange(value: String): Unit = updateReviewForm { it.copy(releaseYear = value) }

    public fun onReviewTotalSeasonsChange(value: String): Unit = updateReviewForm { it.copy(totalSeasons = value) }

    public fun onReviewPurchasePriceChange(value: String): Unit = updateReviewForm { it.copy(purchasePrice = value) }

    /**
     * Saves the reviewed show: the form's values, plus every season and episode and everything else
     * TMDB said.
     *
     * Ignored while a save is in flight or once one has produced a show, so a double tap cannot
     * create two. An unreadable field is reported on the review rather than saved as unknown.
     */
    public fun saveReview() {
        val review = _uiState.value.review ?: return
        if (review.isSaving || _uiState.value.savedMediaId != null) return
        val values =
            when (val parsed = review.form.parsed()) {
                is ShowFormParse.Unreadable -> {
                    updateReview { it.copy(saveError = parsed.message) }
                    return
                }
                is ShowFormParse.Valid -> parsed
            }
        updateReview { it.copy(isSaving = true, saveError = null) }
        val mapping = review.mapping
        viewModelScope.launch {
            when (
                val saved =
                    tvShowRepository.addShow(
                        title = values.title,
                        releaseYear = values.releaseYear,
                        purchasePrice = values.purchasePrice,
                        totalSeasons = values.totalSeasons,
                        seasons = mapping.seasons,
                        externalIdentifiers = mapping.externalIdentifiers,
                        airingStatus = mapping.airingStatus,
                        synopsis = mapping.overview,
                        firstAirDate = mapping.firstAirDate,
                        lastAirDate = mapping.lastAirDate,
                        communityRating = mapping.communityRating,
                    )
            ) {
                is Resource.Success -> {
                    logger.info(TAG) { "Added show from TMDB review: ${mapping.seasons.size} season(s)" }
                    // Release the screen *first*, then fetch the artwork -- see
                    // MovieSearchViewModel.saveReview for the device-found failure this ordering
                    // fixes: savedMediaId drives navigation, so fetching first left the user on a
                    // dead form looking at a show that had already been added.
                    _uiState.value = _uiState.value.copy(review = null, savedMediaId = saved.data)
                    fetchPosterUseCase.enqueue(saved.data, mapping.posterPath)
                }
                is Resource.Error -> updateReview { it.copy(isSaving = false, saveError = saved.message) }
            }
        }
    }

    /**
     * Closes the review and returns to the results it was opened from, saving nothing. Ignored
     * while a save is in flight, for the reason [MovieSearchViewModel.cancelReview] gives.
     */
    public fun cancelReview() {
        if (_uiState.value.review?.isSaving == true) return
        _uiState.value = _uiState.value.copy(review = null)
    }

    /** Acknowledges a save error once shown, so it is not repeated on the next recomposition. */
    public fun dismissReviewError(): Unit = updateReview { it.copy(saveError = null) }

    private inline fun updateReview(transform: (ShowReview) -> ShowReview) {
        val review = _uiState.value.review ?: return
        _uiState.value = _uiState.value.copy(review = transform(review))
    }

    private inline fun updateReviewForm(transform: (ShowFormState) -> ShowFormState): Unit =
        updateReview { it.copy(form = transform(it.form)) }

    private fun failAdd(message: String) {
        _uiState.value = _uiState.value.copy(addingTmdbId = null, addError = message)
    }

    /** Clears a reported failure without discarding the results the user searched for. */
    public fun dismissError() {
        _uiState.value = _uiState.value.copy(searchError = null, addError = null)
    }

    /**
     * Clears [TVShowSearchUiState.savedMediaId] once the route has navigated, so returning to this
     * screen does not immediately navigate again off a value that has already been consumed.
     * Mirrors [AddTVShowViewModel.reset].
     */
    public fun reset() {
        _uiState.value = _uiState.value.copy(savedMediaId = null)
    }
}
