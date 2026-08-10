package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.storage.cleanupTestTempDir
import com.hub.media.core.storage.createTestTempDir
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.domain.BulkDeleteUseCase
import com.hub.media.features.books.domain.DeleteBooksSummary
import com.hub.media.features.books.domain.DeleteBooksUseCase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * [LibraryViewModel] tests against a real in-memory [AppDatabase] (via [testAppDatabase], the
 * same builder the DAO/repository tests use) so the [BookRepository.observeAllBooks] -> `map` ->
 * `stateIn` wiring is exercised end to end, not just mocked. Because it needs a real database,
 * this class is excluded from the android unit-test variant by exact class name in
 * shared/build.gradle.kts, same as the DAO/repository/use-case tests — `:shared:jvmTest` is the
 * authoritative gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: BookRepository
    private lateinit var viewModel: LibraryViewModel
    private lateinit var tempDir: String
    private val viewModels = ViewModelRegistry()

    @BeforeTest
    fun setUp() {
        // viewModelScope dispatches on Dispatchers.Main; UnconfinedTestDispatcher runs launched
        // coroutines eagerly so uiState updates are observable without manually pumping a
        // TestCoroutineScheduler.
        viewModels.installMain()
        db = testAppDatabase()
        repository = BookRepository(db)
        // A real storage directory rather than a fake: the bulk delete's cover cleanup is
        // exercised properly in DeleteBooksUseCaseTest, but wiring the real thing here means these
        // tests fail if the two ever stop fitting together.
        tempDir = runBlocking { createTestTempDir() }
        viewModel = viewModels.track(
            LibraryViewModel(repository, DeleteBooksUseCase(db, LocalImageStorageManager(tempDir))),
        )
    }

    @AfterTest
    fun tearDown() {
        // Cancel every ViewModel's viewModelScope (and its stateIn/WhileSubscribed sharing
        // coroutine) before closing the database or resetting Main -- see ViewModelRegistry's
        // KDoc for why this order matters.
        viewModels.clearAll()
        db.close()
        runBlocking { cleanupTestTempDir(tempDir) }
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_initialValue_isEmpty() {
        assertTrue(viewModel.uiState.value.books.isEmpty())
        assertTrue(viewModel.uiState.value.isEmpty)
    }

    @Test
    fun uiState_emitsInsertedBookAndClearsIsEmpty() = runTest {
        val result = repository.addBook(title = "Dune", format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(result)

        val updated = viewModel.uiState.first { it.books.isNotEmpty() }

        assertEquals(1, updated.books.size)
        assertEquals("Dune", updated.books.first().mediaItem.title)
        assertEquals(false, updated.isEmpty)
    }

    @Test
    fun deleteBook_removesBookFromUiStateAndRestoresIsEmpty() = runTest {
        val addResult = repository.addBook(title = "Foundation", format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data

        val withBook = viewModel.uiState.first { it.books.isNotEmpty() }
        assertEquals(1, withBook.books.size)

        viewModel.deleteBook(mediaId)

        val afterDelete = viewModel.uiState.first { it.books.isEmpty() }
        assertTrue(afterDelete.isEmpty)
    }

    @Test
    fun setStatusFilter_narrowsFilteredBooksButNotBooks() = runTest {
        val toReadResult = repository.addBook(title = "To Read Book", format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(toReadResult)
        val readingResult = repository.addBook(title = "Reading Book", format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(readingResult)
        repository.updateReadingStatus(readingResult.data, ReadingStatus.READING)
        // Add a book without a details row (data-integrity edge case) to verify it's excluded from
        // filtered results when a non-null status filter is applied.
        val noDetailsMediaId = newId()
        db.mediaItemDao().insert(
            sampleMediaItem(id = noDetailsMediaId, type = MediaType.BOOK, title = "No Details Book"),
        )

        viewModel.uiState.first { it.books.size == 3 }

        viewModel.setStatusFilter(ReadingStatus.READING)

        val filtered = viewModel.uiState.first { it.statusFilter == ReadingStatus.READING }
        assertEquals(3, filtered.books.size, "the unfiltered books list must be untouched by the filter")
        assertEquals(1, filtered.filteredBooks.size, "a book with no details row must not match a non-null filter")
        assertEquals("Reading Book", filtered.filteredBooks.first().mediaItem.title)
        assertTrue(filtered.books.any { it.mediaItem.title == "No Details Book" }, "book without details must be in unfiltered list")

        viewModel.setStatusFilter(null)
        val unfiltered = viewModel.uiState.first { it.statusFilter == null }
        assertEquals(3, unfiltered.filteredBooks.size, "all books must be in filteredBooks when filter is null")
    }

    /**
     * Bulk delete that fails on demand. The real use case cannot be made to fail from a test --
     * closing the database yields CancellationException, which it rethrows by design -- so the
     * error surface would otherwise be untestable. Mirrors this codebase's existing hand-rolled
     * fakes (FakeExportDataUseCase and friends); AGENTS.md section 5 rules out a mocking library.
     */
    private class FailingBulkDelete(private val message: String) : BulkDeleteUseCase {
        override suspend fun execute(ids: List<String>): Resource<DeleteBooksSummary> =
            Resource.Error(message)
    }

    /** Rebuilds the ViewModel with a delete that always fails, tracked for teardown like the rest. */
    private fun useFailingDelete(message: String = "Database unavailable") {
        viewModel = viewModels.track(LibraryViewModel(repository, FailingBulkDelete(message)))
    }

    /**
     * Adds a book through the repository (so it goes through the same path production does) and
     * returns its media id, optionally setting a reading status for the filter tests.
     */
    private suspend fun insertBook(title: String, status: ReadingStatus? = null): String {
        val result = repository.addBook(title = title, format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(result)
        if (status != null) repository.updateReadingStatus(result.data, status)
        return result.data
    }

    // --- Selection mode and bulk delete (ROADMAP Task 14 Phase B) ---------------------------

    @Test
    fun toggleSelection_firstAndLast_entersAndLeavesSelectionMode() = runTest {
        val id = insertBook("Dune")
        viewModel.uiState.first { it.books.isNotEmpty() }

        assertFalse(viewModel.uiState.value.isSelectionMode, "not selecting until asked")

        viewModel.toggleSelection(id)
        // Awaited, not read. Selection reaches uiState through combine -> stateIn, so .value can
        // still hold the pre-toggle state when the assertion runs -- the race that failed CI in
        // this file's sibling tests while never reproducing locally.
        assertEquals(setOf(id), viewModel.uiState.first { it.selectedIds.isNotEmpty() }.selectedIds)

        viewModel.toggleSelection(id)
        assertFalse(
            viewModel.uiState.first { it.selectedIds.isEmpty() }.isSelectionMode,
            "deselecting the last book must leave the mode, or there is no way out of it",
        )
    }

    @Test
    fun clearSelection_discardsEverythingSelected() = runTest {
        val a = insertBook("A")
        val b = insertBook("B")
        viewModel.uiState.first { it.books.size == 2 }
        viewModel.toggleSelection(a)
        viewModel.toggleSelection(b)
        assertEquals(2, viewModel.uiState.first { it.selectedIds.size == 2 }.selectedIds.size)

        viewModel.clearSelection()

        assertEquals(emptySet(), viewModel.uiState.first { it.selectedIds.isEmpty() }.selectedIds)
    }

    @Test
    fun deleteSelected_removesOnlyTheSelectedBooksAndLeavesSelectionMode() = runTest {
        val doomed = insertBook("Doomed")
        val keeper = insertBook("Keeper")
        viewModel.uiState.first { it.books.size == 2 }
        viewModel.toggleSelection(doomed)

        viewModel.deleteSelected()

        // Wait on BOTH conditions, not just the book count. deleteSelected clears the selection
        // *after* execute() returns, while the deletion reaches uiState via Room's own async
        // invalidation -- so the emission with one book can legitimately arrive before the one
        // with an empty selection. Waiting only for the count and then asserting the mode was a
        // real race, and CI caught it on a loaded runner where the local machine never did.
        val after = viewModel.uiState.first { it.books.size == 1 && !it.isSelectionMode }
        assertEquals(listOf("Keeper"), after.books.map { it.mediaItem.title })
        assertFalse(after.isSelectionMode, "selection must not survive the delete that consumed it")
    }

    @Test
    fun deleteSelected_withAFilterHidingSomeSelection_stillDeletesTheWholeSelection() = runTest {
        // Reversed from the original behaviour, which scoped the delete to the visible subset. That
        // was reasoned as a safety measure and was worse in practice: the count moved as filters
        // moved, reading as the selection being lost, and the delete then half-finished leaving the
        // rest selected and invisible. Selection belongs to the books, not the current view; the
        // confirmation naming each title is what keeps it honest.
        val visible = insertBook("Visible", status = ReadingStatus.READING)
        val hidden = insertBook("Hidden", status = ReadingStatus.FINISHED)
        viewModel.uiState.first { it.books.size == 2 }
        viewModel.toggleSelection(visible)
        viewModel.toggleSelection(hidden)
        viewModel.setStatusFilter(ReadingStatus.READING)
        viewModel.uiState.first { it.statusFilter == ReadingStatus.READING && it.selectedIds.size == 2 }

        viewModel.deleteSelected()

        val after = viewModel.uiState.first { it.books.isEmpty() && !it.isSelectionMode }
        assertEquals(
            emptyList(),
            after.books.map { it.mediaItem.title },
            "a book hidden by the filter is still selected, so it goes too",
        )
    }

    @Test
    fun selectedBooks_areUnaffectedByTheActiveFilter() = runTest {
        // What the contextual bar counts and the confirmation lists. Scoping this to the filter is
        // what produced the disappearing-count confusion.
        val visible = insertBook("Visible", status = ReadingStatus.READING)
        val hidden = insertBook("Hidden", status = ReadingStatus.FINISHED)
        viewModel.uiState.first { it.books.size == 2 }
        viewModel.toggleSelection(visible)
        viewModel.toggleSelection(hidden)

        viewModel.setStatusFilter(ReadingStatus.READING)

        // Both halves of the state, not just the filter: the toggles and the filter propagate
        // separately, so the first emission carrying READING can still hold a stale selection --
        // which would make the assertion below pass or fail on timing rather than on behaviour.
        val state = viewModel.uiState.first {
            it.statusFilter == ReadingStatus.READING && it.selectedIds.size == 2
        }
        assertEquals(1, state.filteredBooks.size, "the filter still narrows what is *shown*")
        assertEquals(
            listOf("Hidden", "Visible"),
            state.selectedBooks.map { it.mediaItem.title }.sorted(),
            "but the selection itself is not narrowed by it",
        )
    }

    @Test
    fun selection_bookDeletedElsewhere_dropsOutOfTheSelection() = runTest {
        // A selected book can be deleted from Book Detail while selection is active. A stale id
        // would keep inflating the contextual bar's count and be handed to a delete that can do
        // nothing with it.
        val a = insertBook("A")
        val b = insertBook("B")
        viewModel.uiState.first { it.books.size == 2 }
        viewModel.toggleSelection(a)
        viewModel.toggleSelection(b)

        repository.deleteBook(a)

        val after = viewModel.uiState.first { it.books.size == 1 }
        assertEquals(setOf(b), after.selectedIds, "the vanished book must not linger in selection")
    }

    @Test
    fun deleteSelected_withNothingSelected_isANoOp() = runTest {
        insertBook("Untouched")
        viewModel.uiState.first { it.books.isNotEmpty() }

        viewModel.deleteSelected()

        assertEquals(
            1,
            viewModel.uiState.first { it.books.size == 1 }.books.size,
            "nothing selected, nothing deleted",
        )
    }


    @Test
    fun deleteSelected_whenTheDeleteFails_reportsAnErrorAndKeepsTheSelection() = runTest {
        // Closing the database makes the delete fail. Without a reported error the books stay, the
        // selection stays, and nothing appears -- indistinguishable from the button being ignored.
        val id = insertBook("Doomed")
        useFailingDelete("Database unavailable")
        viewModel.uiState.first { it.books.isNotEmpty() }
        viewModel.toggleSelection(id)

        viewModel.deleteSelected()

        val state = viewModel.uiState.first { it.deleteError != null }
        assertEquals("Database unavailable", state.deleteError?.message)
        assertEquals(setOf(id), state.selectedIds, "selection must survive so a retry is possible")
        assertEquals(1, state.books.size, "a failed delete must not remove anything")
    }

    @Test
    fun consumeDeleteError_clearsIt_soTheSameFailureIsNotShownTwice() = runTest {
        val id = insertBook("Doomed")
        useFailingDelete()
        viewModel.uiState.first { it.books.isNotEmpty() }
        viewModel.toggleSelection(id)
        viewModel.deleteSelected()
        // Use the state `first` returned rather than re-reading .value: awaiting and then reading
        // separately is the habit that causes the race even when it happens to be safe here.
        val shown = viewModel.uiState.first { it.deleteError != null }.deleteError!!

        viewModel.consumeDeleteError(shown.id)

        assertNull(
            viewModel.uiState.first { it.deleteError == null }.deleteError,
            "an error already shown is not a state",
        )
    }

    @Test
    fun deleteSelected_failingTwiceWithTheSameMessage_producesTwoDistinctEvents() = runTest {
        // The case the id exists for. A repeated retry against the same broken state yields an
        // identical message, and keyed on text alone the UI would see no change and swallow the
        // second failure -- leaving a delete that appears to have quietly succeeded.
        val id = insertBook("Doomed")
        useFailingDelete("Database unavailable")
        viewModel.uiState.first { it.books.isNotEmpty() }
        viewModel.toggleSelection(id)

        viewModel.deleteSelected()
        val first = viewModel.uiState.first { it.deleteError != null }.deleteError!!
        viewModel.consumeDeleteError(first.id)

        viewModel.deleteSelected()
        // Awaits an event with a *different* id rather than awaiting null in between and then any
        // non-null. Each `first` subscribes and unsubscribes from a WhileSubscribed flow, and this
        // test had four such cycles -- one of them failed to complete on CI (UncompletedCoroutines-
        // Error). Fewer awaits, and a condition that cannot be satisfied by the stale event, is
        // both more robust and a sharper assertion.
        val second = viewModel.uiState
            .first { it.deleteError != null && it.deleteError?.id != first.id }.deleteError!!

        assertEquals(first.message, second.message, "the same failure produces the same text")
        assertNotEquals(first.id, second.id, "but it must still be a distinct, showable event")
    }

    @Test
    fun consumeDeleteError_withAStaleId_leavesANewerFailureIntact() = runTest {
        val id = insertBook("Doomed")
        useFailingDelete()
        viewModel.uiState.first { it.books.isNotEmpty() }
        viewModel.toggleSelection(id)
        viewModel.deleteSelected()
        val current = viewModel.uiState.first { it.deleteError != null }.deleteError!!

        viewModel.consumeDeleteError(current.id - 1)

        // A no-op: the ids do not match, so nothing changes and there is no new state to await.
        // Drain instead, or this asserts before the call has been processed at all and would pass
        // whether the id check works or not.
        runCurrent()
        assertNotNull(
            viewModel.uiState.value.deleteError,
            "acknowledging an older event must not discard the one on screen",
        )
    }

}
