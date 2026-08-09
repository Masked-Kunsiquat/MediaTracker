package com.hub.media.ui

import com.hub.media.core.storage.LogEntry

/**
 * Ordering of [LogViewerUiState.entries], expressed as one named constant so it can be changed in
 * exactly one place (ROADMAP Task 15 Phase B2 requires precisely this).
 *
 * Oldest-first, newest at the bottom -- the terminal convention (`tail -f`, a log file opened in an
 * editor), read top-to-bottom chronologically. This is what makes [LogViewerUiState.newEntryBoundary]
 * read correctly: in ascending order the divider sits *above* the fresh entries ("everything below
 * this line is new"), whereas newest-first would put the marker below the entries it marks. It is
 * also why the UI parks at the bottom on open, the way a terminal does.
 *
 * **Deliberately not a user setting**, and the ROADMAP records the reasoning: the cost of changing
 * this later is social (muscle memory), not technical, so a setting would not solve the real
 * problem, it would only push the decision onto the user -- while doubling the tested surface,
 * since both the divider placement and the auto-scroll direction flip with it.
 */
public const val LOG_ENTRIES_OLDEST_FIRST: Boolean = true

/**
 * UI state for the in-app log viewer (ROADMAP Task 15 Phase B2).
 *
 * ### A snapshot, not a live tail
 * [entries] is what the store held at the moment the screen was opened or [LogViewerViewModel.refresh]
 * was last called -- it does **not** update on its own while the screen is open, even though logging
 * continues in the background. That is the central design decision of this screen and it is not an
 * omission: a live-tailing list actively fights text selection, because new entries reflow the
 * content mid-drag. Since the whole point of this screen is genuinely selectable text
 * (`SelectionContainer`) rather than a copy-everything button, auto-update and usable selection are
 * mutually exclusive, and freezing the view is what makes selection work.
 *
 * There is deliberately **no "3 new entries" badge** either: knowing the pending count requires the
 * live observation the snapshot model exists to avoid. A plain Refresh that reveals what is new is
 * simpler and consistent with the rest of the model.
 *
 * @property entries The snapshot, in [LOG_ENTRIES_OLDEST_FIRST] order.
 * @property newEntryBoundary Sequence number marking where the entries added by the most recent
 *   [LogViewerViewModel.refresh] begin -- see [firstNewEntryIndex]. `null` on first open, when
 *   nothing is new yet.
 * @property isLoading True while a load or refresh is in flight.
 */
public data class LogViewerUiState(
    val entries: List<LogEntry> = emptyList(),
    val newEntryBoundary: Long? = null,
    val isLoading: Boolean = false,
) {
    /**
     * Index of the first entry newer than [newEntryBoundary], i.e. where the UI draws its "new
     * entries below this line" divider -- or `null` if there is nothing to mark (first open, or a
     * refresh that brought back nothing new).
     *
     * ### Why this is derived from a sequence number rather than stored as an index
     * A stored index would be wrong the moment the underlying list changed shape, and it changes
     * shape routinely: the store rotates old entries off disk, so a refresh can return a window
     * that starts *later* than the previous one, shifting every position. Comparing
     * [LogEntry.seq] instead is immune to that, because it never depends on list position or count
     * -- which is exactly why the ROADMAP specifies a monotonic per-entry sequence here and rules
     * out timestamps: two entries can share a millisecond, and the wall clock can jump backwards
     * (NTP sync, or the user changing the device time), either of which would scramble a
     * timestamp-based boundary.
     */
    val firstNewEntryIndex: Int?
        get() {
            val boundary = newEntryBoundary ?: return null
            val index = entries.indexOfFirst { it.seq > boundary }
            return if (index >= 0) index else null
        }

    /** True when a completed load produced nothing -- the "no logs recorded yet" empty state. */
    val isEmpty: Boolean get() = entries.isEmpty() && !isLoading
}
