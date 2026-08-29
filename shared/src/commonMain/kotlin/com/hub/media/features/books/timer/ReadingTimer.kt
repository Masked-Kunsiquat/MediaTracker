package com.hub.media.features.books.timer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Lifecycle states of a [ReadingTimer], exposed for UI (e.g. gating which of start/pause/
 * resume/stop are currently valid to call — see [ReadingTimer]'s KDoc for the transition
 * contract).
 */
public sealed interface ReadingTimerState {
    /** No run in progress. The only state from which [ReadingTimer.start] is valid. */
    public data object Idle : ReadingTimerState

    /** A run is actively ticking. [ReadingTimer.elapsedSeconds] increases roughly once/second. */
    public data object Running : ReadingTimerState

    /** A run is suspended: [ReadingTimer.elapsedSeconds] is frozen until [ReadingTimer.resume]. */
    public data object Paused : ReadingTimerState
}

/**
 * The outcome of a finished [ReadingTimer] run, ready to be handed to
 * [com.hub.media.features.books.domain.LogReadingSessionUseCase].
 *
 * @property timestampStart Wall-clock time [ReadingTimer.start] was called.
 * @property timestampEnd Wall-clock time [ReadingTimer.stop] was called.
 * @property durationSeconds Actively-running seconds only. Time spent [ReadingTimerState.Paused]
 *   is excluded, so this is usually less than `timestampEnd - timestampStart` — see [ReadingTimer]
 *   class KDoc for why the two are computed independently instead of one being derived from the
 *   other.
 */
public data class ReadingTimerResult(
    val timestampStart: Instant,
    val timestampEnd: Instant,
    val durationSeconds: Long,
)

/**
 * Coroutine/Flow-based reading stopwatch (ROADMAP Task 4 Phase A: start/pause/resume/stop with an
 * elapsed-time [Flow][kotlinx.coroutines.flow.Flow]).
 *
 * ### Why `features.books.timer` and not `features.books.domain`
 * The other classes under `features.books.domain` (e.g.
 * [AddBookByIsbnUseCase][com.hub.media.features.books.domain.AddBookByIsbnUseCase]) are
 * request/response style use cases: stateless, constructed per call or held only for their
 * dependencies, `execute(...)`-and-done. [ReadingTimer] is the opposite shape — a mutable,
 * long-lived object with its own internal ticking coroutine and externally-observed state — so it
 * gets its own subpackage rather than being squeezed into the use-case package.
 *
 * [ReadingTimer] is pure common Kotlin with no Room and no Android API dependency at all, so its
 * tests belong in `commonTest` and must keep passing on both `:shared:jvmTest` and
 * `testDebugUnitTest`/`testReleaseUnitTest`. Until #81 that was also a *mechanical* argument for
 * the subpackage: `shared/build.gradle.kts` excluded `com.hub.media.features.books.domain.*` from
 * the Android unit-test variant by wildcard, so a pure test sitting in that package would have been
 * swept up and silently stopped running. That exclusion list is gone — Room-backed tests now live
 * in `jvmTest` and are separated by source set rather than by package pattern — so the subpackage
 * now rests on the design argument above alone. The requirement that these tests run on every
 * variant is unchanged.
 *
 * ### Time-source design: wall-clock timestamps vs. tick-counted duration
 * Two independent time sources are used on purpose:
 * - [clock] (an injected [kotlin.time.Clock], defaulting to [Clock.System]) is read exactly twice
 *   per run: once in [start] for [ReadingTimerResult.timestampStart], once in [stop] for
 *   [ReadingTimerResult.timestampEnd]. This records *when* (wall-clock) the session happened, for
 *   history/display purposes only.
 * - [elapsedSeconds] / [ReadingTimerResult.durationSeconds] (active duration) is accumulated by
 *   **counting ticks**, never by subtracting clock readings. A coroutine launched on [scope] loops
 *   `delay(tickInterval)` then increments a counter by one; that job is cancelled in [pause] and a
 *   fresh one relaunched in [resume]. Paused wall-clock time is therefore never observed by the
 *   accumulator at all — there is no "total minus paused span" subtraction to get right after the
 *   fact, and no way for a missed subtraction to leak paused time into the reported duration.
 *
 * This split is also what makes the class correctly testable under `kotlinx-coroutines-test`
 * virtual time: advancing the virtual clock via `advanceTimeBy`/`runCurrent` drives the exact same
 * `delay()` calls that drive the tick counter, so a virtual-time test sees precisely the number of
 * ticks it explicitly advances past — no wall-clock reads are involved in that count at all. If
 * duration were instead derived as `timestampEnd - timestampStart` minus a separately tracked
 * paused span, a virtual-time test would have to keep an injected fake clock and the coroutine
 * scheduler's virtual time in hand-rolled lockstep merely to avoid the computed duration silently
 * drifting from the tick-driven [elapsedSeconds] Flow the UI actually observes; counting ticks
 * sidesteps that entirely.
 *
 * ### State machine / invalid-transition contract
 * [start] requires [ReadingTimerState.Idle]; [pause] requires [ReadingTimerState.Running];
 * [resume] requires [ReadingTimerState.Paused]; [stop] requires [ReadingTimerState.Running] or
 * [ReadingTimerState.Paused]. Calling any of them from the wrong state throws
 * [IllegalStateException] rather than silently no-op-ing. A caller (typically a ViewModel gating
 * button enablement off [state]) getting this wrong is a programming error worth surfacing loudly
 * in tests/dev builds, not a runtime condition to swallow. [stop] resets the instance back to
 * [ReadingTimerState.Idle] afterward, so the same [ReadingTimer] can be reused for the next
 * session (see the reuse test in `ReadingTimerTest`) rather than needing to be reconstructed.
 *
 * A 0-second session — `start()` immediately followed by `stop()` with no elapsed ticks — is a
 * valid, explicitly supported result (AGENTS.md §7 "0-second timers"): [elapsedSeconds] simply
 * never left `0`.
 *
 * @param clock Wall-clock time source for [ReadingTimerResult] timestamps. Defaults to
 *   [Clock.System]; tests inject a fake tied to the coroutine test scheduler's virtual time so
 *   `timestampStart`/`timestampEnd` are deterministic.
 * @param scope Coroutine scope the internal tick loop runs on. Must be single-threaded/
 *   thread-confined (e.g. a main-dispatcher `viewModelScope`, or a test scheduler) — [state] and
 *   [elapsedSeconds] are updated from this scope's tick loop with no additional synchronization,
 *   so a multi-threaded scope could race those updates with concurrent calls to
 *   [start]/[pause]/[resume]/[stop]. Callers own its lifecycle; tests pass a `TestScope` (or its
 *   `backgroundScope`) to drive the loop with virtual time via `kotlinx-coroutines-test`.
 * @param tickInterval How often [elapsedSeconds] increments while [ReadingTimerState.Running].
 *   Defaults to one second per the ROADMAP's "elapsed-time Flow" ticking ~1/second.
 */
public class ReadingTimer(
    private val clock: Clock = Clock.System,
    private val scope: CoroutineScope,
    private val tickInterval: Duration = 1.seconds,
) {
    private val _state = MutableStateFlow<ReadingTimerState>(ReadingTimerState.Idle)

    /** Current lifecycle state, for UI to gate which action buttons are enabled. */
    public val state: StateFlow<ReadingTimerState> = _state.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)

    /**
     * Actively-running seconds elapsed so far in the current run. Ticks up roughly once/second
     * while [state] is [ReadingTimerState.Running]; frozen while [ReadingTimerState.Paused]; reset
     * to `0` when a new run [start]s (and immediately after [stop] returns).
     */
    public val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private var tickJob: Job? = null
    private var timestampStart: Instant? = null

    /**
     * Starts a fresh run: records [ReadingTimerResult.timestampStart], resets [elapsedSeconds] to
     * `0`, and begins ticking.
     *
     * @throws IllegalStateException if [state] is not [ReadingTimerState.Idle].
     */
    public fun start() {
        check(_state.value is ReadingTimerState.Idle) {
            "ReadingTimer.start() requires Idle state, was ${_state.value}"
        }
        timestampStart = clock.now()
        _elapsedSeconds.value = 0
        _state.value = ReadingTimerState.Running
        startTicking()
    }

    /**
     * Freezes [elapsedSeconds] at its current value; the elapsed time between this call and the
     * matching [resume] does not count toward the eventual [ReadingTimerResult.durationSeconds].
     *
     * @throws IllegalStateException if [state] is not [ReadingTimerState.Running].
     */
    public fun pause() {
        check(_state.value is ReadingTimerState.Running) {
            "ReadingTimer.pause() requires Running state, was ${_state.value}"
        }
        stopTicking()
        _state.value = ReadingTimerState.Paused
    }

    /**
     * Continues ticking from wherever [pause] left [elapsedSeconds].
     *
     * @throws IllegalStateException if [state] is not [ReadingTimerState.Paused].
     */
    public fun resume() {
        check(_state.value is ReadingTimerState.Paused) {
            "ReadingTimer.resume() requires Paused state, was ${_state.value}"
        }
        _state.value = ReadingTimerState.Running
        startTicking()
    }

    /**
     * Ends the current run and returns its [ReadingTimerResult]. Resets back to
     * [ReadingTimerState.Idle] (and [elapsedSeconds] to `0`) afterward so this instance can be
     * reused for a subsequent session.
     *
     * @throws IllegalStateException if [state] is [ReadingTimerState.Idle] (nothing to stop).
     */
    public fun stop(): ReadingTimerResult {
        val current = _state.value
        check(current is ReadingTimerState.Running || current is ReadingTimerState.Paused) {
            "ReadingTimer.stop() requires Running or Paused state, was $current"
        }
        stopTicking()
        val start =
            checkNotNull(timestampStart) {
                "ReadingTimer invariant violated: $current state with no recorded start"
            }
        val end = clock.now()
        val duration = _elapsedSeconds.value

        timestampStart = null
        _elapsedSeconds.value = 0
        _state.value = ReadingTimerState.Idle

        return ReadingTimerResult(timestampStart = start, timestampEnd = end, durationSeconds = duration)
    }

    private fun startTicking() {
        // Defensive: the state machine above already prevents start()/resume() from being called
        // while a tick job is running, but cancelling any leftover job first is free and rules out
        // an accidental duplicate ticker if that invariant is ever loosened.
        tickJob?.cancel()
        tickJob =
            scope.launch {
                while (isActive) {
                    delay(tickInterval)
                    _elapsedSeconds.value += 1
                }
            }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }
}
