package com.hub.media.ui

import com.hub.media.features.media.domain.BackfillRun

/**
 * UI state for a Settings-screen backfill action — the book pass (ROADMAP Task 14 Phase A) and the
 * films-and-shows pass (#140) alike.
 *
 * Deliberately not the `Idle`/`Loading`/`Success`/`Error` shape [ExportUiState]/[ImportUiState] use:
 * these actions are resumable and report honest partial progress rather than a one-shot pass/fail, so
 * "loading" and "here's the result so far" are the same observable moment, not two states in
 * sequence.
 *
 * ### Why it is generic
 * The two passes' *lifecycles* are identical — start, report, stop, resume, fail — while their
 * *progress payloads* are not: one can be paused by an exhausted quota and counts books with no ISBN,
 * the other can be blocked by a rejected credential and counts titles never added from TMDB. Making
 * the payload a type parameter keeps one copy of the four states without pretending the two passes
 * report the same facts. See [BackfillRun] on why that mattered enough to do.
 *
 * @param P The pass's own progress snapshot — [com.hub.media.features.books.domain.BulkBackfillProgress]
 *   or [com.hub.media.features.media.domain.TmdbBackfillProgress].
 */
public sealed class BackfillUiState<out P> {
    /**
     * Nothing to resume and no run is in flight -- either a backfill has never been started,
     * [BackfillViewModel]'s init check found no persisted resume state (nothing left to do from a
     * prior run, or one never ran), or a run that just started was cancelled or hit an unexpected
     * failure before it checkpointed even a single item (so there's no progress snapshot worth
     * surfacing as [Stopped]).
     *
     * Carries no payload, so it is shared across every [P] rather than instantiated per pass.
     */
    public data object Idle : BackfillUiState<Nothing>()

    /**
     * A backfill is actively running. [progress] is `null` immediately after [BackfillViewModel.start]
     * is called and before the first item of this run has been checkpointed; every progress
     * callback thereafter replaces it with the latest snapshot.
     */
    public data class Running<P>(
        val progress: P?,
    ) : BackfillUiState<P>()

    /**
     * A run isn't currently in flight, but [progress] describes where things stand -- either it
     * finished a run just now, or [BackfillViewModel]'s init check found resumable state left over
     * from a previous session (a run interrupted by cancellation or process death). Either way,
     * [BackfillViewModel.start] resumes from exactly this point if anything remains.
     *
     * Which of those it is, and whether the run stopped for a reason the user has to act on, is the
     * payload's business to say -- `BulkBackfillProgress.isPaused` and
     * `TmdbBackfillProgress.isBlocked` are not the same question and are not asked here.
     */
    public data class Stopped<P>(
        val progress: P,
    ) : BackfillUiState<P>()

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
    public data class Failed<P>(
        val progress: P?,
    ) : BackfillUiState<P>()
}
