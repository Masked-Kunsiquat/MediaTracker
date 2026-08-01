package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.books.domain.LogReadingSessionUseCase
import com.hub.media.features.books.timer.ReadingTimerState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * [BookDetailViewModel] tests against a real in-memory [AppDatabase] (via `testAppDatabase()`,
 * same builder as [LibraryViewModelTest] and the DAO/repository tests), so the
 * `observeBookDetail`/`observeSessionsForMedia` -> `combine` -> `stateIn` wiring is exercised end
 * to end. Room-backed, so this class is excluded from the android unit-test variant by exact class
 * name in shared/build.gradle.kts, same as [LibraryViewModelTest] — `:shared:jvmTest` is the
 * authoritative gate.
 *
 * The timer's tick loop is not driven with virtual time here (unlike `ReadingTimerTest`, which
 * owns that coverage): `Dispatchers.Main` is an [UnconfinedTestDispatcher] with its own scheduler,
 * independent of each test's `runTest` scheduler, so `delay()` inside the tick loop never actually
 * advances. That's fine — these tests only assert lifecycle/state-transition and persistence
 * behavior, and a 0-second-elapsed timer run is an explicitly valid result (AGENTS.md §7).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var sessionRepository: ReadingSessionRepository
    private lateinit var useCase: LogReadingSessionUseCase
    private lateinit var mediaId: String

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = testAppDatabase()
        bookRepository = BookRepository(db)
        sessionRepository = ReadingSessionRepository(db)
        useCase = LogReadingSessionUseCase(sessionRepository)
        mediaId = newId()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun insertBook() {
        db.mediaItemDao().insert(sampleMediaItem(id = mediaId, type = MediaType.BOOK, title = "Dune"))
        db.bookDetailsDao().insert(sampleBookDetails(mediaId = mediaId))
    }

    private fun newViewModel(id: String = mediaId) =
        BookDetailViewModel(
            bookId = id,
            bookRepository = bookRepository,
            readingSessionRepository = sessionRepository,
            logReadingSessionUseCase = useCase,
        )

    @Test
    fun uiState_initialValue_isLoading() {
        val viewModel = newViewModel()
        assertIs<BookDetailUiState.Loading>(viewModel.uiState.value)
    }

    @Test
    fun uiState_emitsReadyWithBookSessionsAndDerivedProgress() = runTest {
        insertBook()
        val start1 = Instant.fromEpochMilliseconds(1_700_000_000_000)
        sessionRepository.logSession(
            mediaId = mediaId,
            timestampStart = start1,
            timestampEnd = start1.plus(1.hours),
            durationSeconds = 3_600,
            startUnit = 0.0,
            endUnit = 50.0,
        )
        val start2 = start1.plus(2.hours)
        sessionRepository.logSession(
            mediaId = mediaId,
            timestampStart = start2,
            timestampEnd = start2.plus(1.hours),
            durationSeconds = 3_600,
            startUnit = 50.0,
            endUnit = 90.0,
        )

        val viewModel = newViewModel()
        val ready = viewModel.uiState.first { it is BookDetailUiState.Ready } as BookDetailUiState.Ready

        assertEquals("Dune", ready.book.title)
        assertNotNull(ready.details)
        assertEquals(2, ready.sessions.size)
        // observeSessionsForMedia orders most-recent-first: start2's session (endUnit 90.0) is first.
        assertEquals(90.0, ready.sessions.first().endUnit)
        assertEquals(90.0, ready.currentProgress)
    }

    @Test
    fun uiState_unknownBookId_isNotFound() = runTest {
        val viewModel = newViewModel(id = newId())

        val state = viewModel.uiState.first { it !is BookDetailUiState.Loading }

        assertIs<BookDetailUiState.NotFound>(state)
    }

    @Test
    fun stopReading_afterStart_producesPendingSession() = runTest {
        insertBook()
        val viewModel = newViewModel()
        viewModel.uiState.first { it is BookDetailUiState.Ready }

        viewModel.startReading()
        assertIs<ReadingTimerState.Running>(viewModel.timerState.value)

        viewModel.stopReading()
        assertIs<ReadingTimerState.Idle>(viewModel.timerState.value)

        val ready = viewModel.uiState.first { it is BookDetailUiState.Ready } as BookDetailUiState.Ready
        assertNotNull(ready.pendingSession)
    }

    @Test
    fun saveSession_persistsSessionAndClearsPending() = runTest {
        insertBook()
        val viewModel = newViewModel()
        viewModel.uiState.first { it is BookDetailUiState.Ready }

        viewModel.startReading()
        viewModel.stopReading()

        viewModel.saveSession(startUnit = 10.0, endUnit = 42.0, deltaPages = 32, notes = "Ch. 3")

        val ready = viewModel.uiState
            .first { it is BookDetailUiState.Ready && (it as BookDetailUiState.Ready).sessions.isNotEmpty() }
                as BookDetailUiState.Ready

        assertNull(ready.pendingSession)
        assertNull(ready.errorMessage)
        assertEquals(1, ready.sessions.size)
        assertEquals(42.0, ready.sessions.first().endUnit)
        assertEquals(32, ready.sessions.first().deltaPages)
        assertEquals("Ch. 3", ready.sessions.first().notes)
    }

    @Test
    fun saveSession_validationError_keepsPendingSession() = runTest {
        insertBook()
        val viewModel = newViewModel()
        viewModel.uiState.first { it is BookDetailUiState.Ready }

        viewModel.startReading()
        viewModel.stopReading()
        val pendingBeforeSave =
            (viewModel.uiState.value as BookDetailUiState.Ready).pendingSession
        assertNotNull(pendingBeforeSave)

        // Negative startUnit fails LogReadingSessionUseCase validation without persisting.
        viewModel.saveSession(startUnit = -1.0, endUnit = 10.0)

        val ready = viewModel.uiState
            .first { it is BookDetailUiState.Ready && (it as BookDetailUiState.Ready).errorMessage != null }
                as BookDetailUiState.Ready

        assertNotNull(ready.errorMessage)
        assertTrue(ready.errorMessage!!.contains("startUnit"))
        // The pending result must survive the failed save so the user can correct input and retry.
        assertEquals(pendingBeforeSave, ready.pendingSession)
        assertTrue(ready.sessions.isEmpty())
    }

    @Test
    fun saveSession_withNoPendingSession_isNoOp() = runTest {
        insertBook()
        val viewModel = newViewModel()
        viewModel.uiState.first { it is BookDetailUiState.Ready }

        viewModel.saveSession(startUnit = 0.0, endUnit = 10.0)

        val ready = viewModel.uiState.value as BookDetailUiState.Ready
        assertTrue(ready.sessions.isEmpty())
        assertNull(ready.errorMessage)
    }

    @Test
    fun logManualSession_persistsSessionWithNoTimerInvolved() = runTest {
        insertBook()
        val viewModel = newViewModel()
        viewModel.uiState.first { it is BookDetailUiState.Ready }

        val start = Instant.fromEpochMilliseconds(1_700_000_000_000)
        viewModel.logManualSession(
            timestampStart = start,
            timestampEnd = start.plus(1.hours),
            durationSeconds = 3_600,
            startUnit = 0.0,
            endUnit = 50.0,
        )

        val ready = viewModel.uiState
            .first { it is BookDetailUiState.Ready && (it as BookDetailUiState.Ready).sessions.isNotEmpty() }
                as BookDetailUiState.Ready

        assertEquals(1, ready.sessions.size)
        assertEquals(50.0, ready.sessions.first().endUnit)
        assertIs<ReadingTimerState.Idle>(viewModel.timerState.value)
    }

    @Test
    fun deleteSession_removesItFromHistory() = runTest {
        insertBook()
        val viewModel = newViewModel()
        viewModel.uiState.first { it is BookDetailUiState.Ready }

        val start = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val addResult = sessionRepository.logSession(
            mediaId = mediaId,
            timestampStart = start,
            timestampEnd = start.plus(1.hours),
            durationSeconds = 3_600,
            startUnit = 0.0,
            endUnit = 50.0,
        )
        assertIs<Resource.Success<String>>(addResult)
        val sessionId = addResult.data

        viewModel.uiState.first { it is BookDetailUiState.Ready && (it as BookDetailUiState.Ready).sessions.isNotEmpty() }

        viewModel.deleteSession(sessionId)

        val ready = viewModel.uiState
            .first { it is BookDetailUiState.Ready && (it as BookDetailUiState.Ready).sessions.isEmpty() }
                as BookDetailUiState.Ready
        assertTrue(ready.sessions.isEmpty())
    }

    @Test
    fun doubleFireGuards_neverThrow() = runTest {
        insertBook()
        val viewModel = newViewModel()
        viewModel.uiState.first { it is BookDetailUiState.Ready }

        // pause/resume/stop before any start() must no-op, not throw.
        viewModel.pauseReading()
        viewModel.resumeReading()
        viewModel.stopReading()
        assertIs<ReadingTimerState.Idle>(viewModel.timerState.value)

        viewModel.startReading()
        viewModel.startReading() // second start while Running must no-op, not throw.
        assertIs<ReadingTimerState.Running>(viewModel.timerState.value)

        viewModel.pauseReading()
        viewModel.pauseReading() // second pause while Paused must no-op, not throw.
        assertIs<ReadingTimerState.Paused>(viewModel.timerState.value)

        viewModel.resumeReading()
        viewModel.resumeReading() // second resume while Running must no-op, not throw.
        assertIs<ReadingTimerState.Running>(viewModel.timerState.value)

        viewModel.stopReading()
        val pendingAfterFirstStop =
            (viewModel.uiState.value as BookDetailUiState.Ready).pendingSession
        assertNotNull(pendingAfterFirstStop)

        viewModel.stopReading() // second stop while Idle must no-op, not throw or overwrite pending.
        assertIs<ReadingTimerState.Idle>(viewModel.timerState.value)
        assertEquals(pendingAfterFirstStop, (viewModel.uiState.value as BookDetailUiState.Ready).pendingSession)
    }
}
