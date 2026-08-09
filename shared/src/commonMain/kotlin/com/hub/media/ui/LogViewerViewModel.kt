package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.storage.LogEntry
import com.hub.media.core.storage.LogFileStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How many recent entries the bounded viewer window holds -- see [LogViewerViewModel]'s KDoc. */
internal const val LOG_VIEWER_ENTRY_LIMIT: Int = 500

/**
 * Drives the in-app log viewer (ROADMAP Task 15 Phase B2), reading the Phase B1 [LogFileStore].
 *
 * ### No reactive `Flow` from the store, deliberately
 * Unlike every other ViewModel here ([LibraryViewModel], [StatsViewModel], [SettingsViewModel]),
 * this one does **not** build its state from a repository `Flow`. It cannot: the snapshot model
 * (see [LogViewerUiState]) means the view must *stop* reflecting the store until the user asks for
 * more, so a live stream would be actively wrong, not merely unnecessary. A suspend "read current
 * entries" call is enough and is less machinery -- which is why [LogFileStore] exposes
 * [LogFileStore.readRecent] rather than a `Flow` in the first place. [uiState] is therefore a plain
 * [MutableStateFlow] this class writes to, not a `stateIn` projection.
 *
 * ### A bounded window, plus a separate path for everything else
 * [load]/[refresh] read at most [LOG_VIEWER_ENTRY_LIMIT] recent entries. The bound is a Compose
 * constraint, not a storage one: selection across a `LazyColumn` breaks as items recycle, so the
 * screen renders a scrollable `Column` inside a `SelectionContainer`, which cannot absorb an
 * unbounded list. Everything beyond the window is reachable via [readFullLogForExport].
 *
 * @param logFileStore The Phase B1 store, from [AppContainer.logFileStore].
 */
public class LogViewerViewModel(
    private val logFileStore: LogFileStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LogViewerUiState(isLoading = true))
    public val uiState: StateFlow<LogViewerUiState> = _uiState.asStateFlow()

    // One read at a time, cancel-and-replace. [load] fires from init and [refresh] from a button,
    // so a user tapping Refresh before the initial load settles would otherwise leave two
    // coroutines racing to overwrite _uiState -- and the loser could publish last, restoring a
    // stale snapshot together with a boundary computed against a list that is no longer shown.
    private var readJob: Job? = null

    init {
        load()
    }

    /**
     * Loads the initial snapshot. Leaves [LogViewerUiState.newEntryBoundary] `null`: on first open
     * nothing is new, so there is nothing to divide.
     */
    public fun load() {
        readJob?.cancel()
        readJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val entries = readWindow()
            _uiState.value = LogViewerUiState(
                entries = entries,
                newEntryBoundary = null,
                isLoading = false,
            )
        }
    }

    /**
     * Pulls in whatever has accumulated since the current snapshot, marking where it begins.
     *
     * ### The boundary is captured *before* reloading, and that ordering is the whole trick
     * [LogViewerUiState.newEntryBoundary] is set from the highest [LogEntry.seq] in the snapshot
     * being replaced, computed before the new read. Doing it the other way round -- reading first,
     * then deriving a boundary from the result -- would have nothing left to compare against,
     * because the new entries would already be indistinguishable from the old ones in the same
     * list.
     *
     * Because the boundary is a sequence number rather than a position or a count, this stays
     * correct across repeated refreshes and across the store rotating old entries off disk between
     * two refreshes (see [LogViewerUiState.firstNewEntryIndex]).
     *
     * An empty current snapshot yields a `null` boundary rather than a fabricated one: with nothing
     * previously on screen, every entry that arrives is the first thing the user sees, not an
     * update to something they were already looking at, so there is no meaningful "new below here"
     * line to draw.
     */
    public fun refresh() {
        readJob?.cancel()
        readJob = viewModelScope.launch {
            val previous = _uiState.value.entries
            val boundary = previous.maxOfOrNull { it.seq }
            _uiState.update { it.copy(isLoading = true) }
            val entries = readWindow()
            _uiState.value = LogViewerUiState(
                entries = entries,
                newEntryBoundary = boundary,
                isLoading = false,
            )
        }
    }

    /**
     * Every retained entry, rendered as the plain text the "export full log" action writes out --
     * not just the bounded window [uiState] shows.
     *
     * Returns a [String] rather than touching the filesystem, mirroring how [ExportDataUseCase][
     * com.hub.media.features.portability.domain.ExportDataUseCase] hands back CSV documents for the
     * app module to write via SAF. `shared/` stays KMP-clean and free of platform file pickers;
     * the app module owns where the bytes land.
     */
    public suspend fun readFullLogForExport(): String =
        logFileStore.readAll().joinToString(separator = "\n") { entry ->
            "${entry.timestampMillis} ${entry.level.name} ${entry.tag} ${entry.message}"
        }

    private suspend fun readWindow(): List<LogEntry> {
        val recent = logFileStore.readRecent(LOG_VIEWER_ENTRY_LIMIT)
        // readRecent already returns ascending-by-seq; reversing here (rather than at the call
        // sites or in the UI) keeps LOG_ENTRIES_OLDEST_FIRST the single place ordering is decided.
        return if (LOG_ENTRIES_OLDEST_FIRST) recent else recent.reversed()
    }
}
