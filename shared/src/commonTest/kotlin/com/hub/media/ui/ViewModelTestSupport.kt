package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain

/**
 * Shared fix for the flaky `IllegalStateException: Dispatchers.Main was accessed after
 * resetMain()` that hit every `ViewModel` test in this package (`SettingsViewModelTest`,
 * `StatsViewModelTest`, `LibraryViewModelTest`, `EditBookViewModelTest`,
 * `BookDetailViewModelTest`, `AddBookViewModelTest`).
 *
 * ### Root cause
 * Each test's `uiState` is built with `stateIn(viewModelScope, WhileSubscribed(5.seconds), ...)`.
 * `viewModelScope` is only cancelled by `ViewModel.onCleared()`, which is `protected` and normally
 * invoked by a `ViewModelStore` being cleared (e.g. on navigation away in a real app). Nothing was
 * calling it here, so a test's `stateIn` sharing coroutine (parked in the `WhileSubscribed` grace
 * -period `delay`) — or a fire-and-forget `viewModelScope.launch { ... }` write — could still be
 * alive when `@AfterTest` ran `Dispatchers.resetMain()`. If that survivor later tried to dispatch
 * back onto `Dispatchers.Main`, it hit the now-torn-down stand-in dispatcher and threw.
 *
 * ### Fix, and the two things that were each necessary but not sufficient on their own
 * A [ViewModelStore] is the standard way to trigger `onCleared()` from a test: put the instance in,
 * then [ViewModelStore.clear] it — that cancels `viewModelScope`'s [Job]. Two problems remain even
 * after that:
 *
 * 1. `Job.cancel()` is fire-and-forget: it *marks* the job (and its children) cancelled and returns
 *    immediately, it does not wait for them to actually finish unwinding. `testAppDatabase()` runs
 *    Room's own query/invalidation dispatching on a real `Dispatchers.Default` thread (not this
 *    test's virtual scheduler), so a child coroutine genuinely suspended there only notices the
 *    cancellation and tries to resume back onto `Dispatchers.Main` some short, real, unbounded time
 *    later — which must happen before `Dispatchers.resetMain()` runs, not after.
 * 2. A cancelled coroutine's *own* resumption (e.g. one parked in `delay()`, such as the
 *    `WhileSubscribed(5.seconds)` grace-period timer, or `ReadingTimer`'s tick loop) is itself
 *    dispatched back onto `Dispatchers.Main`. Whichever `TestDispatcher` a test installs there
 *    decides whether that dispatch runs *eagerly*: [UnconfinedTestDispatcher] (every test here
 *    except one) executes it inline, synchronously, as part of cancellation itself. A
 *    `StandardTestDispatcher` (used by exactly one test, deliberately, to force strict ordering —
 *    see `BookDetailViewModelTest.saveSession_staleCompletionDoesNotClobberNewerPendingSession`'s
 *    KDoc) instead only *enqueues* it on that dispatcher's [TestCoroutineScheduler], where it sits
 *    forever unless something explicitly drains the scheduler — and by the time `tearDown()` runs,
 *    `runTest`'s own internal draining has already happened *once*, before this cancellation ever
 *    queued anything, so nothing will ever run it otherwise. (Confirmed empirically: an
 *    earlier version of this fix that only did `Job.join()` inside `runBlocking` hung forever,
 *    verified via a thread dump showing every dispatcher thread idle while `join()` parked — the
 *    join wasn't waiting on real work, it was waiting on a resumption that was queued but never
 *    going to be drained.)
 *
 * [clearAll] handles both: every test must install `Dispatchers.Main` via [installMain] (rather than
 * calling `Dispatchers.setMain` directly) so this class always knows the active
 * [TestCoroutineScheduler]; [track] captures each `ViewModel`'s `viewModelScope` [Job] up front; and
 * [clearAll] alternates draining that scheduler with brief real-time waits (bridging real
 * `Dispatchers.Default` progress into virtual-Main progress and back, the same idiom
 * `BookDetailViewModelTest`'s own `runCurrentUntilOrTimeOut` helper already uses for the same
 * real/virtual-time-boundary reason) until every tracked scope has genuinely finished, bounded so a
 * real regression fails loudly instead of hanging.
 *
 * A plain helper class (composed by each test, not a base class) matches this module's existing
 * test-support convention (see `TestDatabaseBuilder.kt`, `TestStorageHelper.kt`) and keeps each test
 * file's own `tearDown()` spelling out the clear -> close -> resetMain order explicitly, rather than
 * hiding it behind inherited `@AfterTest` ordering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ViewModelRegistry {

    private val store = ViewModelStore()
    private var nextKey = 0
    private val scopeJobs = mutableListOf<Job>()
    private var mainScheduler: TestCoroutineScheduler? = null

    /**
     * Installs [dispatcher] as `Dispatchers.Main` (a fresh [UnconfinedTestDispatcher] by default)
     * and remembers its [TestCoroutineScheduler] so [clearAll] can drain it later. Every test in
     * this package must install Main through this — including a test that reinstalls Main partway
     * through with a different [TestDispatcher] (see `BookDetailViewModelTest`'s
     * `saveSession_staleCompletionDoesNotClobberNewerPendingSession`) — rather than calling
     * `Dispatchers.setMain` directly, or [clearAll] would drain the wrong (stale) scheduler.
     */
    fun installMain(dispatcher: TestDispatcher = UnconfinedTestDispatcher()) {
        mainScheduler = dispatcher.scheduler
        Dispatchers.setMain(dispatcher)
    }

    /**
     * Registers [viewModel] so [clearAll] cancels its `viewModelScope` and waits for that
     * cancellation to fully complete. Returns it unchanged.
     */
    fun <T : ViewModel> track(viewModel: T): T {
        store.put((nextKey++).toString(), viewModel)
        // Captured now, before onCleared() ever runs -- see class KDoc for why the Job reference
        // must be grabbed up front rather than re-read from viewModelScope later.
        viewModel.viewModelScope.coroutineContext[Job]?.let { scopeJobs += it }
        return viewModel
    }

    /**
     * Calls `onCleared()` on every [track]-ed `ViewModel` (cancelling its `viewModelScope`), then
     * waits until each of those scopes' jobs has fully finished unwinding — draining
     * [installMain]'s [TestCoroutineScheduler] (for a cancelled coroutine's own resumption, if the
     * installed dispatcher doesn't run it eagerly) alternated with brief real-time waits (for a
     * genuinely real background-thread coroutine, e.g. one touching Room's query dispatcher, to
     * make progress) — see class KDoc. Does not return until it is safe to close the database or
     * call `Dispatchers.resetMain()`. Bounded to 2 seconds of real time so a genuine regression
     * fails loudly with a clear assertion rather than hanging.
     */
    fun clearAll() {
        store.clear()
        var attempts = 0
        while (scopeJobs.any { !it.isCompleted } && attempts < 400) {
            mainScheduler?.advanceUntilIdle()
            if (scopeJobs.all { it.isCompleted }) break
            Thread.sleep(5)
            attempts++
        }
        check(scopeJobs.all { it.isCompleted }) {
            "ViewModelRegistry.clearAll timed out after 2s waiting for tracked ViewModel(s)' " +
                "viewModelScope to finish cancelling -- a genuinely leaked/stuck coroutine, not " +
                "the Dispatchers.Main-after-resetMain race this class exists to prevent."
        }
    }
}
