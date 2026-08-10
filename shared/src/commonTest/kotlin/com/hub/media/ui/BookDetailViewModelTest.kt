package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.books.domain.LogReadingSessionUseCase
import com.hub.media.features.books.domain.RefetchCoverUseCase
import com.hub.media.features.books.network.BookMetadata
import com.hub.media.features.books.network.BookMetadataProvider
import com.hub.media.features.books.network.CoverImageDownloader
import com.hub.media.features.books.timer.ReadingTimerState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
 * owns that coverage): `Dispatchers.Main` is installed via [ViewModelRegistry.installMain] (an
 * eager unconfined-style test dispatcher, by default) with its own scheduler, independent of each
 * test's `runTest` scheduler, so `delay()` inside the tick loop never actually advances. That's
 * fine — these tests only assert lifecycle/state-transition and persistence behavior, and a
 * 0-second-elapsed timer run is an explicitly valid result (AGENTS.md §7).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailViewModelTest {

    private companion object {
        const val PROVIDER_ERROR_MESSAGE = "test provider error"
    }

    private lateinit var db: AppDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var sessionRepository: ReadingSessionRepository
    private lateinit var useCase: LogReadingSessionUseCase
    private lateinit var refetchCoverUseCase: RefetchCoverUseCase
    private lateinit var mediaId: String
    private val viewModels = ViewModelRegistry()

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
        db = testAppDatabase()
        bookRepository = BookRepository(db)
        sessionRepository = ReadingSessionRepository(db)
        useCase = LogReadingSessionUseCase(sessionRepository)
        // One test in this file exercises refetchCover() on the error path
        // (see refetchCover_useCaseError_setsErrorMessageAndClearsInFlightFlag) -- this wiring
        // must support that. Detailed use-case-level coverage (happy path, no-ISBN, provider
        // coverless, download failure) lives in RefetchCoverUseCaseTest. The image storage path is
        // never written to (LocalImageStorageManager does no I/O until saveImage() is called).
        refetchCoverUseCase = RefetchCoverUseCase(
            metadataProvider = object : BookMetadataProvider {
                override suspend fun fetchByIsbn(isbn: String): Resource<BookMetadata> =
                    Resource.Error(PROVIDER_ERROR_MESSAGE)
            },
            coverDownloader = CoverImageDownloader(createHttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })),
            imageStorage = LocalImageStorageManager("unused"),
            bookRepository = bookRepository,
        )
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
     * Main dispatcher. Bounded, and on exhausting that bound it fails *here* with a message naming
     * the timeout -- see the comment at the bottom of the loop for why returning silently instead
     * was the bug that made three tests in this class intermittently red.
     */
    private suspend fun TestScope.runCurrentUntilOrTimeOut(
        maxAttempts: Int = 1_000,
        condition: suspend () -> Boolean,
    ) {
        var attempts = 0
        while (attempts < maxAttempts) {
            runCurrent()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(5) }
            attempts++
        }
        // One final drain and check. The loop above ends with a delay, so without this the work
        // that completed during that last wait is never looked at -- the helper would sleep for it
        // and then fail without ever asking. Cheap, and it removes an off-by-one that would only
        // ever show up as a rare failure at exactly the boundary, which is the hardest kind to
        // diagnose and precisely the sort of thing this whole change is about.
        runCurrent()
        if (condition()) return
        // Fails here rather than returning, which is what the first version did. Falling through
        // silently meant a timeout surfaced as whichever assertion happened to come next -- so a
        // machine too busy to propagate a Room invalidation in time produced "pendingSession must
        // have moved on to a new session B", which describes neither the cause nor the location.
        // These three tests were intermittently red for exactly that reason, and the message sent
        // every reader looking at ViewModel state that was fine.
        //
        // The bound is also 5x what it was. 200 attempts is ~1 real second, which is ample on an
        // idle developer machine and demonstrably not ample on a loaded CI runner -- the sibling
        // helper in BackfillViewModelTest already documents 5 seconds as the generous-but-bounded
        // figure, and this one being tighter was an accident rather than a decision. A test that is
        // genuinely stuck still fails, just after a wait long enough to mean it.
        fail(
            "runCurrentUntilOrTimeOut gave up after $maxAttempts attempts plus a final check " +
                "(~${maxAttempts * 5}ms of real time) waiting for its condition. Either the " +
                "awaited work never happened (a real regression) or this machine needed longer " +
                "than the bound allows.",
        )
    }

    private fun newViewModel(id: String = mediaId) =
        viewModels.track(
            BookDetailViewModel(
                bookId = id,
                bookRepository = bookRepository,
                readingSessionRepository = sessionRepository,
                logReadingSessionUseCase = useCase,
                refetchCoverUseCase = refetchCoverUseCase,
            ),
        )

    /**
     * The helper is test-only, but it is load-bearing: its silently-returning-on-timeout behaviour
     * is what made three tests in this class intermittently red while pointing at the wrong thing.
     * These two cover it directly so that regression cannot come back unnoticed.
     */
    @Test
    fun runCurrentUntilOrTimeOut_conditionEventuallyTrue_returnsWithoutFailing() = runTest {
        var evaluations = 0

        runCurrentUntilOrTimeOut(maxAttempts = 10) { ++evaluations >= 3 }

        // Positive control for the timeout test below: proves the helper genuinely polls and
        // returns on success, so that test's failure is about the timeout and not about the helper
        // being broken in some way that fails everything.
        assertEquals(3, evaluations, "must stop polling as soon as the condition holds")
    }

    @Test
    fun runCurrentUntilOrTimeOut_conditionNeverTrue_failsNamingTheTimeout() = runTest {
        // Deterministic: the condition can never hold, so this always times out. maxAttempts is
        // tiny to keep it fast -- the default would spend five real seconds proving nothing extra.
        val error = assertFailsWith<AssertionError> {
            runCurrentUntilOrTimeOut(maxAttempts = 3) { false }
        }

        assertTrue(
            error.message.orEmpty().contains("gave up after 3 attempts"),
            "the timeout must identify itself rather than surfacing as a later assertion; " +
                "was: ${error.message}",
        )
    }

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
        assertIs<ReadingTimerState.Running>(
            viewModel.timerState.value,
            "startReading must put the timer into Running",
        )

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
        // Awaited, not read -- see runCurrentUntilOrTimeOut's KDoc and doubleFireGuards for why
        // reading .value straight after an action races combine -> stateIn.
        runCurrentUntilOrTimeOut {
            (viewModel.uiState.value as? BookDetailUiState.Ready)?.pendingSession != null
        }
        val pendingBeforeSave =
            (viewModel.uiState.value as BookDetailUiState.Ready).pendingSession
        assertNotNull(pendingBeforeSave, "stopReading must leave a pending session")

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
        // Awaited, not read -- see runCurrentUntilOrTimeOut's KDoc and doubleFireGuards for why
        // reading .value straight after an action races combine -> stateIn.
        runCurrentUntilOrTimeOut {
            (viewModel.uiState.value as? BookDetailUiState.Ready)?.pendingSession != null
        }
        val pendingBeforeDiscard =
            (viewModel.uiState.value as BookDetailUiState.Ready).pendingSession
        assertNotNull(pendingBeforeDiscard, "stopReading must leave a pending session")

        // Negative startUnit fails LogReadingSessionUseCase validation without persisting, so
        // errorMessage is populated before discardPendingSession is exercised.
        viewModel.saveSession(startUnit = -1.0, endUnit = 10.0)
        viewModel.uiState.first { it is BookDetailUiState.Ready && (it as BookDetailUiState.Ready).errorMessage != null }

        viewModel.discardPendingSession()

        runCurrentUntilOrTimeOut {
            (viewModel.uiState.value as? BookDetailUiState.Ready)?.pendingSession == null
        }
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

        runCurrent()
        // Wait on pendingSession clearing (the save's own completion signal) rather than
        // `sessions.isNotEmpty()`: the DB write finishing (and thus `Resource.Success` clearing
        // pendingSession synchronously) can race ahead of Room's separate invalidation-triggered
        // re-query of `observeSessionsForMedia` that feeds `ready.sessions`, so waiting on
        // `sessions.isNotEmpty()` could resolve this `.first` on a stale pre-insert-visible
        // snapshot in principle.
        val ready = viewModel.uiState
            .first { it is BookDetailUiState.Ready && (it as BookDetailUiState.Ready).pendingSession == null }
                as BookDetailUiState.Ready

        assertNull(ready.pendingSession)
        assertNull(ready.errorMessage)
        // Query the repository directly for the persisted count, rather than trusting
        // `ready.sessions` (sourced from this same combined flow): pendingSession only clears
        // after the guarded save's DB write has already committed, so a fresh query here reflects
        // the true persisted state regardless of the uiState flow's own emission timing -- a
        // regression that let the double-tap's second call also insert would show up here as 2,
        // even if a uiState snapshot happened to be read before that second insert's row appeared.
        val persisted = sessionRepository.observeSessionsForMedia(mediaId).first()
        assertEquals(1, persisted.size, "double-tap on Save must persist exactly one session row")
    }

    @Test
    fun saveSession_withNoPendingSession_isNoOp() = runTest {
        insertBook()
        val viewModel = newViewModel()
        viewModel.uiState.first { it is BookDetailUiState.Ready }

        viewModel.saveSession(startUnit = 0.0, endUnit = 10.0)

        // A save with no pending session is a no-op, so there is no state change to wait *for* --
        // runCurrentUntilOrTimeOut is the wrong tool here, since it now fails when its condition
        // never holds. Draining the scheduler is enough, and it matters: without it this asserts
        // before the save's coroutine has run at all, and would pass whether the guard works or not.
        runCurrent()
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
     *
     * There is no observable side effect distinguishing "save(A)'s completion ran and correctly
     * no-op'd" from "save(A)'s completion hasn't run yet" -- a correct guard produces no visible
     * change either way. So instead of draining a fixed, arbitrarily-chosen number of scheduler
     * rounds and assuming that was enough time for it to have run, the final phase proves it ran
     * (and cleared `saveInFlight`) by repeatedly attempting to save the still-pending session B
     * and confirming it eventually persists -- something only possible once save(A)'s `finally`
     * has actually executed. See the inline comment at that call site for the full reasoning.
     */
    @Test
    fun saveSession_staleCompletionDoesNotClobberNewerPendingSession() = runTest {
        // Reinstalls Main through the registry (not Dispatchers.setMain directly) so
        // ViewModelRegistry.clearAll drains *this* scheduler at teardown, not the one setUp()
        // installed -- see ViewModelRegistry's KDoc on why that distinction matters specifically
        // for a StandardTestDispatcher (nothing else will ever drain a resumption it queues).
        viewModels.installMain(StandardTestDispatcher(testScheduler))
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
        // start/stop a brand-new run -> pendingSession = B in the ViewModel's own `_local` state.
        // These are plain, synchronous `MutableStateFlow` updates with no coroutine dispatch, so
        // they take effect strictly before save(A)'s still-unstarted coroutine gets a chance to
        // run -- but propagating them through `combine(...).stateIn(...)` into the externally-
        // collected `latestReady` still needs the Main-dispatcher `StandardTestDispatcher` to
        // actually run, hence the `runCurrentUntilOrTimeOut` below rather than reading
        // `latestReady` immediately.
        viewModel.discardPendingSession()
        viewModel.startReading()
        viewModel.stopReading()
        runCurrentUntilOrTimeOut { latestReady?.pendingSession !== pendingA }
        val pendingB = assertNotNull(latestReady?.pendingSession)
        assertTrue(pendingB !== pendingA, "pendingSession must have moved on to a new session B")

        // Let save(A) actually run and reach the database. Its row appearing is a real,
        // dispatcher-agnostic signal that the coroutine has passed the suspend point.
        runCurrentUntilOrTimeOut {
            sessionRepository.observeSessionsForMedia(mediaId).first().any { it.notes == "A" }
        }
        assertTrue(
            sessionRepository.observeSessionsForMedia(mediaId).first().any { it.notes == "A" },
            "save(A) must have persisted its row before this assertion",
        )
        // The DB row appearing is not the same moment as save(A)'s own coroutine resuming on Main
        // to run its `when` branch + `finally { saveInFlight = false }` (Room's invalidation-
        // triggered re-query and save(A)'s own continuation are two independent consequences of
        // the same commit, racing on different dispatchers). pendingSession must still be B
        // either way -- a correctly-guarded completion never touches it -- so this check alone
        // can't yet distinguish "guard ran and correctly no-op'd" from "guard hasn't run yet".
        assertTrue(
            latestReady?.pendingSession === pendingB,
            "stale completion of save(A) must not silently wipe or replace pendingSession = B",
        )

        // Prove save(A)'s completion actually ran -- and cleared `saveInFlight` -- rather than
        // draining a fixed, arbitrarily-chosen number of scheduler rounds and hoping that was
        // enough: repeatedly attempt to save the still-pending B and observe that it eventually
        // persists. Per `saveSession`'s own contract, each attempt is a same-shape no-op while
        // `saveInFlight` is still true (i.e. while save(A) hasn't reached its `finally` yet); the
        // first attempt made after `saveInFlight` clears actually starts and persists B. Bounded
        // via the same `runCurrentUntilOrTimeOut` helper used above so a genuine regression --
        // `saveInFlight` stuck true, or (if the stale `===` guard were removed) save(A)'s
        // completion wiping B's pendingSession to null before this can even attempt to save it --
        // times out with a clear failure below instead of hanging.
        runCurrentUntilOrTimeOut {
            viewModel.saveSession(startUnit = 55.0, endUnit = 61.0, notes = "B")
            sessionRepository.observeSessionsForMedia(mediaId).first().any { it.notes == "B" }
        }
        assertTrue(
            sessionRepository.observeSessionsForMedia(mediaId).first().any { it.notes == "B" },
            "save(B) must be accepted once save(A)'s stale completion clears saveInFlight, " +
                "proving the ViewModel isn't left stuck and B's pendingSession wasn't corrupted",
        )

        // A's row must remain untouched by B's own (later, unrelated) save.
        assertTrue(sessionRepository.observeSessionsForMedia(mediaId).first().any { it.notes == "A" })

        val finalReady = assertNotNull(latestReady)
        assertNull(finalReady.pendingSession, "save(B) succeeding must clear pendingSession")
        assertNull(
            finalReady.errorMessage,
            "neither A's stale completion nor save(B)'s own completion should set an error",
        )
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
    fun logManualSession_nullDuration_persistsSessionWithNullDuration() = runTest {
        // Schema v2 (ROADMAP Task 5 pre-phase): a backlogged manual entry may omit duration
        // entirely; it must persist as null (unknown), never coerced to 0 (see
        // ReadingSessionEntity's KDoc on why 0 and null must stay distinct).
        insertBook()
        val viewModel = newViewModel()
        viewModel.uiState.first { it is BookDetailUiState.Ready }

        val start = Instant.fromEpochMilliseconds(1_700_000_000_000)
        viewModel.logManualSession(
            timestampStart = start,
            timestampEnd = start,
            durationSeconds = null,
            startUnit = 20.0,
            endUnit = 20.0,
        )

        val ready = viewModel.uiState
            .first { it is BookDetailUiState.Ready && (it as BookDetailUiState.Ready).sessions.isNotEmpty() }
                as BookDetailUiState.Ready

        assertEquals(1, ready.sessions.size)
        assertNull(ready.sessions.first().durationSeconds)
    }

    @Test
    fun updateSession_persistsAllFieldChanges() = runTest {
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
            notes = "Original",
        )
        assertIs<Resource.Success<String>>(addResult)
        val sessionId = addResult.data
        viewModel.uiState.first { it is BookDetailUiState.Ready && (it as BookDetailUiState.Ready).sessions.isNotEmpty() }

        viewModel.updateSession(
            sessionId = sessionId,
            timestampStart = start,
            timestampEnd = start.plus(2.hours),
            durationSeconds = 7_200,
            startUnit = 0.0,
            endUnit = 90.0,
            deltaPages = 90,
            notes = "Edited",
        )

        val ready = viewModel.uiState
            .first {
                it is BookDetailUiState.Ready &&
                    (it as BookDetailUiState.Ready).sessions.firstOrNull()?.notes == "Edited"
            } as BookDetailUiState.Ready

        assertEquals(1, ready.sessions.size)
        val edited = ready.sessions.first()
        assertEquals(sessionId, edited.id)
        assertEquals(90.0, edited.endUnit)
        assertEquals(7_200L, edited.durationSeconds)
        assertEquals(90, edited.deltaPages)
        assertEquals("Edited", edited.notes)
        assertNull(ready.errorMessage)
    }

    /**
     * Regression test for the Task 6 Phase B data-integrity defect where `BookDetailScreen`'s
     * `ManualSessionDialog` (app module) silently coarsened a timer-backed session's sub-minute
     * precision `durationSeconds` to the nearest whole minute the moment its Save button was
     * tapped -- even when the user opened the dialog only to fix an unrelated field (e.g. a
     * position/page number) and never touched the duration field at all. AGENTS.md §1 (user data
     * safety overrides development shortcuts) forbids silently mutating a field the user never
     * touched, so that dialog was fixed to re-emit the session's original `durationSeconds`
     * verbatim whenever its duration text is unchanged from what it was prefilled with.
     *
     * This test lives here, not in the app module, because [BookDetailViewModel.updateSession]
     * already takes `durationSeconds` directly (it always did -- the defect was entirely in the
     * Compose dialog's minutes<->seconds conversion, one layer above this ViewModel). What this
     * test proves is the *contract* the UI fix depends on: this ViewModel/repository/DAO stack
     * must persist whatever `durationSeconds` it's given byte-identical, including a value that is
     * NOT an exact multiple of 60 (1_847s = 30m47s), when only `startUnit`/`endUnit` also change
     * in the same call -- i.e. nothing below the UI layer rounds, truncates, or otherwise
     * reinterprets a sub-minute-precision duration during an update.
     *
     * What this test does NOT prove: it does not exercise `ManualSessionDialog` itself (there is
     * no Compose UI test harness in this shared-module, commonTest target), so it cannot directly
     * verify that the dialog actually detects "duration field untouched" and passes through
     * `originalDurationSeconds` rather than the rounded-then-reconverted minutes value. That
     * detection logic (comparing the live duration text against its captured prefill) is pure
     * Compose state colocated with the dialog and has no shared-module seam to test against here.
     * This test instead pins down the one thing that logic depends on: if the dialog *does* pass
     * 1_847 through unchanged, this stack will not itself corrupt it -- so the layer this test
     * cannot reach is exactly the layer the fix lives in, and no lower.
     */
    @Test
    fun updateSession_positionOnlyChange_preservesSubMinuteDurationPrecision() = runTest {
        insertBook()
        val viewModel = newViewModel()
        viewModel.uiState.first { it is BookDetailUiState.Ready }

        val start = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val addResult = sessionRepository.logSession(
            mediaId = mediaId,
            timestampStart = start,
            timestampEnd = start.plus(1_847.seconds),
            durationSeconds = 1_847,
            startUnit = 10.0,
            endUnit = 20.0,
            notes = "Timer run",
        )
        assertIs<Resource.Success<String>>(addResult)
        val sessionId = addResult.data
        viewModel.uiState.first { it is BookDetailUiState.Ready && (it as BookDetailUiState.Ready).sessions.isNotEmpty() }

        // Simulate the fixed dialog's Save call for "user only corrected the end position": every
        // argument matches the original row except endUnit, and durationSeconds is passed through
        // as the original 1_847 verbatim -- exactly what ManualSessionDialog now does when its
        // duration text is unchanged from its prefill.
        viewModel.updateSession(
            sessionId = sessionId,
            timestampStart = start,
            timestampEnd = start.plus(1_847.seconds),
            durationSeconds = 1_847,
            startUnit = 10.0,
            endUnit = 25.0,
            notes = "Timer run",
        )

        val ready = viewModel.uiState
            .first {
                it is BookDetailUiState.Ready &&
                    (it as BookDetailUiState.Ready).sessions.firstOrNull()?.endUnit == 25.0
            } as BookDetailUiState.Ready

        val edited = ready.sessions.first()
        assertEquals(25.0, edited.endUnit)
        assertEquals(
            1_847L,
            edited.durationSeconds,
            "editing an unrelated field (position) must not round durationSeconds to the nearest minute",
        )
        assertNull(ready.errorMessage)
    }

    @Test
    fun updateSession_validationError_leavesSessionUnchangedAndSetsErrorMessage() = runTest {
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
            notes = "Original",
        )
        assertIs<Resource.Success<String>>(addResult)
        val sessionId = addResult.data
        viewModel.uiState.first { it is BookDetailUiState.Ready && (it as BookDetailUiState.Ready).sessions.isNotEmpty() }

        // Negative startUnit fails LogReadingSessionUseCase.executeUpdate validation without
        // persisting -- the existing row must survive untouched.
        viewModel.updateSession(
            sessionId = sessionId,
            timestampStart = start,
            timestampEnd = start.plus(1.hours),
            durationSeconds = 3_600,
            startUnit = -1.0,
            endUnit = 50.0,
        )

        val ready = viewModel.uiState
            .first { it is BookDetailUiState.Ready && (it as BookDetailUiState.Ready).errorMessage != null }
                as BookDetailUiState.Ready

        assertNotNull(ready.errorMessage)
        assertTrue(ready.errorMessage!!.contains("startUnit"))
        val unchanged = ready.sessions.first()
        assertEquals("Original", unchanged.notes)
        assertEquals(0.0, unchanged.startUnit)
        assertEquals(50.0, unchanged.endUnit)
    }

    @Test
    fun updateSession_nonexistentId_setsErrorMessage() = runTest {
        insertBook()
        val viewModel = newViewModel()
        viewModel.uiState.first { it is BookDetailUiState.Ready }

        val start = Instant.fromEpochMilliseconds(1_700_000_000_000)
        viewModel.updateSession(
            sessionId = newId(),
            timestampStart = start,
            timestampEnd = start,
            durationSeconds = 0,
            startUnit = 0.0,
            endUnit = 0.0,
        )

        val ready = viewModel.uiState
            .first { it is BookDetailUiState.Ready && (it as BookDetailUiState.Ready).errorMessage != null }
                as BookDetailUiState.Ready

        assertNotNull(ready.errorMessage)
        assertTrue(ready.sessions.isEmpty())
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

    /**
     * Regression test for the PR review finding that the delete-book action needs a shared-module
     * success path: [BookDetailViewModel.deleteBook] deletes [BookDetailViewModel]'s own [bookId]
     * and, on [Resource.Success], [uiState] must reactively become [BookDetailUiState.NotFound]
     * once [BookRepository.observeBookDetail] emits null for the now-gone id -- the signal
     * [BookDetailScreenRoute]'s `LaunchedEffect` uses to auto-navigate back.
     *
     * The [Resource.Error] branch (e.g. a DB failure surfaced via
     * [BookDetailUiState.Ready.errorMessage]) is not covered here: producing a genuine delete
     * failure against the real in-memory [AppDatabase] this test class uses would need a
     * contrived fake repository (deleting an already-missing id is a no-op `Resource.Success` per
     * [MediaItemDao.deleteById], not an error -- same reasoning as the [deleteSession] KDoc fix),
     * which is out of scope for this file's real-DB test style.
     */
    @Test
    fun deleteBook_removesBook_uiStateBecomesNotFound() = runTest {
        insertBook()
        val viewModel = newViewModel()
        viewModel.uiState.first { it is BookDetailUiState.Ready }

        viewModel.deleteBook()

        val state = viewModel.uiState.first { it is BookDetailUiState.NotFound }
        assertIs<BookDetailUiState.NotFound>(state)
    }

    /**
     * ROADMAP Task 6 Phase E: [BookDetailViewModel.refetchCover] surfaces a
     * [RefetchCoverUseCase.execute] failure via [BookDetailUiState.Ready.errorMessage], the same
     * convention every other mutating method on this class uses. Detailed use-case-level coverage
     * (happy path, no-ISBN, provider-coverless, download failure) lives in
     * [com.hub.media.features.books.domain.RefetchCoverUseCaseTest] -- this test only proves the
     * ViewModel plumbs the result through and resets [BookDetailUiState.Ready.isRefetchingCover].
     */
    @Test
    fun refetchCover_useCaseError_setsErrorMessageAndClearsInFlightFlag() = runTest {
        insertBook()
        val viewModel = newViewModel()
        viewModel.uiState.first { it is BookDetailUiState.Ready }

        viewModel.refetchCover()

        val ready = viewModel.uiState
            .first { it is BookDetailUiState.Ready && (it as BookDetailUiState.Ready).errorMessage != null }
                as BookDetailUiState.Ready

        assertNotNull(ready.errorMessage)
        assertTrue(ready.errorMessage!!.contains(PROVIDER_ERROR_MESSAGE))
        assertEquals(false, ready.isRefetchingCover)
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
        assertIs<ReadingTimerState.Idle>(
            viewModel.timerState.value,
            "pause/resume/stop before any start must no-op, leaving the timer Idle",
        )

        viewModel.startReading()
        viewModel.startReading() // second start while Running must no-op, not throw.
        assertIs<ReadingTimerState.Running>(
            viewModel.timerState.value,
            "a second start while Running must no-op",
        )

        viewModel.pauseReading()
        viewModel.pauseReading() // second pause while Paused must no-op, not throw.
        assertIs<ReadingTimerState.Paused>(
            viewModel.timerState.value,
            "a second pause while Paused must no-op",
        )

        viewModel.resumeReading()
        viewModel.resumeReading() // second resume while Running must no-op, not throw.
        assertIs<ReadingTimerState.Running>(
            viewModel.timerState.value,
            "a second resume while Running must no-op",
        )

        viewModel.stopReading()
        // Waited for, not read. The pending session reaches uiState through combine -> stateIn,
        // which is not guaranteed to have propagated by the time stopReading() returns -- reading
        // .value straight away made this the likely source of a message-less AssertionError that
        // failed CI once and never reproduced locally. Same mistake as reading state immediately
        // after an action anywhere else in this file; the surrounding tests already wait.
        runCurrentUntilOrTimeOut {
            (viewModel.uiState.value as? BookDetailUiState.Ready)?.pendingSession != null
        }
        val pendingAfterFirstStop =
            (viewModel.uiState.value as BookDetailUiState.Ready).pendingSession
        assertNotNull(pendingAfterFirstStop, "stopReading must leave a pending session to save")

        viewModel.stopReading() // second stop while Idle must no-op, not throw or overwrite pending.
        assertIs<ReadingTimerState.Idle>(
            viewModel.timerState.value,
            "a second stop while Idle must no-op",
        )
        runCurrent()
        assertEquals(
            pendingAfterFirstStop,
            (viewModel.uiState.value as BookDetailUiState.Ready).pendingSession,
            "a second stop must not overwrite the pending session the first one produced",
        )
    }
}
