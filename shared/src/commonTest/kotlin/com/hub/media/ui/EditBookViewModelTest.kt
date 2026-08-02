package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.newId
import com.hub.media.features.books.data.BookRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * [EditBookViewModel] tests against a real in-memory [AppDatabase], mirroring
 * [BookDetailViewModelTest]'s style (same `testAppDatabase()` builder, `Dispatchers.Main` set to
 * an [UnconfinedTestDispatcher]). Room-backed, so excluded from the android unit-test variant by
 * exact class name in `shared/build.gradle.kts` — `:shared:jvmTest` is the authoritative gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditBookViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var mediaId: String

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = testAppDatabase()
        bookRepository = BookRepository(db)
        mediaId = newId()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun insertBook(
        title: String = "Project Hail Mary",
        releaseYear: Int? = 2021,
        purchasePrice: Double? = 27.99,
        totalPages: Int? = 384,
        format: BookFormat = BookFormat.PHYSICAL,
        status: ReadingStatus = ReadingStatus.TO_READ,
    ) {
        db.mediaItemDao().insert(
            sampleMediaItem(
                id = mediaId,
                type = MediaType.BOOK,
                title = title,
                releaseYear = releaseYear,
                purchasePrice = purchasePrice,
            ),
        )
        db.bookDetailsDao().insert(
            sampleBookDetails(mediaId = mediaId, format = format, totalPages = totalPages, status = status),
        )
    }

    private fun newViewModel(id: String = mediaId) =
        EditBookViewModel(bookId = id, bookRepository = bookRepository)

    @Test
    fun uiState_initialValue_isLoading() {
        val viewModel = newViewModel()
        assertIs<EditBookUiState.Loading>(viewModel.uiState.value)
    }

    @Test
    fun uiState_unknownBookId_isNotFound() = runTest {
        val viewModel = newViewModel(id = newId())

        val state = viewModel.uiState.first { it !is EditBookUiState.Loading }

        assertIs<EditBookUiState.NotFound>(state)
    }

    @Test
    fun uiState_emitsReadyWithCurrentMetadata() = runTest {
        insertBook(
            title = "Project Hail Mary",
            releaseYear = 2021,
            purchasePrice = 27.99,
            totalPages = 384,
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.READING,
        )
        val viewModel = newViewModel()

        val ready = viewModel.uiState.first { it is EditBookUiState.Ready } as EditBookUiState.Ready

        assertEquals("Project Hail Mary", ready.title)
        assertEquals(2021, ready.releaseYear)
        assertEquals(27.99, ready.purchasePrice)
        assertEquals(384, ready.totalPages)
        assertEquals(BookFormat.PHYSICAL, ready.format)
        assertEquals(ReadingStatus.READING, ready.status)
        assertNull(ready.errorMessage)
        assertEquals(false, ready.isSaving)
    }

    @Test
    fun uiState_noBookDetailsRow_defaultsFormatToPhysical() = runTest {
        // Data-integrity edge case (see BookRepository.updateBookMetadata's KDoc): a MediaItem
        // with no BookDetailsEntity row.
        db.mediaItemDao().insert(sampleMediaItem(id = mediaId, type = MediaType.BOOK, title = "Orphan"))
        val viewModel = newViewModel()

        val ready = viewModel.uiState.first { it is EditBookUiState.Ready } as EditBookUiState.Ready

        assertEquals("Orphan", ready.title)
        assertNull(ready.totalPages)
        assertEquals(BookFormat.PHYSICAL, ready.format)
        assertEquals(ReadingStatus.TO_READ, ready.status)
    }

    @Test
    fun save_happyPath_persistsAndUiStateBecomesSaved() = runTest {
        insertBook()
        val viewModel = newViewModel()
        viewModel.uiState.first { it is EditBookUiState.Ready }

        viewModel.save(
            title = "Corrected Title",
            releaseYear = 2022,
            purchasePrice = 15.0,
            totalPages = 366,
            format = BookFormat.HARDCOVER,
            status = ReadingStatus.FINISHED,
        )

        val state = viewModel.uiState.first { it is EditBookUiState.Saved }
        assertIs<EditBookUiState.Saved>(state)

        val mediaItem = db.mediaItemDao().getById(mediaId)
        assertEquals("Corrected Title", mediaItem?.title)
        assertEquals(2022, mediaItem?.releaseYear)
        assertEquals(15.0, mediaItem?.purchasePrice)
        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertEquals(366, details?.totalPages)
        assertEquals(BookFormat.HARDCOVER, details?.format)
        assertEquals(ReadingStatus.FINISHED, details?.status)
        assertTrue(details?.finishedAt != null)
    }

    @Test
    fun save_validationFailure_keepsReadyWithErrorMessage() = runTest {
        insertBook()
        val viewModel = newViewModel()
        viewModel.uiState.first { it is EditBookUiState.Ready }

        viewModel.save(
            title = "   ",
            releaseYear = 2021,
            purchasePrice = 27.99,
            totalPages = 384,
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.TO_READ,
        )

        val ready = viewModel.uiState
            .first { it is EditBookUiState.Ready && (it as EditBookUiState.Ready).errorMessage != null }
                as EditBookUiState.Ready

        assertTrue(ready.errorMessage!!.contains("blank"))
        assertEquals(false, ready.isSaving)
        // Nothing was persisted; the original title survives.
        assertEquals("Project Hail Mary", db.mediaItemDao().getById(mediaId)?.title)
    }

    @Test
    fun save_doubleTapBeforeCompletion_persistsOnlyOnce() = runTest {
        insertBook()
        val viewModel = newViewModel()
        viewModel.uiState.first { it is EditBookUiState.Ready }

        viewModel.save(
            title = "First Call Title",
            releaseYear = 2021,
            purchasePrice = 27.99,
            totalPages = 384,
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.TO_READ,
        )
        // Second call while the first is still in flight must no-op per the saveInFlight guard.
        viewModel.save(
            title = "Second Call Title",
            releaseYear = 2021,
            purchasePrice = 27.99,
            totalPages = 384,
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.TO_READ,
        )

        viewModel.uiState.first { it is EditBookUiState.Saved }

        assertEquals("First Call Title", db.mediaItemDao().getById(mediaId)?.title)
    }
}
