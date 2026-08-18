package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.util.Resource
import com.hub.media.features.books.domain.BookIngestionUseCase
import com.hub.media.features.books.domain.SearchBooksUseCase
import com.hub.media.features.books.network.BookSearchProvider
import com.hub.media.features.books.network.BookSearchResult
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
 *   discovery pipeline, including debounce, cancellation, and result caching (via [SearchBooksUseCase]).
 *   Selecting a result ([selectSearchResult]) bridges from search to add: it resolves the edition
 *   key to an ISBN (via [searchProvider]) and, if successful, feeds that ISBN to the add flow.
 *
 * @param addBookByIsbnUseCase Runs the ISBN ingestion workflow. Typed as the narrow
 *   [BookIngestionUseCase] interface (rather than the concrete
 *   [com.hub.media.features.books.domain.AddBookByIsbnUseCase]) so tests can hand-roll a fake
 *   with no Ktor/Room/disk dependencies (AGENTS.md §5 "No Unnecessary Dependencies" — no mocking
 *   library).
 * @param searchBooksUseCase The search orchestrator (min-length checks, query normalization,
 *   LRU result cache). Injected so tests can provide a fake.
 * @param searchProvider Resolves selected search results to ISBNs. Injected so tests can provide a fake.
 */
public class AddBookViewModel(
    private val addBookByIsbnUseCase: BookIngestionUseCase,
    private val searchBooksUseCase: SearchBooksUseCase? = null,
    private val searchProvider: BookSearchProvider? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow<AddBookUiState>(AddBookUiState.Idle)
    public val uiState: StateFlow<AddBookUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    public val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<BookSearchResult>>(emptyList())
    public val searchResults: StateFlow<List<BookSearchResult>> = _searchResults.asStateFlow()

    private val _searchState = MutableStateFlow<AddSearchState>(AddSearchState.Idle)
    public val searchState: StateFlow<AddSearchState> = _searchState.asStateFlow()

    private val _confirmationResult = MutableStateFlow<BookSearchResult?>(null)
    public val confirmationResult: StateFlow<BookSearchResult?> = _confirmationResult.asStateFlow()

    private var searchJob: Job? = null
    private var resolveJob: Job? = null

    /**
     * Initiates a search for [query], with debounce (300ms) and previous-query cancellation.
     *
     * Queries shorter than [SearchBooksUseCase.MIN_SEARCH_QUERY_LENGTH] are answered immediately
     * with an empty result (from the cache, spending no network budget), distinguishing them from
     * zero-match searches (which report [AddSearchState.NoResults] instead). Call
     * [SearchBooksUseCase.isQueryLongEnough] before showing "keep typing" prompts to the user
     * without duplicating the length rules.
     *
     * Per ROADMAP Task 9 Phase B2: cancel-on-keystroke, debounce is 300ms per ROADMAP line 495.
     */
    public fun search(query: String) {
        _searchQuery.value = query

        // Cancel any in-flight search for the previous query.
        searchJob?.cancel()

        // Guard: search requires both use case and provider to be injected.
        if (searchBooksUseCase == null || searchProvider == null) return

        // If the add flow is in a terminal error state, clear it so searching can resume.
        if (_uiState.value is AddBookUiState.Error) {
            _uiState.value = AddBookUiState.Idle
        }

        if (_uiState.value is AddBookUiState.Loading) {
            // Don't start a new search if the add flow is in flight.
            return
        }

        // A too-short query immediately returns empty results without hitting the network.
        if (!searchBooksUseCase.isQueryLongEnough(query)) {
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

                val result = searchBooksUseCase.execute(query)
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
     * callers should show a confirmation dialog and then call [confirmSelection] or [cancelSelection].
     */
    public fun selectSearchResult(result: BookSearchResult) {
        if (_uiState.value is AddBookUiState.Loading || searchProvider == null) return

        val editionKey = result.coverEditionKey
        if (editionKey.isNullOrBlank()) {
            _searchState.value =
                AddSearchState.Error(AddSearchErrorReason.MissingEditionKey)
            return
        }

        _confirmationResult.value = result
    }

    /**
     * Confirms the current [confirmationResult], resolving it to an ISBN and initiating ingestion.
     *
     * Resolution happens via [searchProvider]. If successful, feeds the ISBN to [addBook]. If
     * resolution fails, the error is surfaced in [searchState] so the search UI can show the
     * failure without leaving the flow.
     */
    public fun confirmSelection() {
        val result = _confirmationResult.value ?: return
        _confirmationResult.value = null

        val editionKey = result.coverEditionKey ?: return // Already guarded by selectSearchResult

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
                            // Successfully resolved — now add the book.
                            _searchState.value = AddSearchState.Idle
                            addBook(isbn)
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

    /** Discards the current [confirmationResult]. */
    public fun cancelSelection() {
        _confirmationResult.value = null
        resolveJob?.cancel()
        resolveJob = null
    }

    /** Clears the search state, results, and query. */
    public fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _searchState.value = AddSearchState.Idle
        searchJob?.cancel()
        resolveJob?.cancel()
        resolveJob = null
    }

    /**
     * Runs ingestion for [isbn]. If a submission is already in flight
     * ([AddBookUiState.Loading]), this call is silently ignored — guards against a double-tap
     * firing two concurrent inserts. Callers must [reset] (or wait for the in-flight submission
     * to finish) before a retry is accepted.
     */
    public fun addBook(isbn: String) {
        if (_uiState.value is AddBookUiState.Loading) return

        _uiState.value = AddBookUiState.Loading
        viewModelScope.launch {
            _uiState.value =
                when (val result = addBookByIsbnUseCase.execute(isbn)) {
                    is Resource.Success -> AddBookUiState.Success(result.data)
                    is Resource.Error -> AddBookUiState.Error(result.message)
                }
        }
    }

    /** Resets state back to [AddBookUiState.Idle], e.g. after the UI has consumed a terminal state. */
    public fun reset() {
        _uiState.value = AddBookUiState.Idle
    }

    private companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
    }
}
