package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.info
import com.hub.media.core.util.warn
import com.hub.media.features.movies.data.MovieRepository
import com.hub.media.features.movies.domain.toMovieMapping
import com.hub.media.features.tv.network.TmdbClient
import com.hub.media.features.tv.network.dto.TmdbSearchResultDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "MovieSearchViewModel"

/**
 * @property hasSearched Whether a search has completed at least once — the empty list means "you
 *   have not searched yet" and "nothing matched", and one message for both is wrong half the time.
 * @property savedMediaId Set once a film has been created; the route reads it and navigates.
 */
public data class MovieSearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val results: List<TmdbSearchResult> = emptyList(),
    val searchError: String? = null,
    val addingTmdbId: Int? = null,
    val addError: String? = null,
    val savedMediaId: String? = null,
)

/**
 * Backs adding a film by looking it up on TMDB (ROADMAP Task 13 Phase D) — the film counterpart of
 * [TVShowSearchViewModel].
 *
 * ### Why this is a second class rather than a generic one
 * The two differ in the middle step and nowhere else: a show is fetched with `append_to_response` and
 * translated into seasons and episodes, while a film is one record and four fields. Making that
 * generic would mean parameterising over the client call, the mapper, the repository and the add
 * signature — four type parameters to share a `search`/`select`/`report` skeleton that is about
 * twenty lines. #81 identifies the repository layer as the place per-media-type duplication is worth
 * extracting; this is not that layer, and a generic here would cost more than it saved.
 *
 * Everything the two *can* share already is: [TmdbSearchResult], and the date and vote-count rules
 * in `core.network`.
 *
 * The reasoning behind the rest — searching as an explicit action rather than a keystroke, not
 * checking the credential up front, refusing a duplicate before spending a request, and what is and
 * is not logged — is identical to [TVShowSearchViewModel] and is written out there.
 */
public class MovieSearchViewModel(
    private val tmdbClient: TmdbClient,
    private val movieRepository: MovieRepository,
    private val logger: Logger = AppLogger,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MovieSearchUiState())
    public val uiState: StateFlow<MovieSearchUiState> = _uiState.asStateFlow()

    public fun onQueryChange(value: String) {
        _uiState.value = _uiState.value.copy(query = value)
    }

    public fun search() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty() || _uiState.value.isSearching) return

        _uiState.value = _uiState.value.copy(isSearching = true, searchError = null, addError = null)
        viewModelScope.launch {
            when (val result = tmdbClient.searchMovies(query)) {
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

    /** Fetches the chosen film and adds it, unless it is already in the library. */
    public fun addMovie(tmdbId: Int) {
        if (_uiState.value.addingTmdbId != null) return
        _uiState.value = _uiState.value.copy(addingTmdbId = tmdbId, addError = null)

        viewModelScope.launch {
            // Checked before the request: a film already held is a question the local database can
            // answer, and spending a round trip to learn it is one the user waits on. The lookup is
            // scoped to MOVIE rows, which matters here rather than being defensive -- TMDB numbers
            // films and shows in separate sequences, so owning show 603 must not refuse film 603.
            val existing =
                movieRepository.findMovieIdByExternalId(IdentifierProvider.TMDB, tmdbId.toString())
            if (existing != null) {
                val title =
                    _uiState.value.results
                        .firstOrNull { it.tmdbId == tmdbId }
                        ?.title
                failAdd(
                    title?.let { "$it is already in your library" }
                        ?: "That film is already in your library",
                )
                return@launch
            }

            when (val fetched = tmdbClient.movieDetails(tmdbId)) {
                is Resource.Error -> failAdd(fetched.message)
                is Resource.Success -> {
                    val mapping = fetched.data.toMovieMapping()
                    if (mapping == null) {
                        logger.warn(TAG) { "TMDB record $tmdbId could not be translated: no usable title" }
                        failAdd("TMDB returned a film record without a title")
                        return@launch
                    }
                    when (
                        val saved =
                            movieRepository.addMovie(
                                title = mapping.title,
                                releaseYear = mapping.releaseYear,
                                runtimeMinutes = mapping.runtimeMinutes,
                                externalIdentifiers = mapping.externalIdentifiers,
                                communityRating = mapping.communityRating,
                            )
                    ) {
                        is Resource.Success -> {
                            logger.info(TAG) { "Added film from TMDB $tmdbId" }
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

    /** Clears [MovieSearchUiState.savedMediaId] once the route has navigated. */
    public fun reset() {
        _uiState.value = _uiState.value.copy(savedMediaId = null)
    }
}

/**
 * A search hit as the list draws it, or `null` when TMDB sent one with no usable title.
 *
 * Dropped rather than shown untitled: `addMovie` would reject a blank title, so offering it would be
 * offering a tap that always fails.
 */
private fun TmdbSearchResultDto.toSearchResult(): TmdbSearchResult? {
    val name = displayTitle?.takeIf { it.isNotBlank() } ?: return null
    return TmdbSearchResult(
        tmdbId = id,
        title = name,
        year = displayDate?.takeIf { it.isNotBlank() }?.take(4),
        overview = overview?.takeIf { it.isNotBlank() },
        posterPath = posterPath,
    )
}
