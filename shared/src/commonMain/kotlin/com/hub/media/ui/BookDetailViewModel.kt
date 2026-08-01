package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.books.domain.LogReadingSessionUseCase
import com.hub.media.features.books.timer.ReadingTimer
import com.hub.media.features.books.timer.ReadingTimerResult
import com.hub.media.features.books.timer.ReadingTimerState
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the book-detail screen (ROADMAP Task 4 Phase B): book metadata, session history,
 * derived current progress, and the reading timer for [bookId].
 *
 * ### Reactive [uiState]
 * [BookRepository.observeBookDetail] and [ReadingSessionRepository.observeSessionsForMedia] are
 * both DB-backed reactive Flows; a third, purely in-memory [MutableStateFlow] ([_local]) tracks
 * UI-only state that has no DB representation ([BookDetailUiState.Ready.pendingSession] and
 * [BookDetailUiState.Ready.errorMessage]). All three are `combine`d into [uiState] so a fresh DB
 * emission (e.g. a new session appearing after [saveSession] persists it) never clobbers an
 * in-flight pending timer result or error the user hasn't dismissed yet, and vice versa.
 *
 * ### Why [pendingSession] lives on [BookDetailUiState.Ready] rather than a separate `StateFlow`
 * A second top-level `StateFlow<ReadingTimerResult?>` would let the UI observe it out of sync with
 * [uiState] (e.g. re-collect one before the other after a process/config-style restart of
 * collectors), and would need its own `NotFound`/`Loading` handling duplicated on the side. Folding
 * it into [BookDetailUiState.Ready] means there's exactly one state object the screen renders from,
 * consistent with [LibraryViewModel] and [AddBookViewModel]'s single-`StateFlow` shape.
 *
 * ### Timer ownership and gating
 * This ViewModel owns one [ReadingTimer] built on [viewModelScope] for [bookId]'s lifetime.
 * [ReadingTimer]'s start/pause/resume/stop each throw [IllegalStateException] on an invalid
 * transition by contract (see its KDoc) — that is intentional there (a caller getting the state
 * machine wrong is a programming error worth surfacing loudly). This ViewModel is the layer that
 * gates: [startReading]/[pauseReading]/[resumeReading]/[stopReading] each check [timerState] first
 * and silently no-op on a mismatched call (e.g. a double-tap firing `startReading()` twice in a
 * row), so a UI double-fire can never crash the screen.
 *
 * @param bookId The media id this screen was opened for.
 * @param bookRepository Source of reactive book metadata.
 * @param readingSessionRepository Source of reactive session history and [deleteSession].
 * @param logReadingSessionUseCase Persists both the timer-backed ([saveSession]) and manual
 *   ([logManualSession]) session-logging paths.
 * @param clock Wall-clock time source handed to the internal [ReadingTimer]. Defaults to
 *   [Clock.System]; tests inject a fake tied to a coroutine test scheduler for deterministic
 *   timestamps (see [ReadingTimer]'s KDoc).
 */
public class BookDetailViewModel(
    private val bookId: String,
    private val bookRepository: BookRepository,
    private val readingSessionRepository: ReadingSessionRepository,
    private val logReadingSessionUseCase: LogReadingSessionUseCase,
    clock: Clock = Clock.System,
) : ViewModel() {

    private val timer = ReadingTimer(clock = clock, scope = viewModelScope)

    /** Current [ReadingTimer] lifecycle state, for UI to gate which action buttons are enabled. */
    public val timerState: StateFlow<ReadingTimerState> = timer.state

    /** Re-exposed [ReadingTimer.elapsedSeconds], for a live running-time display. */
    public val elapsedSeconds: StateFlow<Long> = timer.elapsedSeconds

    /** UI-only state with no DB representation; see class KDoc. */
    private data class LocalState(
        val pendingSession: ReadingTimerResult? = null,
        val errorMessage: String? = null,
    )

    private val _local = MutableStateFlow(LocalState())

    /**
     * In-flight guard shared by [saveSession] and [logManualSession]: set synchronously (before
     * `launch`) the moment a save starts, cleared in that same coroutine's completion. A double-
     * tap on Save fires the click handler twice before the first `launch`'s suspending
     * [logReadingSessionUseCase] call completes; without this guard both calls would read the same
     * pending/explicit session data and both persist, producing a duplicate row. Plain `var` is
     * safe here (no atomics/mutex needed): [viewModelScope] dispatches on the main thread, so
     * these reads/writes are never concurrent, just interleaved between suspension points.
     */
    private var saveInFlight: Boolean = false

    public val uiState: StateFlow<BookDetailUiState> = combine(
        bookRepository.observeBookDetail(bookId),
        readingSessionRepository.observeSessionsForMedia(bookId),
        _local,
    ) { bookDetail, sessions, local ->
        if (bookDetail == null) {
            BookDetailUiState.NotFound
        } else {
            BookDetailUiState.Ready(
                book = bookDetail.mediaItem,
                details = bookDetail.details,
                sessions = sessions,
                pendingSession = local.pendingSession,
                errorMessage = local.errorMessage,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5.seconds),
        initialValue = BookDetailUiState.Loading,
    )

    /**
     * Starts a fresh timer run. No-ops (does not throw) unless [timerState] is currently
     * [ReadingTimerState.Idle] — see class KDoc on gating double-fires.
     */
    public fun startReading() {
        if (timer.state.value !is ReadingTimerState.Idle) return
        timer.start()
    }

    /**
     * Pauses the running timer. No-ops unless [timerState] is currently
     * [ReadingTimerState.Running].
     */
    public fun pauseReading() {
        if (timer.state.value !is ReadingTimerState.Running) return
        timer.pause()
    }

    /**
     * Resumes a paused timer. No-ops unless [timerState] is currently [ReadingTimerState.Paused].
     */
    public fun resumeReading() {
        if (timer.state.value !is ReadingTimerState.Paused) return
        timer.resume()
    }

    /**
     * Stops the timer and stores its [ReadingTimerResult] as [BookDetailUiState.Ready.pendingSession]
     * awaiting [saveSession]/[discardPendingSession]. No-ops unless [timerState] is currently
     * [ReadingTimerState.Running] or [ReadingTimerState.Paused] (nothing to stop otherwise).
     * Overwrites any previous unsaved [BookDetailUiState.Ready.pendingSession] and clears any
     * previous [BookDetailUiState.Ready.errorMessage] — a fresh run supersedes a stale one.
     */
    public fun stopReading() {
        val current = timer.state.value
        if (current !is ReadingTimerState.Running && current !is ReadingTimerState.Paused) return
        val result = timer.stop()
        _local.update { it.copy(pendingSession = result, errorMessage = null) }
    }

    /**
     * Persists the current [BookDetailUiState.Ready.pendingSession] (a finished timer run) via
     * [logReadingSessionUseCase], with user-supplied position bounds. No-ops if there is no
     * pending session. On success, clears [BookDetailUiState.Ready.pendingSession] and any prior
     * [BookDetailUiState.Ready.errorMessage]; on failure, sets
     * [BookDetailUiState.Ready.errorMessage] but leaves [BookDetailUiState.Ready.pendingSession]
     * intact so the user can correct their input and retry without re-timing the session.
     *
     * ### Stale-completion guard
     * [saveInFlight] blocks a second *save* from starting while one is in flight, but
     * [discardPendingSession] and [stopReading] are **not** gated by it (discarding or timing a
     * new run while a save is still in flight is allowed) — so by the time this coroutine's
     * suspending [logReadingSessionUseCase] call returns, [BookDetailUiState.Ready.pendingSession]
     * may have moved on to a newer, unrelated session (call it `B`): the user discarded the one
     * this save was for (`A`) and started/stopped a new timer run before `A`'s save finished.
     * Applying `A`'s outcome unconditionally at that point would silently wipe `B` (on
     * [Resource.Success], via the unconditional `LocalState()` reset) or mislabel it with `A`'s
     * error (on [Resource.Error]). Both branches below therefore compare
     * `_local.value.pendingSession` against the locally-captured [pending] (the exact session
     * this coroutine was launched for) with **referential equality (`===`)**, not `==`: even
     * though [ReadingTimerResult] is a data class, two independently-timed runs can be
     * structurally equal (e.g. two 0-second sessions started/stopped in the same clock
     * millisecond, which virtual-time tests and fast real hardware can both produce) while still
     * being logically distinct sessions — `==` would then wrongly treat `B` as `A` and apply the
     * stale result anyway. `===` identifies "the literal session object this coroutine closed
     * over," which is the only correct match here. If the check fails, the result is dropped
     * entirely and neither [BookDetailUiState.Ready.pendingSession] nor
     * [BookDetailUiState.Ready.errorMessage] is touched.
     *
     * Note: dropping a stale *[Resource.Success]* result this way does **not** undo the write —
     * [logReadingSessionUseCase] had already dispatched (and by this point, completed) the insert
     * for `A` by the time this check runs, so `A`'s row still persists even though the discard
     * happened first. That's accepted as correct: the user's *discard* only ever promised to
     * abandon the unsaved *pending* state, not to cancel a save that had already been explicitly
     * requested before the discard.
     *
     * @param startUnit Position (page or percent) at the start of the session. Must be finite and
     *   `>= 0` (see [LogReadingSessionUseCase]).
     * @param endUnit Position at the end of the session. Must be finite and `>= 0`; may be less
     *   than [startUnit] (re-reading backward is valid, see [LogReadingSessionUseCase] KDoc).
     * @param deltaPages Optional page delta.
     * @param notes Optional free-text notes.
     */
    public fun saveSession(
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int? = null,
        notes: String? = null,
    ) {
        val pending = _local.value.pendingSession ?: return
        if (saveInFlight) return
        saveInFlight = true
        viewModelScope.launch {
            try {
                when (
                    val result = logReadingSessionUseCase.execute(
                        mediaId = bookId,
                        timerResult = pending,
                        startUnit = startUnit,
                        endUnit = endUnit,
                        deltaPages = deltaPages,
                        notes = notes,
                    )
                ) {
                    is Resource.Success -> _local.update {
                        if (it.pendingSession === pending) LocalState() else it
                    }
                    is Resource.Error -> _local.update {
                        if (it.pendingSession === pending) it.copy(errorMessage = result.message) else it
                    }
                }
            } finally {
                saveInFlight = false
            }
        }
    }

    /**
     * Logs a session from explicit bounds with no live timer involved (a manual session-entry
     * form), via [LogReadingSessionUseCase]'s explicit-bounds overload. Independent of any
     * [BookDetailUiState.Ready.pendingSession]; does not touch it either way — neither completion
     * branch below reads or writes [LocalState.pendingSession], only [LocalState.errorMessage], so
     * this method has no stale-`pendingSession`-clobber risk analogous to [saveSession]'s and
     * needs no referential-equality guard for it.
     */
    public fun logManualSession(
        timestampStart: Instant,
        timestampEnd: Instant,
        durationSeconds: Long,
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int? = null,
        notes: String? = null,
    ) {
        if (saveInFlight) return
        saveInFlight = true
        viewModelScope.launch {
            try {
                when (
                    val result = logReadingSessionUseCase.execute(
                        mediaId = bookId,
                        timestampStart = timestampStart,
                        timestampEnd = timestampEnd,
                        durationSeconds = durationSeconds,
                        startUnit = startUnit,
                        endUnit = endUnit,
                        deltaPages = deltaPages,
                        notes = notes,
                    )
                ) {
                    is Resource.Success -> _local.update { it.copy(errorMessage = null) }
                    is Resource.Error -> _local.update { it.copy(errorMessage = result.message) }
                }
            } finally {
                saveInFlight = false
            }
        }
    }

    /**
     * Abandons the current [BookDetailUiState.Ready.pendingSession] without persisting it, and
     * clears any [BookDetailUiState.Ready.errorMessage]. The timed run is discarded entirely; the
     * user must start a new timer run (or use [logManualSession]) to log progress instead.
     *
     * Not gated by [saveInFlight]: calling this while a [saveSession] for the current
     * [BookDetailUiState.Ready.pendingSession] is still in flight is allowed and takes effect
     * immediately (unlike a second *save*, which [saveInFlight] blocks from starting). See
     * [saveSession]'s KDoc ("Stale-completion guard") for how that in-flight save's eventual
     * result is prevented from clobbering whatever [BookDetailUiState.Ready.pendingSession]
     * becomes afterward.
     */
    public fun discardPendingSession() {
        _local.update { LocalState() }
    }

    /**
     * Deletes the session identified by [sessionId]. On [Resource.Success], fire-and-forget,
     * matching [LibraryViewModel.deleteBook]: [uiState] reflects the removal reactively via
     * [ReadingSessionRepository.observeSessionsForMedia] once the delete completes, with no
     * explicit state update needed here. On [Resource.Error] (e.g. a DB failure/exception from
     * [ReadingSessionRepository.deleteSession]'s underlying `deleteById` call), sets
     * [BookDetailUiState.Ready.errorMessage] using the same surfacing convention as
     * [saveSession]/[logManualSession] so a failed delete isn't silently swallowed.
     */
    public fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            when (val result = readingSessionRepository.deleteSession(sessionId)) {
                is Resource.Success -> Unit
                is Resource.Error -> _local.update { it.copy(errorMessage = result.message) }
            }
        }
    }

    /**
     * Deletes [bookId] itself (the book this screen was opened for). On [Resource.Success],
     * fire-and-forget: [uiState] reflects the removal reactively once
     * [BookRepository.observeBookDetail] emits null for the now-gone media id, transitioning to
     * [BookDetailUiState.NotFound] — which [BookDetailScreenRoute]'s existing `LaunchedEffect`
     * already turns into an automatic navigate-back, so no separate post-delete navigation is
     * needed here. On [Resource.Error] (e.g. a DB failure), sets
     * [BookDetailUiState.Ready.errorMessage] using the same surfacing convention as
     * [deleteSession]/[saveSession]/[logManualSession] so a failed delete isn't silently
     * swallowed and the user gets feedback instead of a confirm tap that appears to do nothing.
     */
    public fun deleteBook() {
        viewModelScope.launch {
            when (val result = bookRepository.deleteBook(bookId)) {
                is Resource.Success -> Unit
                is Resource.Error -> _local.update { it.copy(errorMessage = result.message) }
            }
        }
    }
}
