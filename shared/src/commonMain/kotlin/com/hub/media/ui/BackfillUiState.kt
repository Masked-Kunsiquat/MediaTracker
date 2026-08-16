package com.hub.media.ui

import com.hub.media.features.books.domain.BulkBackfillProgress

/**
 * UI state for the Settings screen's bulk cover/author backfill action (ROADMAP Task 14 Phase A).
 * Deliberately not the `Idle`/`Loading`/`Success`/`Error` shape [ExportUiState]/[ImportUiState] use:
 * this action is resumable and reports honest partial progress rather than a one-shot pass/fail, so
 * "loading" and "here's the result so far" are the same observable moment, not two states in
 * sequence.
 */
public sealed class BackfillUiState {
    /**
     * Nothing to resume and no run is in flight -- either a backfill has never been started,
     * [BackfillViewModel]'s init check found no persisted resume state (nothing left to do from a
     * prior run, or one never ran), or a run that just started was cancelled or hit an unexpected
     * failure before it checkpointed even a single book (so there's no [BulkBackfillProgress] snapshot
     * worth surfacing as [Stopped]).
     */
    public data object Idle : BackfillUiState()

    /**
     * A backfill is actively running. [progress] is `null` immediately after [BackfillViewModel.start]
     * is called and before the first book of this run has been checkpointed; every progress
     * callback thereafter replaces it with the latest snapshot.
     */
    public data class Running(
        val progress: BulkBackfillProgress?,
    ) : BackfillUiState()

    /**
     * A run isn't currently in flight, but [progress] describes where things stand -- either it
     * finished a run just now ([BulkBackfillProgress.isComplete] or [BulkBackfillProgress.isPaused]
     * tells the app layer which), or [BackfillViewModel]'s init check found resumable state left
     * over from a previous session (a run interrupted by cancellation or process death). Either
     * way, [BackfillViewModel.start] resumes from exactly this point if [BulkBackfillProgress.remaining]
     * is non-zero.
     */
    public data class Stopped(
        val progress: BulkBackfillProgress,
    ) : BackfillUiState()

    /**
     * [BackfillViewModel.start]'s coroutine stopped because of an unexpected, non-cancellation
     * failure (e.g. a DB error mid-run) rather than the user cancelling or the run finishing/pausing
     * normally (PR review round 2: a failure settling on [Stopped] made it indistinguishable from a
     * clean stop, silently telling the user nothing went wrong when their progress may not have been
     * saved). Deliberately a distinct case from [Stopped], not folded into it, so the Settings screen
     * can render a visibly different signal for the two. [progress] carries the last snapshot this
     * run actually reported -- same convention as [Stopped.progress] -- or `null` if nothing was
     * checkpointed before the failure.
     */
    public data class Failed(
        val progress: BulkBackfillProgress?,
    ) : BackfillUiState()
}
