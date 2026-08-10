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
import com.hub.media.features.books.domain.DeleteBooksUseCase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
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
        assertTrue(viewModel.uiState.value.isSelectionMode)
        assertEquals(setOf(id), viewModel.uiState.value.selectedIds)

        viewModel.toggleSelection(id)
        assertFalse(
            viewModel.uiState.value.isSelectionMode,
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
        assertEquals(2, viewModel.uiState.value.selectedIds.size)

        viewModel.clearSelection()

        assertEquals(emptySet(), viewModel.uiState.value.selectedIds)
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
    fun deleteSelected_withAFilterHidingSomeSelection_deletesOnlyWhatIsVisible() = runTest {
        // The dangerous case. Selection deliberately survives a filter change, so a user can refine
        // a search to reach the next book they want -- but a bulk delete must never remove
        // something they cannot currently see, because they have no way to notice it went.
        val visible = insertBook("Visible", status = ReadingStatus.READING)
        val hidden = insertBook("Hidden", status = ReadingStatus.FINISHED)
        viewModel.uiState.first { it.books.size == 2 }
        viewModel.toggleSelection(visible)
        viewModel.toggleSelection(hidden)
        viewModel.setStatusFilter(ReadingStatus.READING)
        assertEquals(
            setOf(visible),
            viewModel.uiState.value.visibleSelectedIds,
            "the hidden book is still selected, just not actionable",
        )

        viewModel.deleteSelected()

        val after = viewModel.uiState.first { it.books.size == 1 }
        assertEquals(
            listOf("Hidden"),
            after.books.map { it.mediaItem.title },
            "the book filtered out of view must survive",
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

        assertEquals(1, viewModel.uiState.value.books.size, "nothing selected, nothing deleted")
    }

}
