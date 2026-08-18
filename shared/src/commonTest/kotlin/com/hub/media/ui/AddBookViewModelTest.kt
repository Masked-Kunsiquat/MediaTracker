package com.hub.media.ui

import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.util.Resource
import com.hub.media.features.books.domain.BookIngestionUseCase
import com.hub.media.features.books.domain.FakeSearchBooksUseCase
import com.hub.media.features.books.domain.SearchBooksUseCase
import com.hub.media.features.books.network.BookSearchProvider
import com.hub.media.features.books.network.BookSearchResult
import com.hub.media.features.books.network.FakeBookSearchProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [AddBookViewModel] tests against hand-rolled fakes — no Ktor engine, no Room database, no disk
 * I/O — so this class is safe to run on the android unit-test variant too (unlike
 * [LibraryViewModelTest], which needs a real [com.hub.media.core.database.AppDatabase] and is
 * excluded there; see shared/build.gradle.kts).
 *
 * Tests cover both the existing add-by-ISBN flow (unchanged) and the new search/select/resolve
 * flow added for ROADMAP Task 9 Phase B2.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddBookViewModelTest {
    private val viewModels = ViewModelRegistry()

    @BeforeTest
    fun setUp() {
        // viewModelScope dispatches on Dispatchers.Main; UnconfinedTestDispatcher runs launched
        // coroutines eagerly so state transitions are observable synchronously without manually
        // pumping a TestCoroutineScheduler.
        viewModels.installMain()
    }

    @AfterTest
    fun tearDown() {
        // Cancel every ViewModel's viewModelScope before resetting Main -- see ViewModelRegistry's
        // KDoc for why this order matters (this class has no database to close in between).
        viewModels.clearAll()
        Dispatchers.resetMain()
    }

    private fun newViewModel(useCase: BookIngestionUseCase) = viewModels.track(AddBookViewModel(useCase))

    private fun newViewModelWithSearch(
        useCase: BookIngestionUseCase,
        searchUseCase: SearchBooksUseCase,
        searchProvider: BookSearchProvider,
    ) = viewModels.track(AddBookViewModel(useCase, searchUseCase, searchProvider))

    @Test
    fun initialState_isIdle() {
        val viewModel = newViewModel(FakeAddBookByIsbnUseCase())
        assertEquals(AddBookUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun addBook_emitsIdleThenLoadingThenSuccess() =
        runTest {
            val fake =
                FakeAddBookByIsbnUseCase(result = Resource.Success("media-1")).apply {
                    awaitGate = true
                }
            val viewModel = newViewModel(fake)

            assertEquals(AddBookUiState.Idle, viewModel.uiState.value)

            viewModel.addBook("9780135957059")
            assertEquals(AddBookUiState.Loading, viewModel.uiState.value)

            fake.release()
            val finalState = viewModel.uiState.first { it is AddBookUiState.Success }
            assertEquals(AddBookUiState.Success("media-1"), finalState)
        }

    @Test
    fun addBook_useCaseError_setsErrorState() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase(result = Resource.Error("Invalid ISBN: 'bad'"))
            val viewModel = newViewModel(fake)

            viewModel.addBook("bad")

            val finalState = viewModel.uiState.value
            assertIs<AddBookUiState.Error>(finalState)
            assertEquals("Invalid ISBN: 'bad'", finalState.message)
        }

    @Test
    fun addBook_concurrentSubmissionWhileLoading_isIgnored() =
        runTest {
            val fake =
                FakeAddBookByIsbnUseCase(result = Resource.Success("media-1")).apply {
                    awaitGate = true
                }
            val viewModel = newViewModel(fake)

            viewModel.addBook("9780135957059")
            assertEquals(AddBookUiState.Loading, viewModel.uiState.value)

            // Second submission while the first is still in flight must be a no-op.
            viewModel.addBook("9780132350884")
            assertEquals(1, fake.callCount)

            fake.release()
            viewModel.uiState.first { it is AddBookUiState.Success }
            assertEquals(1, fake.callCount)
        }

    @Test
    fun reset_returnsToIdleAfterTerminalState() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase(result = Resource.Success("media-1"))
            val viewModel = newViewModel(fake)

            viewModel.addBook("9780135957059")
            assertIs<AddBookUiState.Success>(viewModel.uiState.value)

            viewModel.reset()

            assertEquals(AddBookUiState.Idle, viewModel.uiState.value)
        }

    @Test
    fun addBook_afterReset_isAcceptedAgain() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase(result = Resource.Success("media-1"))
            val viewModel = newViewModel(fake)

            viewModel.addBook("9780135957059")
            viewModel.reset()
            viewModel.addBook("9780132350884")

            assertEquals(2, fake.callCount)
            assertIs<AddBookUiState.Success>(viewModel.uiState.value)
        }

    // ============ Search integration tests (ROADMAP Task 9 Phase B2) ============

    @Test
    fun initialSearchState_isIdle() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val searchFake = FakeSearchBooksUseCase()
            val providerFake = FakeBookSearchProvider()
            val viewModel = newViewModelWithSearch(fake, searchFake, providerFake)

            assertEquals(AddSearchState.Idle, viewModel.searchState.value)
            assertEquals("", viewModel.searchQuery.value)
            assertEquals(emptyList(), viewModel.searchResults.value)
        }

    @Test
    fun search_queryTooShort_returnsEmptyWithoutHittingProvider() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val searchFake = FakeSearchBooksUseCase(minLengthIsEnough = false)
            val providerFake = FakeBookSearchProvider()
            val viewModel = newViewModelWithSearch(fake, searchFake, providerFake)

            viewModel.search("ab")

            assertEquals(AddSearchState.Idle, viewModel.searchState.value)
            assertEquals(emptyList(), viewModel.searchResults.value)
            assertEquals(0, searchFake.executeCallCount)
        }

    @Test
    fun search_queryLongEnough_hitsProvider() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val result = bookSearchResult("The Hobbit")
            val searchFake = FakeSearchBooksUseCase(results = listOf(result))
            val providerFake = FakeBookSearchProvider()
            val viewModel = newViewModelWithSearch(fake, searchFake, providerFake)

            viewModel.search("hob")
            advanceUntilIdle()

            assertEquals(AddSearchState.Idle, viewModel.searchState.value)
            assertEquals(listOf(result), viewModel.searchResults.value)
            assertEquals(1, searchFake.executeCallCount)
        }

    @Test
    fun search_emptyResults_setsNoResultsState() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val searchFake = FakeSearchBooksUseCase(results = emptyList())
            val providerFake = FakeBookSearchProvider()
            val viewModel = newViewModelWithSearch(fake, searchFake, providerFake)

            viewModel.search("hobbit")
            advanceUntilIdle()

            assertIs<AddSearchState.NoResults>(viewModel.searchState.value)
            assertEquals(emptyList(), viewModel.searchResults.value)
        }

    @Test
    fun search_error_setsErrorState() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val searchFake =
                FakeSearchBooksUseCase(error = Resource.Error("Network error"))
            val providerFake = FakeBookSearchProvider()
            val viewModel = newViewModelWithSearch(fake, searchFake, providerFake)

            viewModel.search("hobbit")
            advanceUntilIdle()

            val errorState = viewModel.searchState.value
            assertIs<AddSearchState.Error>(errorState)
            assertEquals("Network error", errorState.message)
        }

    @Test
    fun search_newQueryCancelsPrevious() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val searchFake = FakeSearchBooksUseCase(delay = 100)
            val providerFake = FakeBookSearchProvider()
            val viewModel = newViewModelWithSearch(fake, searchFake, providerFake)

            viewModel.search("hob")
            viewModel.search("hobbit") // Cancel the first, start the second

            advanceUntilIdle()

            // The first search (with delay) is cancelled; only the second call counts
            assertEquals(1, searchFake.executeCallCount)
        }

    @Test
    fun search_queryUpdate_recorded() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val searchFake = FakeSearchBooksUseCase()
            val providerFake = FakeBookSearchProvider()
            val viewModel = newViewModelWithSearch(fake, searchFake, providerFake)

            viewModel.search("hobbit")

            assertEquals("hobbit", viewModel.searchQuery.value)
        }

    @Test
    fun clearSearch_resetsAllSearchState() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val result = bookSearchResult("The Hobbit")
            val searchFake = FakeSearchBooksUseCase(results = listOf(result))
            val providerFake = FakeBookSearchProvider()
            val viewModel = newViewModelWithSearch(fake, searchFake, providerFake)

            viewModel.search("hobbit")
            advanceUntilIdle()
            assertEquals(1, viewModel.searchResults.value.size)

            viewModel.clearSearch()

            assertEquals("", viewModel.searchQuery.value)
            assertEquals(emptyList(), viewModel.searchResults.value)
            assertEquals(AddSearchState.Idle, viewModel.searchState.value)
        }

    @Test
    fun selectSearchResult_resolvesEditionToIsbn_andAddBook() =
        runTest {
            val addFake = FakeAddBookByIsbnUseCase(result = Resource.Success("media-1"))
            val searchFake = FakeSearchBooksUseCase()
            val providerFake = FakeBookSearchProvider(isbn = "9780547928227")
            val viewModel = newViewModelWithSearch(addFake, searchFake, providerFake)

            val result =
                bookSearchResult("The Hobbit").copy(coverEditionKey = "OL51711263M")

            viewModel.selectSearchResult(result)

            // After selection and resolution, addBook should have been called
            assertEquals(1, addFake.callCount)

            // Verify the edition key was resolved
            assertEquals(1, providerFake.resolveCallCount)
            assertEquals("OL51711263M", providerFake.lastResolvedEditionKey)
        }

    @Test
    fun selectSearchResult_noEditionKey_setsError() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val searchFake = FakeSearchBooksUseCase()
            val providerFake = FakeBookSearchProvider()
            val viewModel = newViewModelWithSearch(fake, searchFake, providerFake)

            val result =
                bookSearchResult("The Hobbit").copy(coverEditionKey = null)

            viewModel.selectSearchResult(result)

            assertIs<AddSearchState.Error>(viewModel.searchState.value)
            val errorState = viewModel.searchState.value as AddSearchState.Error
            assertEquals("Selected result has no edition key; cannot resolve to ISBN", errorState.message)
        }

    @Test
    fun selectSearchResult_resolutionFails_setsError() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val searchFake = FakeSearchBooksUseCase()
            val providerFake =
                FakeBookSearchProvider(error = Resource.Error("Open Library failed"))
            val viewModel = newViewModelWithSearch(fake, searchFake, providerFake)

            val result =
                bookSearchResult("The Hobbit").copy(coverEditionKey = "OL51711263M")

            viewModel.selectSearchResult(result)

            assertIs<AddSearchState.Error>(viewModel.searchState.value)
            val errorState = viewModel.searchState.value as AddSearchState.Error
            assertEquals("Open Library failed", errorState.message)
        }

    @Test
    fun selectSearchResult_editionHasNoIsbn_setsError() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val searchFake = FakeSearchBooksUseCase()
            val providerFake = FakeBookSearchProvider(isbn = null)
            val viewModel = newViewModelWithSearch(fake, searchFake, providerFake)

            val result =
                bookSearchResult("The Hobbit").copy(coverEditionKey = "OL51711263M")

            viewModel.selectSearchResult(result)

            assertIs<AddSearchState.Error>(viewModel.searchState.value)
            val errorState = viewModel.searchState.value as AddSearchState.Error
            assertEquals(
                "Selected edition has no ISBN in Open Library; cannot add this book this way",
                errorState.message,
            )
        }

    @Test
    fun selectSearchResult_whileAddInFlight_ignored() =
        runTest {
            val addFake =
                FakeAddBookByIsbnUseCase(result = Resource.Success("media-1")).apply {
                    awaitGate = true
                }
            val searchFake = FakeSearchBooksUseCase()
            val providerFake = FakeBookSearchProvider(isbn = "9780547928227")
            val viewModel = newViewModelWithSearch(addFake, searchFake, providerFake)

            // Start an add operation
            viewModel.addBook("9780135957059")
            assertEquals(AddBookUiState.Loading, viewModel.uiState.value)

            // Try to select a search result while add is in flight
            val result =
                bookSearchResult("The Hobbit").copy(coverEditionKey = "OL51711263M")
            viewModel.selectSearchResult(result)

            // The selection should be ignored
            assertEquals(0, providerFake.resolveCallCount)
        }

    @Test
    fun search_blockedWhileAddInFlight() =
        runTest {
            val addFake =
                FakeAddBookByIsbnUseCase(result = Resource.Success("media-1")).apply {
                    awaitGate = true
                }
            val searchFake = FakeSearchBooksUseCase()
            val providerFake = FakeBookSearchProvider()
            val viewModel = newViewModelWithSearch(addFake, searchFake, providerFake)

            // Start an add operation
            viewModel.addBook("9780135957059")
            assertEquals(AddBookUiState.Loading, viewModel.uiState.value)

            // Try to search while add is in flight
            viewModel.search("hobbit")

            // Search should not proceed; no new call should be made
            assertEquals(0, searchFake.executeCallCount)
        }

    private companion object {
        fun bookSearchResult(
            title: String,
            editionKey: String = "OL51711263M",
        ) = BookSearchResult(
            title = title,
            provider = IdentifierProvider.OPEN_LIBRARY,
            coverEditionKey = editionKey,
        )
    }
}
