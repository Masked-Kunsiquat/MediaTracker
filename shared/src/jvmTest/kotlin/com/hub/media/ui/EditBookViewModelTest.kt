package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.newId
import com.hub.media.features.books.data.BookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [EditBookViewModel] tests against a real in-memory [AppDatabase], mirroring
 * [BookDetailViewModelTest]'s style (same `testAppDatabase()` builder, `Dispatchers.Main` set to
 * an eager test dispatcher via [ViewModelRegistry.installMain]). Room-backed, so in `jvmTest`, the only source set where `testAppDatabase()` is visible (#81) —
 * `:shared:jvmTest` is the authoritative gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditBookViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var mediaId: String
    private val viewModels = ViewModelRegistry()

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
        db = testAppDatabase()
        bookRepository = BookRepository(db)
        mediaId = newId()
    }

    @AfterTest
    fun tearDown() {
        // Cancel every ViewModel's viewModelScope (and its stateIn/WhileSubscribed sharing
        // coroutine) before closing the database or resetting Main -- see ViewModelRegistry's
        // KDoc for why this order matters.
        viewModels.clearAll()
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
        trackingMode: TrackingMode = TrackingMode.PAGES,
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
            sampleBookDetails(
                mediaId = mediaId,
                format = format,
                totalPages = totalPages,
                status = status,
                trackingMode = trackingMode,
            ),
        )
    }

    private fun newViewModel(id: String = mediaId) =
        viewModels.track(EditBookViewModel(bookId = id, bookRepository = bookRepository))

    @Test
    fun uiState_initialValue_isLoading() {
        val viewModel = newViewModel()
        assertIs<EditBookUiState.Loading>(viewModel.uiState.value)
    }

    @Test
    fun uiState_unknownBookId_isNotFound() =
        runTest {
            val viewModel = newViewModel(id = newId())

            val state = viewModel.uiState.first { it !is EditBookUiState.Loading }

            assertIs<EditBookUiState.NotFound>(state)
        }

    @Test
    fun uiState_emitsReadyWithCurrentMetadata() =
        runTest {
            insertBook(
                title = "Project Hail Mary",
                releaseYear = 2021,
                purchasePrice = 27.99,
                totalPages = 384,
                format = BookFormat.PHYSICAL,
                status = ReadingStatus.READING,
                trackingMode = TrackingMode.PAGES,
            )
            val viewModel = newViewModel()

            val ready = viewModel.uiState.first { it is EditBookUiState.Ready } as EditBookUiState.Ready

            assertEquals("Project Hail Mary", ready.title)
            assertEquals(2021, ready.releaseYear)
            assertEquals(27.99, ready.purchasePrice)
            assertEquals(384, ready.totalPages)
            assertEquals(BookFormat.PHYSICAL, ready.format)
            assertEquals(ReadingStatus.READING, ready.status)
            assertEquals(TrackingMode.PAGES, ready.trackingMode)
            assertNull(ready.errorMessage)
            assertEquals(false, ready.isSaving)
        }

    @Test
    fun uiState_noBookDetailsRow_defaultsFormatToPhysical() =
        runTest {
            // Data-integrity edge case (see BookRepository.updateBookMetadata's KDoc): a MediaItem
            // with no BookDetailsEntity row.
            db.mediaItemDao().insert(sampleMediaItem(id = mediaId, type = MediaType.BOOK, title = "Orphan"))
            val viewModel = newViewModel()

            val ready = viewModel.uiState.first { it is EditBookUiState.Ready } as EditBookUiState.Ready

            assertEquals("Orphan", ready.title)
            assertNull(ready.totalPages)
            assertEquals(BookFormat.PHYSICAL, ready.format)
            assertEquals(ReadingStatus.TO_READ, ready.status)
            assertEquals(TrackingMode.PAGES, ready.trackingMode)
        }

    @Test
    fun save_happyPath_persistsAndUiStateBecomesSaved() =
        runTest {
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
                trackingMode = TrackingMode.PERCENT,
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
            assertEquals(TrackingMode.PERCENT, details?.trackingMode)
            assertTrue(details?.finishedAt != null)
        }

    @Test
    fun save_validationFailure_keepsReadyWithErrorMessage() =
        runTest {
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
                trackingMode = TrackingMode.PAGES,
            )

            val ready =
                viewModel.uiState
                    .first { it is EditBookUiState.Ready && (it as EditBookUiState.Ready).errorMessage != null }
                    as EditBookUiState.Ready

            assertTrue(ready.errorMessage!!.contains("blank"))
            assertEquals(false, ready.isSaving)
            // Nothing was persisted; the original title survives.
            assertEquals("Project Hail Mary", db.mediaItemDao().getById(mediaId)?.title)
        }

    @Test
    fun save_validationFailure_outOfRangeReleaseYear_keepsReadyWithErrorMessage() =
        runTest {
            insertBook()
            val viewModel = newViewModel()
            viewModel.uiState.first { it is EditBookUiState.Ready }

            viewModel.save(
                title = "Project Hail Mary",
                releaseYear = 2101,
                purchasePrice = 27.99,
                totalPages = 384,
                format = BookFormat.PHYSICAL,
                status = ReadingStatus.TO_READ,
                trackingMode = TrackingMode.PAGES,
            )

            val ready =
                viewModel.uiState
                    .first { it is EditBookUiState.Ready && (it as EditBookUiState.Ready).errorMessage != null }
                    as EditBookUiState.Ready

            assertTrue(ready.errorMessage!!.contains("Release year"))
            assertEquals(false, ready.isSaving)
            assertEquals(2021, db.mediaItemDao().getById(mediaId)?.releaseYear)
        }

    @Test
    fun save_validationFailure_negativePurchasePrice_keepsReadyWithErrorMessage() =
        runTest {
            insertBook()
            val viewModel = newViewModel()
            viewModel.uiState.first { it is EditBookUiState.Ready }

            viewModel.save(
                title = "Project Hail Mary",
                releaseYear = 2021,
                purchasePrice = -10.0,
                totalPages = 384,
                format = BookFormat.PHYSICAL,
                status = ReadingStatus.TO_READ,
                trackingMode = TrackingMode.PAGES,
            )

            val ready =
                viewModel.uiState
                    .first { it is EditBookUiState.Ready && (it as EditBookUiState.Ready).errorMessage != null }
                    as EditBookUiState.Ready

            assertTrue(ready.errorMessage!!.contains("Purchase price"))
            assertEquals(false, ready.isSaving)
            assertEquals(27.99, db.mediaItemDao().getById(mediaId)?.purchasePrice)
        }

    @Test
    fun save_validationFailure_nonPositiveTotalPages_keepsReadyWithErrorMessage() =
        runTest {
            insertBook()
            val viewModel = newViewModel()
            viewModel.uiState.first { it is EditBookUiState.Ready }

            viewModel.save(
                title = "Project Hail Mary",
                releaseYear = 2021,
                purchasePrice = 27.99,
                totalPages = 0,
                format = BookFormat.PHYSICAL,
                status = ReadingStatus.TO_READ,
                trackingMode = TrackingMode.PAGES,
            )

            val ready =
                viewModel.uiState
                    .first { it is EditBookUiState.Ready && (it as EditBookUiState.Ready).errorMessage != null }
                    as EditBookUiState.Ready

            assertTrue(ready.errorMessage!!.contains("Total pages"))
            assertEquals(false, ready.isSaving)
            assertEquals(384, db.bookDetailsDao().getByMediaId(mediaId)?.totalPages)
        }

    @Test
    fun save_doubleTapBeforeCompletion_persistsOnlyOnce() =
        runTest {
            insertBook()
            val viewModel = newViewModel()
            viewModel.uiState.first { it is EditBookUiState.Ready }

            // Main becomes a StandardTestDispatcher for the two save calls, so `launch` only *enqueues*
            // and the first save cannot finish before the second is made. Under the default eager
            // dispatcher it sometimes did: the first coroutine ran to completion inside the first
            // save() call, clearing saveInFlight, so the second save legitimately proceeded and won.
            // That made this test's premise -- "a second tap while the first is still in flight" --
            // something it could not actually guarantee, and it failed on CI twice for that reason
            // while passing locally.
            //
            // Same technique, and the same reason, as
            // BookDetailViewModelTest.saveSession_staleCompletionDoesNotClobberNewerPendingSession.
            viewModels.installMain(StandardTestDispatcher(testScheduler))

            viewModel.save(
                title = "First Call Title",
                releaseYear = 2021,
                purchasePrice = 27.99,
                totalPages = 384,
                format = BookFormat.PHYSICAL,
                status = ReadingStatus.TO_READ,
                trackingMode = TrackingMode.PAGES,
            )
            // Second call while the first is still in flight must no-op per the saveInFlight guard.
            viewModel.save(
                title = "Second Call Title",
                releaseYear = 2021,
                purchasePrice = 27.99,
                totalPages = 384,
                format = BookFormat.PHYSICAL,
                status = ReadingStatus.TO_READ,
                trackingMode = TrackingMode.PAGES,
            )

            // Now let the enqueued save actually run.
            runCurrent()
            viewModel.uiState.first { it is EditBookUiState.Saved }

            // Awaited through the reactive path the UI itself reads, rather than snapshotting the DAO
            // the instant uiState reports Saved. `Saved` is derived from the ViewModel's own local
            // state, which flips as soon as the repository call returns -- it says nothing about the
            // observing query having re-emitted. Snapshotting there failed once on CI with a
            // ComparisonFailure whose values the console log truncated away.
            //
            // The guard is correct -- saveInFlight is checked and set synchronously before
            // viewModelScope.launch. What this test could not previously do was *create* the condition
            // it names, which is what the dispatcher swap above fixes.
            val persisted =
                bookRepository
                    .observeBookDetail(mediaId)
                    .first { it?.item?.title != "Project Hail Mary" }
            assertEquals(
                "First Call Title",
                persisted?.item?.title,
                "the in-flight guard must have dropped the second save; found instead",
            )
        }

    /**
     * Tracking-mode selector round-trip (ROADMAP Task 7 Phase A): the value [EditBookUiState.Ready]
     * prefills the (would-be) selector with is exactly the value a subsequent [EditBookViewModel.save]
     * persists when re-emitted unchanged -- proving [EditBookUiState.Ready.trackingMode] and
     * [EditBookViewModel.save]'s `trackingMode` parameter agree on the same field, with no
     * re-derivation from [totalPages] happening anywhere in between.
     */
    @Test
    fun trackingMode_readThenSaveUnchanged_roundTripsExactly() =
        runTest {
            insertBook(totalPages = 384, trackingMode = TrackingMode.PERCENT)
            val viewModel = newViewModel()

            val ready = viewModel.uiState.first { it is EditBookUiState.Ready } as EditBookUiState.Ready
            assertEquals(
                TrackingMode.PERCENT,
                ready.trackingMode,
                "prefilled trackingMode must reflect the stored value, not be re-derived from totalPages",
            )

            viewModel.save(
                title = ready.title,
                releaseYear = ready.releaseYear,
                purchasePrice = ready.purchasePrice,
                totalPages = ready.totalPages,
                format = ready.format,
                status = ready.status,
                trackingMode = ready.trackingMode,
            )

            viewModel.uiState.first { it is EditBookUiState.Saved }
            val details = db.bookDetailsDao().getByMediaId(mediaId)
            assertEquals(384, details?.totalPages, "totalPages must survive untouched")
            assertEquals(
                TrackingMode.PERCENT,
                details?.trackingMode,
                "trackingMode must round-trip unchanged despite totalPages being non-null",
            )
        }
}
