package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

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

    @BeforeTest
    fun setUp() {
        // viewModelScope dispatches on Dispatchers.Main; UnconfinedTestDispatcher runs launched
        // coroutines eagerly so uiState updates are observable without manually pumping a
        // TestCoroutineScheduler.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = testAppDatabase()
        repository = BookRepository(db)
        viewModel = LibraryViewModel(repository)
    }

    @AfterTest
    fun tearDown() {
        db.close()
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

        viewModel.uiState.first { it.books.size == 2 }

        viewModel.setStatusFilter(ReadingStatus.READING)

        val filtered = viewModel.uiState.first { it.statusFilter == ReadingStatus.READING }
        assertEquals(2, filtered.books.size, "the unfiltered books list must be untouched by the filter")
        assertEquals(1, filtered.filteredBooks.size)
        assertEquals("Reading Book", filtered.filteredBooks.first().mediaItem.title)

        viewModel.setStatusFilter(null)
        val unfiltered = viewModel.uiState.first { it.statusFilter == null }
        assertEquals(2, unfiltered.filteredBooks.size)
    }
}
