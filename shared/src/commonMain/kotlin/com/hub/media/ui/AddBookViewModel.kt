package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.util.Resource
import com.hub.media.features.books.domain.BookFormValues
import com.hub.media.features.books.domain.BookIngestionUseCase
import com.hub.media.features.books.domain.ResolveWorkToEditionsUseCase
import com.hub.media.features.books.network.BookEditionSearchResult
import com.hub.media.features.books.network.BookSearchProvider
import com.hub.media.features.media.domain.SearchMediaUseCase
import com.hub.media.features.media.network.MediaSearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the "add book by ISBN" screen, including title/author search and result selection
 * (ROADMAP Task 9 Phase B2).
 *
 * This ViewModel owns two independent flows:
 * - **Add flow:** [uiState] models terminal outcomes (Idle/Loading/Success/Error) of the ISBN
 *   ingestion pipeline.
 * - **Search flow:** [searchState], [searchResults], [searchQuery] model the title/author type-ahead
 *   discovery pipeline, including debounce, cancellation, and result caching (via [SearchMediaUseCase]).
 *   Selecting a result ([selectSearchResult]) bridges from search to add: it resolves the edition
 *   key to an ISBN (via [searchProvider]) and, if successful, feeds that ISBN to the add flow.
 *
 * @param addBookByIsbnUseCase Runs the ISBN ingestion workflow. Typed as the narrow
 *   [BookIngestionUseCase] interface (rather than the concrete
 *   [com.hub.media.features.books.domain.AddBookByIsbnUseCase]) so tests can hand-roll a fake
 *   with no Ktor/Room/disk dependencies (AGENTS.md §5 "No Unnecessary Dependencies" — no mocking
 *   library).
 * @param searchMediaUseCase The search orchestrator (min-length checks, query normalization,
 *   LRU result cache). Injected so tests can provide a fake.
 * @param searchProvider Resolves selected search results to ISBNs. Injected so tests can provide a fake.
 * @param resolveWorkToEditionsUseCase Resolves a work key to its available editions (GitHub Issue #63).
 */
public class AddBookViewModel(
    private val addBookByIsbnUseCase: BookIngestionUseCase,
    private val searchMediaUseCase: SearchMediaUseCase? = null,
    private val searchProvider: BookSearchProvider? = null,
    private val resolveWorkToEditionsUseCase: ResolveWorkToEditionsUseCase? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow<AddBookUiState>(AddBookUiState.Idle)
    public val uiState: StateFlow<AddBookUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    public val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<MediaSearchResult>>(emptyList())
    public val searchResults: StateFlow<List<MediaSearchResult>> = _searchResults.asStateFlow()

    private val _searchState = MutableStateFlow<AddSearchState>(AddSearchState.Idle)
    public val searchState: StateFlow<AddSearchState> = _searchState.asStateFlow()

    private val _confirmationResult = MutableStateFlow<MediaSearchResult?>(null)
    public val confirmationResult: StateFlow<MediaSearchResult?> = _confirmationResult.asStateFlow()

    private val _editions = MutableStateFlow<List<BookEditionSearchResult>>(emptyList())
    public val editions: StateFlow<List<BookEditionSearchResult>> = _editions.asStateFlow()

    private var searchJob: Job? = null
    private var resolveJob: Job? = null

    /**
     * Initiates a search for [query], with debounce (300ms) and previous-query cancellation.
     *
     * Queries shorter than [SearchMediaUseCase.MIN_SEARCH_QUERY_LENGTH] are answered immediately
     * with an empty result (from the cache, spending no network budget), distinguishing them from
     * zero-match searches (which report [AddSearchState.NoResults] instead). Call
     * [SearchMediaUseCase.isQueryLongEnough] before showing "keep typing" prompts to the user
     * without duplicating the length rules.
     *
     * Per ROADMAP Task 9 Phase B2: cancel-on-keystroke, debounce is 300ms per ROADMAP line 495.
     */
    public fun search(query: String) {
        _searchQuery.value = query

        // Cancel any in-flight search for the previous query.
        searchJob?.cancel()

        // Guard: search requires both use case and provider to be injected.
        if (searchMediaUseCase == null || searchProvider == null) return

        // If the add flow is in a terminal error state, clear it so searching can resume.
        if (_uiState.value is AddBookUiState.Error) {
            _uiState.value = AddBookUiState.Idle
        }

        if (_uiState.value is AddBookUiState.Loading) {
            // Don't start a new search if the add flow is in flight.
            return
        }

        // A too-short query immediately returns empty results without hitting the network.
        if (!searchMediaUseCase.isQueryLongEnough(query)) {
            _searchState.value = AddSearchState.Idle
            _searchResults.value = emptyList()
            return
        }

        _searchState.value = AddSearchState.Searching
        searchJob =
            viewModelScope.launch {
                // 300ms debounce: wait before actually hitting the network, so typing multiple
                // characters fires one request instead of N. A cancellation (next keystroke) kills
                // this delay and starts the count over.
                delay(SEARCH_DEBOUNCE_MS)

                val result = searchMediaUseCase.execute(query)
                when (result) {
                    is Resource.Success -> {
                        _searchResults.value = result.data
                        _searchState.value =
                            if (result.data.isEmpty()) AddSearchState.NoResults else AddSearchState.Idle
                    }

                    is Resource.Error -> {
                        _searchState.value = AddSearchState.Error(AddSearchErrorReason.Generic(result.message))
                    }
                }
            }
    }

    /**
     * Selects a search result, initiating a confirmation request.
     *
     * Selectable only when no add is already in flight. Result is stored in [confirmationResult];
     * if the result carries a [MediaSearchResult.workKey], this method initiates a resolution to
     * its editions (GitHub Issue #63), which are surfaced in [editions].
     *
     * A work opens the edition picker, answered by [selectEdition] or [cancelSelection]. A result
     * with no work key is resolved to its ISBN and looked up straight away (#214).
     */
    public fun selectSearchResult(result: MediaSearchResult) {
        if (_uiState.value is AddBookUiState.Loading || searchProvider == null) return

        val workKey = result.workKey
        if (!workKey.isNullOrBlank() && resolveWorkToEditionsUseCase != null) {
            _confirmationResult.value = result
            _searchState.value = AddSearchState.ResolvingEditions
            _editions.value = emptyList()

            resolveJob?.cancel()
            resolveJob =
                viewModelScope.launch {
                    val editionsResult = resolveWorkToEditionsUseCase.execute(workKey)
                    when (editionsResult) {
                        is Resource.Success -> {
                            _editions.value = editionsResult.data
                            _searchState.value = AddSearchState.Idle
                        }

                        is Resource.Error -> {
                            _confirmationResult.value = null
                            _searchState.value =
                                AddSearchState.Error(AddSearchErrorReason.Generic(editionsResult.message))
                        }
                    }
                    // Only clear resolveJob if it still points to this job.
                    if (resolveJob === coroutineContext[Job]) {
                        resolveJob = null
                    }
                }
            return
        }

        val editionKey = result.coverEditionKey
        if (editionKey.isNullOrBlank()) {
            _searchState.value =
                AddSearchState.Error(AddSearchErrorReason.MissingEditionKey)
            return
        }

        // #214: no "Add this book?" dialog. The review the lookup opens is where the user confirms,
        // and asking twice is one question too many.
        resolveEditionAndLookUp(editionKey)
    }

    /** Selects a specific edition from [editions] and looks it up for review (#214). */
    public fun selectEdition(edition: BookEditionSearchResult) {
        _confirmationResult.value = null
        _editions.value = emptyList()
        lookUp(edition.isbn)
    }

    /**
     * Resolves a search result's edition to an ISBN and looks it up for review.
     *
     * Resolution happens via [searchProvider]. If successful, feeds the ISBN to [lookUp]. If
     * resolution fails, the error is surfaced in [searchState] so the search UI can show the
     * failure without leaving the flow.
     */
    private fun resolveEditionAndLookUp(editionKey: String) {
        val provider = searchProvider ?: return
        _searchState.value = AddSearchState.Searching

        // Cancel any in-flight resolution (ROADMAP Task 9 Phase B2 nitpick).
        resolveJob?.cancel()

        val job =
            viewModelScope.launch {
                val isbnResult = provider.resolveEditionToIsbn(editionKey)
                when (isbnResult) {
                    is Resource.Success -> {
                        val isbn = isbnResult.data
                        if (isbn.isNullOrBlank()) {
                            _searchState.value =
                                AddSearchState.Error(AddSearchErrorReason.MissingIsbn)
                        } else {
                            // Successfully resolved — now look the book up for review.
                            _searchState.value = AddSearchState.Idle
                            lookUp(isbn)
                        }
                    }

                    is Resource.Error -> {
                        _searchState.value = AddSearchState.Error(AddSearchErrorReason.Generic(isbnResult.message))
                    }
                }
                // Only clear resolveJob if it still points to this job.
                if (resolveJob === coroutineContext[Job]) {
                    resolveJob = null
                }
            }
        resolveJob = job
    }

    /** Discards the current [confirmationResult] and any resolved [editions]. */
    public fun cancelSelection() {
        _confirmationResult.value = null
        _editions.value = emptyList()
        resolveJob?.cancel()
        resolveJob = null
    }

    /** Clears the search state, results, query, and [editions]. */
    public fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _editions.value = emptyList()
        _searchState.value = AddSearchState.Idle
        searchJob?.cancel()
        resolveJob?.cancel()
        resolveJob = null
    }

    /**
     * Looks [isbn] up and opens it for review ([AddBookUiState.Reviewing]); nothing is saved until
     * [saveReview] (#214). Ignored while a lookup is in flight or a review is open -- guards against
     * a double tap firing two lookups, or a second pick replacing the review the user is editing.
     */
    public fun lookUp(isbn: String) {
        val current = _uiState.value
        if (current is AddBookUiState.Loading || current is AddBookUiState.Reviewing) return

        _uiState.value = AddBookUiState.Loading
        viewModelScope.launch {
            _uiState.value =
                when (val result = addBookByIsbnUseCase.lookup(isbn)) {
                    is Resource.Success -> AddBookUiState.Reviewing(result.data)
                    is Resource.Error -> AddBookUiState.Error(result.message)
                }
        }
    }

    /**
     * Saves the reviewed book with [values] as the user left them. Ignored unless a review is open
     * and not already saving, so a double-tapped Save adds the book once. A rejected save keeps the
     * review open with its message, so nothing the user typed is lost.
     */
    public fun saveReview(values: BookFormValues) {
        val reviewing = _uiState.value as? AddBookUiState.Reviewing ?: return
        if (reviewing.isSaving) return

        _uiState.value = reviewing.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            _uiState.value =
                when (val result = addBookByIsbnUseCase.save(reviewing.draft, values)) {
                    is Resource.Success -> AddBookUiState.Success(result.data)
                    is Resource.Error -> reviewing.copy(isSaving = false, errorMessage = result.message)
                }
        }
    }

    /**
     * Closes the review without saving, back to the search or ISBN entry it came from -- the
     * query and results are untouched. Ignored while a save is in flight: that write is already
     * happening, and closing the review over it would leave the user unsure whether it landed.
     */
    public fun cancelReview() {
        val reviewing = _uiState.value as? AddBookUiState.Reviewing ?: return
        if (reviewing.isSaving) return
        _uiState.value = AddBookUiState.Idle
    }

    /** Resets state back to [AddBookUiState.Idle], e.g. after the UI has consumed a terminal state. */
    public fun reset() {
        _uiState.value = AddBookUiState.Idle
    }

    private companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
    }
}
