package com.hub.media.ui

import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.util.Resource
import com.hub.media.features.books.domain.BookIngestionUseCase
import com.hub.media.features.media.domain.FakeSearchMediaUseCase
import com.hub.media.features.books.domain.ResolveWorkToEditionsUseCase
import com.hub.media.features.books.network.BookEditionSearchResult
import com.hub.media.features.books.network.BookSearchProvider
import com.hub.media.features.books.network.FakeBookSearchProvider
import com.hub.media.features.media.domain.SearchMediaUseCase
import com.hub.media.features.media.network.MediaSearchResult
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
        searchMediaUseCase: SearchMediaUseCase,
        searchProvider: BookSearchProvider,
        resolveWorkToEditionsUseCase: ResolveWorkToEditionsUseCase? = null,
    ) = viewModels.track(
        AddBookViewModel(
            addBookByIsbnUseCase = useCase,
            searchMediaUseCase = searchMediaUseCase,
            searchProvider = searchProvider,
            resolveWorkToEditionsUseCase = resolveWorkToEditionsUseCase,
        ),
    )

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

            val finalState = viewModel.uiState.first { it is AddBookUiState.Error }
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
            viewModel.uiState.first { it is AddBookUiState.Success }

            viewModel.reset()

            assertEquals(AddBookUiState.Idle, viewModel.uiState.value)
        }

    @Test
    fun addBook_afterReset_isAcceptedAgain() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase(result = Resource.Success("media-1"))
            val viewModel = newViewModel(fake)

            viewModel.addBook("9780135957059")
            viewModel.uiState.first { it is AddBookUiState.Success }
            viewModel.reset()
            viewModel.addBook("9780132350884")
            val finalState = viewModel.uiState.first { it is AddBookUiState.Success }

            assertEquals(2, fake.callCount)
            assertIs<AddBookUiState.Success>(finalState)
        }

    // ============ Search integration tests (ROADMAP Task 9 Phase B2) ============

    @Test
    fun initialSearchState_isIdle() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val searchFake = FakeSearchMediaUseCase()
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
            val searchFake = FakeSearchMediaUseCase(minLengthIsEnough = false)
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
            val searchFake = FakeSearchMediaUseCase(results = listOf(result))
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
            val searchFake = FakeSearchMediaUseCase(results = emptyList<MediaSearchResult>())
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
                FakeSearchMediaUseCase(error = Resource.Error("Network error"))
            val providerFake = FakeBookSearchProvider()
            val viewModel = newViewModelWithSearch(fake, searchFake, providerFake)

            viewModel.search("hobbit")
            advanceUntilIdle()

            val errorState = viewModel.searchState.value
            assertIs<AddSearchState.Error>(errorState)
            val reason = errorState.reason
            assertIs<AddSearchErrorReason.Generic>(reason)
            assertEquals("Network error", reason.message)
        }

    @Test
    fun search_newQueryCancelsPrevious() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val searchFake = FakeSearchMediaUseCase(delay = 100)
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
            val searchFake = FakeSearchMediaUseCase()
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
            val searchFake = FakeSearchMediaUseCase(results = listOf(result))
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
    fun selectSearchResult_withWorkKey_resolvesToEditions() =
        runTest {
            val result =
                bookSearchResult("The Hobbit").copy(
                    workKey = "/works/OL27482W",
                    coverEditionKey = null,
                )
            val addFake = FakeAddBookByIsbnUseCase()
            val searchFake = FakeSearchMediaUseCase(results = listOf(result))
            val providerFake = FakeBookSearchProvider()
            val resolveUseCase = ResolveWorkToEditionsUseCase(providerFake)
            val viewModel = newViewModelWithSearch(addFake, searchFake, providerFake, resolveUseCase)

            viewModel.search("hobbit")
            advanceUntilIdle()

            viewModel.selectSearchResult(result)

            // With UnconfinedTestDispatcher, the resolution happens eagerly.
            // So we check the final state.
            assertEquals(AddSearchState.Idle, viewModel.searchState.value)
            assertEquals(emptyList(), viewModel.editions.value)
            assertEquals(result, viewModel.confirmationResult.value)
        }

    @Test
    fun selectEdition_initiatesIngestion() =
        runTest {
            val addFake = FakeAddBookByIsbnUseCase(result = Resource.Success("media-1"))
            val searchFake = FakeSearchMediaUseCase()
            val providerFake = FakeBookSearchProvider()
            val viewModel = newViewModelWithSearch(addFake, searchFake, providerFake)

            val edition =
                BookEditionSearchResult(
                    title = "The Hobbit",
                    publisher = "Allen & Unwin",
                    publishDate = "1937",
                    isbn = "9780547928227",
                    pageCount = 310,
                    coverThumbnailUrl = null,
                    editionKey = "OL51711263M",
                    provider = IdentifierProvider.OPEN_LIBRARY,
                )

            viewModel.selectEdition(edition)

            // Await success state instead of reading .value immediately (AGENTS.md §7)
            val finalState = viewModel.uiState.first { it is AddBookUiState.Success }
            assertIs<AddBookUiState.Success>(finalState)
            assertEquals(1, addFake.callCount)
        }

    @Test
    fun selectSearchResult_resolvesEditionToIsbn_andAddBook() =
        runTest {
            val result = bookSearchResult("The Hobbit").copy(coverEditionKey = "OL51711263M")
            val addFake = FakeAddBookByIsbnUseCase(result = Resource.Success("media-1"))
            val searchFake = FakeSearchMediaUseCase(results = listOf(result))
            val providerFake = FakeBookSearchProvider(isbn = "9780547928227")
            val viewModel = newViewModelWithSearch(addFake, searchFake, providerFake)

            // Populate search results first
            viewModel.search("hobbit")
            advanceUntilIdle()
            assertEquals(1, viewModel.searchResults.value.size)

            viewModel.selectSearchResult(result)
            assertEquals(result, viewModel.confirmationResult.value, "confirmationResult should be populated")

            viewModel.confirmSelection()
            advanceUntilIdle()

            // After selection and resolution, addBook should have been called
            assertEquals(1, addFake.callCount, "addBook should have been called once")

            // Verify the edition key was resolved
            assertEquals(1, providerFake.resolveCallCount, "resolveEditionToIsbn should have been called once")
            assertEquals("OL51711263M", providerFake.lastResolvedEditionKey, "resolved edition key mismatch")

            // Search state should be reset to Idle after resolution, but results kept
            // until the screen clears them on success.
            assertEquals(AddSearchState.Idle, viewModel.searchState.value, "searchState should be Idle")
            assertEquals(1, viewModel.searchResults.value.size, "searchResults should not be cleared yet")
        }

    @Test
    fun selectSearchResult_noEditionKey_setsError() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val searchFake = FakeSearchMediaUseCase()
            val providerFake = FakeBookSearchProvider()
            val viewModel = newViewModelWithSearch(fake, searchFake, providerFake)

            val result =
                bookSearchResult("The Hobbit").copy(coverEditionKey = null)

            viewModel.selectSearchResult(result)
            advanceUntilIdle()

            assertIs<AddSearchState.Error>(viewModel.searchState.value)
            val errorState = viewModel.searchState.value as AddSearchState.Error
            assertIs<AddSearchErrorReason.MissingEditionKey>(errorState.reason)
        }

    @Test
    fun selectSearchResult_resolutionFails_setsError() =
        runTest {
            val result = bookSearchResult("The Hobbit").copy(coverEditionKey = "OL51711263M")
            val fake = FakeAddBookByIsbnUseCase()
            val searchFake = FakeSearchMediaUseCase(results = listOf(result))
            val providerFake =
                FakeBookSearchProvider(error = Resource.Error("Open Library failed"))
            val viewModel = newViewModelWithSearch(fake, searchFake, providerFake)

            // Populate search results first
            viewModel.search("hobbit")
            advanceUntilIdle()
            assertEquals(1, viewModel.searchResults.value.size)

            viewModel.selectSearchResult(result)
            viewModel.confirmSelection()
            advanceUntilIdle()

            assertIs<AddSearchState.Error>(viewModel.searchState.value, "expected Error state")
            val errorState = viewModel.searchState.value as AddSearchState.Error
            val reason = errorState.reason
            assertIs<AddSearchErrorReason.Generic>(reason)
            assertEquals("Open Library failed", reason.message, "error message mismatch")

            // Results must NOT be cleared on failure (ROADMAP Task 9 Phase B2 PR review)
            assertEquals(1, viewModel.searchResults.value.size, "searchResults should NOT be cleared on failure")
        }

    @Test
    fun selectSearchResult_editionHasNoIsbn_setsError() =
        runTest {
            val result = bookSearchResult("The Hobbit").copy(coverEditionKey = "OL51711263M")
            val fake = FakeAddBookByIsbnUseCase()
            val searchFake = FakeSearchMediaUseCase(results = listOf(result))
            val providerFake = FakeBookSearchProvider(isbn = null)
            val viewModel = newViewModelWithSearch(fake, searchFake, providerFake)

            // Populate search results first
            viewModel.search("hobbit")
            advanceUntilIdle()

            viewModel.selectSearchResult(result)
            viewModel.confirmSelection()
            advanceUntilIdle()

            assertIs<AddSearchState.Error>(viewModel.searchState.value)
            val errorState = viewModel.searchState.value as AddSearchState.Error
            assertIs<AddSearchErrorReason.MissingIsbn>(errorState.reason)

            // Results must NOT be cleared on failure (ROADMAP Task 9 Phase B2 PR review)
            assertEquals(1, viewModel.searchResults.value.size)
        }

    @Test
    fun selectSearchResult_whileAddInFlight_ignored() =
        runTest {
            val addFake =
                FakeAddBookByIsbnUseCase(result = Resource.Success("media-1")).apply {
                    awaitGate = true
                }
            val searchFake = FakeSearchMediaUseCase()
            val providerFake = FakeBookSearchProvider(isbn = "9780547928227")
            val viewModel = newViewModelWithSearch(addFake, searchFake, providerFake)

            // Start an add operation
            viewModel.addBook("9780135957059")
            assertEquals(AddBookUiState.Loading, viewModel.uiState.value)

            // Try to select a search result while add is in flight
            val result =
                bookSearchResult("The Hobbit").copy(coverEditionKey = "OL51711263M")
            viewModel.selectSearchResult(result)

            // The selection should be ignored (confirmationResult remains null)
            assertEquals(null, viewModel.confirmationResult.value)
        }

    @Test
    fun search_blockedWhileAddInFlight() =
        runTest {
            val addFake =
                FakeAddBookByIsbnUseCase(result = Resource.Success("media-1")).apply {
                    awaitGate = true
                }
            val searchFake = FakeSearchMediaUseCase()
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

    @Test
    fun search_afterAddFailure_clearsErrorAndProceeds() =
        runTest {
            val addFake = FakeAddBookByIsbnUseCase(result = Resource.Error("Add failed"))
            val searchFake = FakeSearchMediaUseCase(results = listOf(bookSearchResult("Found")))
            val providerFake = FakeBookSearchProvider()
            val viewModel = newViewModelWithSearch(addFake, searchFake, providerFake)

            // 1. Fail an add operation
            viewModel.addBook("9780135957059")
            viewModel.uiState.first { it is AddBookUiState.Error }

            // 2. Start a search
            viewModel.search("hobbit")
            advanceUntilIdle()

            // Search should have proceeded, and uiState should be back to Idle
            assertEquals(1, searchFake.executeCallCount)
            assertEquals(AddBookUiState.Idle, viewModel.uiState.value)
        }

    @Test
    fun cancelSelection_clearsConfirmationResult() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val searchFake = FakeSearchMediaUseCase()
            val providerFake = FakeBookSearchProvider()
            val viewModel = newViewModelWithSearch(fake, searchFake, providerFake)

            val result = bookSearchResult("The Hobbit")
            viewModel.selectSearchResult(result)
            assertEquals(result, viewModel.confirmationResult.value)

            viewModel.cancelSelection()
            assertEquals(null, viewModel.confirmationResult.value)
        }

    private companion object {
        fun bookSearchResult(
            title: String,
            editionKey: String = "OL51711263M",
        ) = MediaSearchResult(
            title = title,
            type = MediaType.BOOK,
            provider = IdentifierProvider.OPEN_LIBRARY,
            coverEditionKey = editionKey,
        )
    }
}
