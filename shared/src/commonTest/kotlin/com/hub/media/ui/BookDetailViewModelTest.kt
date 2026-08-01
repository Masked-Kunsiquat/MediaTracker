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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

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

    /**
     * Repeatedly drains the [TestScope.testScheduler] with [runCurrent] (never `advanceUntilIdle`
     * — see [saveSession_staleCompletionDoesNotClobberNewerPendingSession]'s KDoc for why) and,
     * between rounds, yields real (non-virtual) time so work dispatched to a genuinely different
     * dispatcher (Room's own query/invalidation dispatching, entirely outside this test's virtual
     * scheduler) gets a chance to run and re-enqueue its continuation back onto the (test-driven)
     * Main dispatcher. Bounded so an actual regression fails with a clear assertion below instead
     * of hanging.
     */
    private suspend fun TestScope.runCurrentUntilOrTimeOut(
        maxAttempts: Int = 200,
        condition: suspend () -> Boolean,
    ) {
        var attempts = 0
        while (attempts < maxAttempts) {
            runCurrent()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(5) }
            attempts++
        }
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
    fun discardPendingSession_clearsPendingSessionAndError() = runTest {
        insertBook()
        val viewModel = newViewModel()
        viewModel.uiState.first { it is BookDetailUiState.Ready }

        viewModel.startReading()
        viewModel.stopReading()
        val pendingBeforeDiscard =
            (viewModel.uiState.value as BookDetailUiState.Ready).pendingSession
        assertNotNull(pendingBeforeDiscard)

        // Negative startUnit fails LogReadingSessionUseCase validation without persisting, so
        // errorMessage is populated before discardPendingSession is exercised.
        viewModel.saveSession(startUnit = -1.0, endUnit = 10.0)
        viewModel.uiState.first { it is BookDetailUiState.Ready && (it as BookDetailUiState.Ready).errorMessage != null }

        viewModel.discardPendingSession()

        val ready = viewModel.uiState.value as BookDetailUiState.Ready
        assertNull(ready.pendingSession)
        assertNull(ready.errorMessage)
        assertTrue(ready.sessions.isEmpty())
    }

    @Test
    fun saveSession_doubleTapBeforeCompletion_persistsExactlyOneSession() = runTest {
        insertBook()
        val viewModel = newViewModel()
        viewModel.uiState.first { it is BookDetailUiState.Ready }

        viewModel.startReading()
        viewModel.stopReading()

        // Simulate a double-tap on Save: two back-to-back calls, neither yielding in between,
        // both racing to read the same pendingSession before the first persists it.
        viewModel.saveSession(startUnit = 10.0, endUnit = 42.0, deltaPages = 32, notes = "Ch. 3")
        viewModel.saveSession(startUnit = 10.0, endUnit = 42.0, deltaPages = 32, notes = "Ch. 3")

        val ready = viewModel.uiState
            .first { it is BookDetailUiState.Ready && (it as BookDetailUiState.Ready).sessions.isNotEmpty() }
                as BookDetailUiState.Ready

        assertEquals(1, ready.sessions.size)
        assertNull(ready.pendingSession)
        assertNull(ready.errorMessage)
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

    /**
     * Regression test for the stale-completion clobber fixed in [BookDetailViewModel.saveSession]:
     * a save for a since-discarded pending session ("A") must not wipe (or mislabel with an error)
     * a newer pending session ("B") that was started after A was discarded but before A's save
     * actually completed.
     *
     * Unlike every other test in this file, [Dispatchers.Main] here is a [StandardTestDispatcher]
     * rather than [UnconfinedTestDispatcher]: the race requires [BookDetailViewModel.saveSession]'s
     * `viewModelScope.launch` to be enqueued and NOT run at all until explicitly driven, so that
     * `discardPendingSession()` + a fresh start/stop are guaranteed (not just likely) to happen
     * before any part of save(A)'s completion logic executes — `UnconfinedTestDispatcher` would run
     * that launch eagerly up to its first real suspension point instead, which happens to still
     * work for the double-tap guard test above but doesn't give the strict "queued, not run"
     * ordering this test depends on.
     *
     * [runCurrent] (never `advanceUntilIdle()`) drives the scheduler throughout: [ReadingTimer]'s
     * tick loop is also launched on this same `viewModelScope`/Main dispatcher as an unconditional
     * `while (isActive) { delay(...); ... }` loop that never completes, and `advanceUntilIdle()`
     * would spin forever trying to drain it. `runCurrent()` only runs work that's ready *now* and
     * never touches the tick loop's still-pending future delay.
     *
     * The actual Room insert genuinely crosses to a real (non-test-scheduler) dispatcher, so its
     * resumption back onto Main can land slightly after any single `runCurrent()` call returns;
     * the polling loops below bridge that with tiny real (non-virtual) waits rather than a fixed
     * sleep, bounded so a genuine regression fails fast instead of hanging.
     */
    @Test
    fun saveSession_staleCompletionDoesNotClobberNewerPendingSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        insertBook()
        val viewModel = newViewModel()

        var latestReady: BookDetailUiState.Ready? = null
        backgroundScope.launch(Dispatchers.Unconfined) {
            viewModel.uiState.collect { state ->
                if (state is BookDetailUiState.Ready) latestReady = state
            }
        }
        // Room's own Flows (observeById/observeByMediaId/observeSessionsForMedia backing
        // observeBookDetail/observeSessionsForMedia) emit via Room's real internal invalidation
        // dispatching, not this test's virtual scheduler, so reaching the first Ready value also
        // needs the same real-time-bridging poll as the save(A) completion below, not a single
        // runCurrent().
        runCurrentUntilOrTimeOut { latestReady != null }
        assertNotNull(latestReady)
        assertNull(latestReady?.pendingSession)

        // Produce pending session A.
        viewModel.startReading()
        viewModel.stopReading()
        runCurrentUntilOrTimeOut { latestReady?.pendingSession != null }
        val pendingA = assertNotNull(latestReady?.pendingSession)

        // Start saving A. Because Dispatchers.Main currently delegates to a StandardTestDispatcher,
        // this only enqueues the coroutine -- it does not run until the scheduler is next driven.
        viewModel.saveSession(startUnit = 10.0, endUnit = 42.0, notes = "A")

        // Discard A (allowed while a save for it is in flight -- see saveSession's KDoc) and
        // start/stop a brand-new run -> pendingSession = B. Both are plain, synchronous
        // MutableStateFlow updates with no coroutine dispatch, so they take effect immediately,
        // strictly before save(A)'s still-unstarted coroutine gets a chance to run.
        viewModel.discardPendingSession()
        viewModel.startReading()
        viewModel.stopReading()

        // Let save(A) actually run and reach the database. Its row appearing is a real,
        // dispatcher-agnostic signal that the coroutine has passed the suspend point.
        runCurrentUntilOrTimeOut {
            sessionRepository.observeSessionsForMedia(mediaId).first().any { it.notes == "A" }
        }
        assertTrue(
            sessionRepository.observeSessionsForMedia(mediaId).first().any { it.notes == "A" },
            "save(A) must have persisted its row before this assertion",
        )

        // Drain a few more rounds (with tiny real waits) so save(A)'s post-insert completion
        // handler -- which resumes back onto Main asynchronously -- has a chance to run its
        // (correctly guarded) _local.update(...) and be reflected into the collected uiState above.
        repeat(20) {
            runCurrent()
            withContext(Dispatchers.Default) { delay(5) }
        }
        runCurrent()

        // A's row persists even though its pending session was discarded mid-flight: the insert
        // was already dispatched before the discard happened (documented in saveSession's KDoc).
        assertTrue(sessionRepository.observeSessionsForMedia(mediaId).first().any { it.notes == "A" })

        val finalReady = assertNotNull(latestReady)
        assertNotNull(
            finalReady.pendingSession,
            "stale completion of save(A) must not silently wipe pendingSession = B",
        )
        assertTrue(
            finalReady.pendingSession !== pendingA,
            "pendingSession must be the newer session B, not the stale A reference",
        )
        assertNull(finalReady.errorMessage, "stale completion of save(A) must not set an error either")
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
