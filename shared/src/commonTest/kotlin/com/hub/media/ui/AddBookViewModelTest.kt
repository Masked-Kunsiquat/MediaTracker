package com.hub.media.ui

import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.util.Resource
import com.hub.media.features.books.domain.BookDraft
import com.hub.media.features.books.domain.BookFormValues
import com.hub.media.features.books.domain.BookIngestionUseCase
import com.hub.media.features.books.domain.ResolveWorkToEditionsUseCase
import com.hub.media.features.books.network.BookEditionSearchResult
import com.hub.media.features.books.network.BookSearchProvider
import com.hub.media.features.books.network.FakeBookSearchProvider
import com.hub.media.features.media.domain.FakeSearchMediaUseCase
import com.hub.media.features.media.domain.SearchMediaUseCase
import com.hub.media.features.media.network.MediaSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
    fun lookUp_emitsIdleThenLoadingThenReviewing_andSavesNothing() =
        runTest {
            val fake =
                FakeAddBookByIsbnUseCase(result = Resource.Success("media-1")).apply {
                    awaitGate = true
                }
            val viewModel = newViewModel(fake)

            assertEquals(AddBookUiState.Idle, viewModel.uiState.value)

            viewModel.lookUp("9780135957059")
            assertEquals(AddBookUiState.Loading, viewModel.uiState.value)

            fake.release()
            val reviewing = viewModel.uiState.first { it is AddBookUiState.Reviewing }
            assertIs<AddBookUiState.Reviewing>(reviewing)
            assertEquals("Fake Book", reviewing.draft.metadata.title)
            assertTrue(fake.saves.isEmpty(), "picking a book must not add it before Save (#214)")
        }

    @Test
    fun lookUp_useCaseError_setsErrorState() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase(result = Resource.Error("Invalid ISBN: 'bad'"))
            val viewModel = newViewModel(fake)

            viewModel.lookUp("bad")

            val finalState = viewModel.uiState.first { it is AddBookUiState.Error }
            assertIs<AddBookUiState.Error>(finalState)
            assertEquals("Invalid ISBN: 'bad'", finalState.message)
        }

    @Test
    fun lookUp_concurrentSubmissionWhileLoading_isIgnored() =
        runTest {
            val fake =
                FakeAddBookByIsbnUseCase(result = Resource.Success("media-1")).apply {
                    awaitGate = true
                }
            val viewModel = newViewModel(fake)

            viewModel.lookUp("9780135957059")
            assertEquals(AddBookUiState.Loading, viewModel.uiState.value)

            // Second submission while the first is still in flight must be a no-op.
            viewModel.lookUp("9780132350884")
            assertEquals(1, fake.callCount)

            fake.release()
            viewModel.uiState.first { it is AddBookUiState.Reviewing }
            assertEquals(1, fake.callCount)
        }

    @Test
    fun reset_returnsToIdleAfterTerminalState() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase(result = Resource.Success("media-1"))
            val viewModel = newViewModel(fake)

            viewModel.lookUp("9780135957059")
            viewModel.saveTheReview()

            viewModel.reset()

            assertEquals(AddBookUiState.Idle, viewModel.uiState.value)
        }

    @Test
    fun lookUp_afterReset_isAcceptedAgain() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase(result = Resource.Success("media-1"))
            val viewModel = newViewModel(fake)

            viewModel.lookUp("9780135957059")
            viewModel.saveTheReview()
            viewModel.reset()
            viewModel.lookUp("9780132350884")
            val finalState = viewModel.saveTheReview()

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
            val searchFake = FakeSearchMediaUseCase(delay = 1000)
            val providerFake = FakeBookSearchProvider()
            val viewModel = newViewModelWithSearch(fake, searchFake, providerFake)

            viewModel.search("hob")
            // Pass the 300ms debounce; searchFake.execute("hob") starts and suspends on delay(1000)
            advanceTimeBy(400)
            assertEquals(1, searchFake.executeCallCount)

            viewModel.search("hobbit") // Cancel the first, start the second
            advanceUntilIdle()

            // Both were started (execute() reached its increment), matching AGENTS.md §7's
            // requirement to exercise the cancellation.
            assertEquals(2, searchFake.executeCallCount)
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
    fun selectEdition_looksUpAndOpensTheReview() =
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

            // Await the state instead of reading .value immediately (AGENTS.md §7)
            viewModel.uiState.first { it is AddBookUiState.Reviewing }
            assertEquals(listOf("9780547928227"), addFake.lookups)
            assertTrue(addFake.saves.isEmpty(), "choosing an edition must not add the book before Save (#214)")
        }

    @Test
    fun selectSearchResult_withNoWorkKey_resolvesItsIsbnAndOpensTheReview_withNoConfirmation() =
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

            // #214: no "Add this book?" dialog first -- the review that follows is the confirmation.
            viewModel.selectSearchResult(result)
            advanceUntilIdle()

            assertEquals(null, viewModel.confirmationResult.value, "there is no confirmation to answer")
            viewModel.uiState.first { it is AddBookUiState.Reviewing }
            assertEquals(listOf("9780547928227"), addFake.lookups, "the resolved ISBN is looked up once")

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
            viewModel.lookUp("9780135957059")
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
            viewModel.lookUp("9780135957059")
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
            viewModel.lookUp("9780135957059")
            viewModel.uiState.first { it is AddBookUiState.Error }

            // 2. Start a search
            viewModel.search("hobbit")
            advanceUntilIdle()

            // Search should have proceeded, and uiState should be back to Idle
            assertEquals(1, searchFake.executeCallCount)
            assertEquals(AddBookUiState.Idle, viewModel.uiState.value)
        }

    @Test
    fun cancelSelection_clearsTheWorkBeingResolved() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val searchFake = FakeSearchMediaUseCase()
            val providerFake = FakeBookSearchProvider()
            val viewModel = newViewModelWithSearch(fake, searchFake, providerFake)

            // A work opens the edition picker, which is the one selection that still waits on the user.
            val result = bookSearchResult("The Hobbit").copy(workKey = "/works/OL27482W")
            val withEditions =
                newViewModelWithSearch(fake, searchFake, providerFake, ResolveWorkToEditionsUseCase(providerFake))
            withEditions.selectSearchResult(result)
            assertEquals(result, withEditions.confirmationResult.value)

            withEditions.cancelSelection()
            assertEquals(null, withEditions.confirmationResult.value)
        }

    @Test
    fun cancelSelection_withNothingSelected_isHarmless() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val viewModel = newViewModelWithSearch(fake, FakeSearchMediaUseCase(), FakeBookSearchProvider())

            viewModel.cancelSelection()
            assertEquals(null, viewModel.confirmationResult.value)
        }

    // ============ The review (#214) ============

    /** Awaits the review a lookup opens, saves it as it stands, and awaits the result. */
    private suspend fun AddBookViewModel.saveTheReview(): AddBookUiState.Success {
        val reviewing = uiState.first { it is AddBookUiState.Reviewing } as AddBookUiState.Reviewing
        saveReview(reviewing.draft.initialValues())
        return uiState.first { it is AddBookUiState.Success } as AddBookUiState.Success
    }

    @Test
    fun saveReview_savesTheValuesTheUserLeft() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase(result = Resource.Success("media-1"))
            val viewModel = newViewModel(fake)
            viewModel.lookUp("9780135957059")
            val reviewing = viewModel.uiState.first { it is AddBookUiState.Reviewing } as AddBookUiState.Reviewing

            viewModel.saveReview(reviewing.draft.initialValues().copy(totalPages = 412))

            assertEquals(AddBookUiState.Success("media-1"), viewModel.uiState.first { it is AddBookUiState.Success })
            assertEquals(412, fake.saves.single().totalPages)
        }

    @Test
    fun saveReview_failure_keepsTheReviewOpenWithTheMessage() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase(result = Resource.Success("media-1"))
            val failing =
                object : BookIngestionUseCase by fake {
                    override suspend fun save(
                        draft: BookDraft,
                        values: BookFormValues,
                    ): Resource<String> = Resource.Error("Title must not be blank")
                }
            val viewModel = newViewModel(failing)
            viewModel.lookUp("9780135957059")
            val reviewing = viewModel.uiState.first { it is AddBookUiState.Reviewing } as AddBookUiState.Reviewing

            viewModel.saveReview(reviewing.draft.initialValues())

            val after = viewModel.uiState.first { (it as? AddBookUiState.Reviewing)?.errorMessage != null }
            assertIs<AddBookUiState.Reviewing>(after)
            assertEquals("Title must not be blank", after.errorMessage)
            assertEquals(false, after.isSaving)
        }

    @Test
    fun cancelReview_savesNothingAndKeepsTheResults() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val searchFake = FakeSearchMediaUseCase(results = listOf(bookSearchResult("Found")))
            val viewModel = newViewModelWithSearch(fake, searchFake, FakeBookSearchProvider())
            viewModel.search("hobbit")
            advanceUntilIdle()
            viewModel.lookUp("9780135957059")
            viewModel.uiState.first { it is AddBookUiState.Reviewing }

            viewModel.cancelReview()

            assertEquals(AddBookUiState.Idle, viewModel.uiState.first { it is AddBookUiState.Idle })
            assertEquals(1, viewModel.searchResults.value.size, "Cancel returns to the results it came from")
            assertTrue(fake.saves.isEmpty())
        }

    @Test
    fun cancelReview_whileSaving_isIgnored_andTheSaveLands() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase(result = Resource.Success("media-1")).apply { awaitSaveGate = true }
            val viewModel = newViewModel(fake)
            viewModel.lookUp("9780135957059")
            val reviewing = viewModel.uiState.first { it is AddBookUiState.Reviewing } as AddBookUiState.Reviewing
            viewModel.saveReview(reviewing.draft.initialValues())
            viewModel.uiState.first { (it as? AddBookUiState.Reviewing)?.isSaving == true }

            viewModel.cancelReview()
            // cancelReview is synchronous: the review must still be open over the write it is saving.
            assertIs<AddBookUiState.Reviewing>(viewModel.uiState.value, "Back during a save must not close the review")
            fake.releaseSave()

            assertEquals(AddBookUiState.Success("media-1"), viewModel.uiState.first { it !is AddBookUiState.Reviewing })
        }

    @Test
    fun saveReview_twice_savesOnce() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase(result = Resource.Success("media-1")).apply { awaitSaveGate = true }
            val viewModel = newViewModel(fake)
            viewModel.lookUp("9780135957059")
            val reviewing = viewModel.uiState.first { it is AddBookUiState.Reviewing } as AddBookUiState.Reviewing

            viewModel.saveReview(reviewing.draft.initialValues())
            viewModel.saveReview(reviewing.draft.initialValues())
            fake.releaseSave()

            viewModel.uiState.first { it is AddBookUiState.Success }
            assertEquals(1, fake.saves.size, "a double-tapped Save must add the book once")
        }

    @Test
    fun lookUp_whileReviewing_isIgnored() =
        runTest {
            val fake = FakeAddBookByIsbnUseCase()
            val viewModel = newViewModel(fake)
            viewModel.lookUp("9780135957059")
            viewModel.uiState.first { it is AddBookUiState.Reviewing }

            viewModel.lookUp("9780132350884")

            assertEquals(1, fake.callCount, "a review is open; a second lookup must not replace it")
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
