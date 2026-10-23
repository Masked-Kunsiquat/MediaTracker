package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.info
import com.hub.media.core.util.warn
import com.hub.media.features.movies.data.MovieRepository
import com.hub.media.features.movies.domain.TmdbMovieMapping
import com.hub.media.features.movies.domain.toMovieMapping
import com.hub.media.features.tv.domain.FetchPosterUseCase
import com.hub.media.features.tv.domain.WatchedWhen
import com.hub.media.features.tv.network.TmdbClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "MovieSearchViewModel"

/**
 * @property hasSearched Whether a search has completed at least once — the empty list means "you
 *   have not searched yet" and "nothing matched", and one message for both is wrong half the time.
 * @property addingTmdbId The result being looked up; every row is unresponsive while it is set.
 * @property review The looked-up film, open for checking and editing before anything is saved
 *   (#214). `null` shows the search itself.
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
    val review: FilmReview? = null,
    val savedMediaId: String? = null,
)

/**
 * A film picked from TMDB search, waiting on the user's Save (#214).
 *
 * [mapping] is everything TMDB said, kept whole because the save writes more than the form shows
 * -- the TMDB id, community rating, synopsis and poster path. [form] is what the user sees and
 * edits; its values win over [mapping]'s at the save.
 */
public data class FilmReview(
    val mapping: TmdbMovieMapping,
    val form: FilmFormState,
    val isSaving: Boolean = false,
    val saveError: String? = null,
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
    private val fetchPosterUseCase: FetchPosterUseCase,
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

    /**
     * Looks up the chosen film and opens it for review (#214), unless it is already in the library.
     * Nothing is saved here: [saveReview] does that once the user has checked the form.
     */
    public fun reviewMovie(tmdbId: Int) {
        if (_uiState.value.addingTmdbId != null || _uiState.value.review != null) return
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
                    logger.info(TAG) { "Looked up film from TMDB $tmdbId for review" }
                    _uiState.value =
                        _uiState.value.copy(addingTmdbId = null, review = FilmReview(mapping, mapping.toFilmForm()))
                }
            }
        }
    }

    public fun onReviewTitleChange(value: String): Unit = updateReviewForm { it.copy(title = value) }

    public fun onReviewReleaseYearChange(value: String): Unit = updateReviewForm { it.copy(releaseYear = value) }

    public fun onReviewRuntimeChange(value: String): Unit = updateReviewForm { it.copy(runtimeMinutes = value) }

    public fun onReviewPurchasePriceChange(value: String): Unit = updateReviewForm { it.copy(purchasePrice = value) }

    /** See [FilmFormState.choosingStatus]: choosing Watched asks when first ([confirmReviewWatched]). */
    public fun onReviewStatusChange(value: WatchStatus): Unit = updateReviewForm { it.choosingStatus(value) }

    public fun confirmReviewWatched(watchedWhen: WatchedWhen): Unit =
        updateReviewForm { it.confirmingWatched(watchedWhen) }

    public fun dismissReviewWatched(): Unit = updateReviewForm { it.dismissingWatched() }

    /**
     * Saves the reviewed film: the form's values, plus everything else TMDB said.
     *
     * Ignored while a save is in flight or once one has produced a film, so a double tap cannot
     * create two. An unreadable field is reported on the review rather than saved as unknown (see
     * [FilmFormState.parsed]).
     */
    public fun saveReview() {
        val review = _uiState.value.review ?: return
        if (review.isSaving || _uiState.value.savedMediaId != null) return
        val values =
            when (val parsed = review.form.parsed()) {
                is FilmFormParse.Unreadable -> {
                    updateReview { it.copy(saveError = parsed.message()) }
                    return
                }
                is FilmFormParse.Valid -> parsed
            }
        updateReview { it.copy(isSaving = true, saveError = null) }
        val mapping = review.mapping
        viewModelScope.launch {
            when (
                val saved =
                    movieRepository.addMovie(
                        title = values.title,
                        releaseYear = values.releaseYear,
                        purchasePrice = values.purchasePrice,
                        runtimeMinutes = values.runtimeMinutes,
                        status = values.status,
                        watchedWhen = values.watchedWhen,
                        externalIdentifiers = mapping.externalIdentifiers,
                        communityRating = mapping.communityRating,
                        synopsis = mapping.synopsis,
                    )
            ) {
                is Resource.Success -> {
                    logger.info(TAG) { "Added film from TMDB review" }
                    // Release the screen *first*, then fetch the artwork.
                    //
                    // Fetching before this line made a poster a precondition of reaching the
                    // film: savedMediaId is what the route navigates on, so a slow or hanging
                    // download left the user on a dead form looking at a film that had already
                    // been added. Found on a device -- the row existed in the database while the
                    // screen had not moved.
                    //
                    // The detail screen observes its row, so the poster appears there when it
                    // lands. Failure stays ignored: the entry is complete without one.
                    _uiState.value = _uiState.value.copy(review = null, savedMediaId = saved.data)
                    fetchPosterUseCase.enqueue(saved.data, mapping.posterPath)
                }
                is Resource.Error -> updateReview { it.copy(isSaving = false, saveError = saved.message) }
            }
        }
    }

    /**
     * Closes the review and returns to the results it was opened from, saving nothing. Ignored
     * while a save is in flight: that write is already happening, and backing out of it would leave
     * the user looking at search results for a film that then appears in the library.
     */
    public fun cancelReview() {
        if (_uiState.value.review?.isSaving == true) return
        _uiState.value = _uiState.value.copy(review = null)
    }

    /** Acknowledges a save error once shown, so it is not repeated on the next recomposition. */
    public fun dismissReviewError(): Unit = updateReview { it.copy(saveError = null) }

    private inline fun updateReview(transform: (FilmReview) -> FilmReview) {
        val review = _uiState.value.review ?: return
        _uiState.value = _uiState.value.copy(review = transform(review))
    }

    private inline fun updateReviewForm(transform: (FilmFormState) -> FilmFormState): Unit =
        updateReview { it.copy(form = transform(it.form)) }

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
