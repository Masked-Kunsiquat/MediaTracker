package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.info
import com.hub.media.core.util.warn
import com.hub.media.features.tv.data.TVShowRepository
import com.hub.media.features.tv.domain.toShowMapping
import com.hub.media.features.tv.network.TmdbClient
import com.hub.media.features.tv.network.dto.TmdbSearchResultDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "TVShowSearchViewModel"

/**
 * One row in the search results, carrying only what the list draws and what selecting it needs.
 *
 * A UI type rather than [TmdbSearchResultDto] so the screen holds no dependency on a wire format:
 * the DTO's field names are TMDB's (`name` for shows, `title` for films), and a screen written
 * against them would have to be edited if a provider were ever added or swapped.
 *
 * @property year Already formatted for display, or `null` when TMDB gave no date. Derived here
 *   rather than in the composable so the "what does an empty date mean" question is answered once.
 */
public data class ShowSearchResult(
    val tmdbId: Int,
    val title: String,
    val year: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
)

/**
 * @property hasSearched Whether a search has completed at least once. The empty list means two
 *   different things -- "you have not searched yet" and "nothing matched" -- and a screen that
 *   cannot tell them apart has to either show "no results" before the user has done anything or
 *   stay silent when a search genuinely found nothing.
 * @property savedMediaId Set once a show has been created; the route reads it and navigates. The
 *   save is asynchronous, so the tapped row cannot know the id at click time.
 */
public data class TVShowSearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val results: List<ShowSearchResult> = emptyList(),
    val searchError: String? = null,
    val addingTmdbId: Int? = null,
    val addError: String? = null,
    val savedMediaId: String? = null,
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
     * Fetches the chosen show in full and adds it to the library.
     *
     * [TVShowSearchUiState.addingTmdbId] carries *which* row is being added rather than a bare
     * boolean, so the screen can show progress on the row that was tapped instead of over the whole
     * list. Re-entry is refused while one is in flight: two taps would otherwise produce two shows.
     */
    public fun addShow(tmdbId: Int) {
        if (_uiState.value.addingTmdbId != null) return
        _uiState.value = _uiState.value.copy(addingTmdbId = tmdbId, addError = null)

        viewModelScope.launch {
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
                    when (
                        val saved =
                            tvShowRepository.addShow(
                                title = mapping.title,
                                releaseYear = mapping.releaseYear,
                                totalSeasons = mapping.totalSeasons,
                                seasons = mapping.seasons,
                                externalIdentifiers = mapping.externalIdentifiers,
                                airingStatus = mapping.airingStatus,
                                overview = mapping.overview,
                                firstAirDate = mapping.firstAirDate,
                                lastAirDate = mapping.lastAirDate,
                                communityRating = mapping.communityRating,
                            )
                    ) {
                        is Resource.Success -> {
                            logger.info(TAG) {
                                "Added show from TMDB $tmdbId: ${mapping.seasons.size} season(s)"
                            }
                            _uiState.value =
                                _uiState.value.copy(addingTmdbId = null, savedMediaId = saved.data)
                        }
                        is Resource.Error -> failAdd(saved.message)
                    }
                }
            }
        }
    }

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

/**
 * A search hit as the list draws it, or `null` when TMDB sent one with no usable name.
 *
 * Dropped rather than shown as an untitled row: it cannot be added -- `addShow` would reject a blank
 * title -- so offering it would be offering a tap that always fails.
 */
private fun TmdbSearchResultDto.toSearchResult(): ShowSearchResult? {
    val name = displayTitle?.takeIf { it.isNotBlank() } ?: return null
    return ShowSearchResult(
        tmdbId = id,
        title = name,
        year = displayDate?.takeIf { it.isNotBlank() }?.take(4),
        overview = overview?.takeIf { it.isNotBlank() },
        posterPath = posterPath,
    )
}
