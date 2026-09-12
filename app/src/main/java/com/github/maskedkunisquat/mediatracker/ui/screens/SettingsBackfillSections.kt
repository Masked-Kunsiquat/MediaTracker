package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.features.books.domain.BulkBackfillProgress
import com.hub.media.features.media.domain.TmdbBackfillProgress
import com.hub.media.ui.BackfillUiState
import kotlin.math.ceil
import kotlin.time.DurationUnit

/*
 * The two backfill sections of the Settings screen, lifted out of `SettingsScreen.kt` (#81).
 *
 * They were the first cut because they are self-contained: nothing else on the screen has to move
 * for them to leave, and they already split along the Books / Films & TV line #126 settled Settings
 * should be organised by. The file owns the whole vertical slice -- the two section wrappers, the
 * one generic row they share, and the per-pass conversions feeding it -- so a change to a pass's
 * reporting is a change in one file rather than a search through a screen.
 *
 * Only the two `*BackfillSection` composables are visible outside this file; everything below them
 * is an implementation detail of how a backfill row draws itself.
 */

/**
 * The books pass (ROADMAP Task 14 Phase A) as its own settings section.
 *
 * Its own section, not another row in the "Data" card above it -- this is a long-running, resumable,
 * cancellable action (that phase's brief: "not a single tap"), unlike the export/import rows, and
 * reads better grouped with the repair-your-library concern it serves than folded into the
 * import/export card.
 *
 * @param uiState Current state of the books pass.
 * @param onStartClick Start/resume, wired to [com.hub.media.ui.BackfillViewModel.start].
 * @param onCancelClick Cancel, wired to [com.hub.media.ui.BackfillViewModel.cancel].
 */
@Composable
internal fun BookBackfillSection(
    uiState: BackfillUiState<BulkBackfillProgress>,
    onStartClick: () -> Unit,
    onCancelClick: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_section_backfill)) {
        BackfillSetting(
            description = stringResource(R.string.settings_backfill_description),
            uiState = uiState,
            describe = { bookBackfillRowContent(it) },
            onStartClick = onStartClick,
            onCancelClick = onCancelClick,
        )
    }
}

/**
 * The films-and-shows pass (#140) as its own settings section, plus the way into reconciliation.
 *
 * Its own section rather than a second row inside the books one, because #126 decided Settings is
 * sectioned by domain -- Books / Films & TV / Data / Diagnostics. This is the Films & TV half of the
 * same repair concern, and #140 chose two actions over one merged pass on exactly that basis: a
 * control spanning both domains would have had to live in neither.
 *
 * The two passes do not share a progress type either; `BackfillRowContent` below says why, and is
 * what lets them share a row regardless.
 *
 * @param uiState Current state of the films-and-shows pass.
 * @param onStartClick Start/resume for that pass.
 * @param onCancelClick Cancel for that pass.
 * @param mismatchedShows How many shows have unreconciled episode-count findings (#123). Read from
 *   the stored findings rather than from the run's progress: a completed run clears its resume
 *   state, so `peekProgress()` is null by the time anyone opens this screen -- the count has to come
 *   from where the findings actually live, or it would show only in the seconds after a run.
 * @param onReviewClick Opens the reconciliation screen.
 */
@Composable
internal fun FilmsAndTvBackfillSection(
    uiState: BackfillUiState<TmdbBackfillProgress>,
    onStartClick: () -> Unit,
    onCancelClick: () -> Unit,
    mismatchedShows: Int,
    onReviewClick: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_section_tmdb_backfill)) {
        BackfillSetting(
            description = stringResource(R.string.settings_tmdb_backfill_description),
            uiState = uiState,
            describe = { tmdbBackfillRowContent(it) },
            onStartClick = onStartClick,
            onCancelClick = onCancelClick,
        )
        if (mismatchedShows > 0) {
            MismatchReviewEntry(
                shows = mismatchedShows,
                onReviewClick = onReviewClick,
            )
        }
    }
}

/**
 * The bulk cover/author backfill setting row (ROADMAP Task 14 Phase A). Unlike every other row on
 * this screen, its body branches on a full sealed [BackfillUiState] rather than a bare in-progress
 * boolean -- a plain "loading" flag can't express "312 of 480 done, paused until the quota resets"
 * (this phase's explicit brief for honest partial progress), a resumable state left over from a
 * previous session, or the distinction between "finished cleanly" and "paused by the rate limit."
 */
@Composable
private fun <P : Any> BackfillSetting(
    description: String,
    uiState: BackfillUiState<P>,
    describe: @Composable (P) -> BackfillRowContent,
    onStartClick: () -> Unit,
    onCancelClick: () -> Unit,
) {
    Column {
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        when (uiState) {
            BackfillUiState.Idle -> {
                Button(onClick = onStartClick) {
                    Text(stringResource(R.string.settings_backfill_start_button))
                }
            }
            is BackfillUiState.Running -> {
                BackfillRunningContent(content = uiState.progress?.let { describe(it) })
                OutlinedButton(onClick = onCancelClick, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.settings_backfill_cancel_button))
                }
            }
            is BackfillUiState.Stopped -> {
                val content = describe(uiState.progress)
                BackfillStoppedContent(content = content)
                Button(onClick = onStartClick, modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(
                            if (content.remaining > 0) {
                                R.string.settings_backfill_resume_button
                            } else {
                                R.string.settings_backfill_start_button
                            },
                        ),
                    )
                }
            }
            is BackfillUiState.Failed -> {
                val content = uiState.progress?.let { describe(it) }
                BackfillFailedContent(content = content)
                Button(onClick = onStartClick, modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(
                            if ((content?.remaining ?: 0) > 0) {
                                R.string.settings_backfill_resume_button
                            } else {
                                R.string.settings_backfill_start_button
                            },
                        ),
                    )
                }
            }
        }
    }
}

/**
 * The way into the reconciliation screen (#123), shown only when a run has found something.
 *
 * Deliberately below the backfill controls rather than beside them: the disagreements are a *result*
 * of running the pass, and putting a second button next to Start would make them look like a second
 * thing to run.
 */
@Composable
private fun MismatchReviewEntry(
    shows: Int,
    onReviewClick: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            text = pluralStringResource(R.plurals.settings_tmdb_backfill_mismatch_format, shows, shows),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onReviewClick, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.settings_tmdb_backfill_review_button))
        }
    }
}

/**
 * One backfill pass's progress, reduced to the handful of things this row actually draws.
 *
 * ### Why the row does not read a progress type directly
 * There are two passes (#140) and their snapshots deliberately do not carry the same facts: the book
 * pass can be paused by an exhausted Open Library quota and counts books with no ISBN, while the TMDB
 * pass cannot be paused at all — a rate is waited out — and counts titles never added from TMDB.
 * Neither type can honestly be given the other's fields.
 *
 * What is genuinely common is what a *row* shows: a fraction, an optional status sentence, and some
 * detail lines. Each pass converts itself into this once, and the four state bodies below are written
 * once against it — the UI-side application of the same "two actions, one set of machinery" rule
 * [com.hub.media.ui.BackfillViewModel] follows.
 *
 * @property statusMessage The sentence above the fraction — paused, blocked, or complete — or `null`
 *   when there is nothing to say.
 * @property statusIsError Whether [statusMessage] describes something the user has to act on. A
 *   finished run and a run stopped by a rejected credential are both "stopped", and must not look it.
 * @property detailLines The trailing dimmed lines, in order. Empty when nothing is worth adding.
 */
private data class BackfillRowContent(
    val processed: Int,
    val totalCandidates: Int,
    val remaining: Int,
    val statusMessage: String?,
    val statusIsError: Boolean,
    val detailLines: List<String>,
)

/** The book pass's progress ([BulkBackfillProgress]) as a [BackfillRowContent]. */
@Composable
private fun bookBackfillRowContent(progress: BulkBackfillProgress): BackfillRowContent {
    val paused =
        progress.retryAfter?.let { retryAfter ->
            // Round up, floored at one minute, so a sub-minute wait (e.g. 30s) never renders
            // as the misleading "about 0 min" -- any nonzero wait is at least "about 1 min".
            val minutes = ceil(retryAfter.toDouble(DurationUnit.MINUTES)).toInt().coerceAtLeast(1)
            pluralStringResource(R.plurals.settings_backfill_paused_with_wait_format, minutes, minutes)
        } ?: stringResource(R.string.settings_backfill_paused_message)
    val complete = stringResource(R.string.settings_backfill_complete_message)
    val summary =
        stringResource(
            R.string.settings_backfill_summary_format,
            progress.updated,
            progress.noProviderData,
        )
    val noIsbn = stringResource(R.string.settings_backfill_no_isbn_format, progress.noIsbnSkipped)

    return BackfillRowContent(
        processed = progress.processed,
        totalCandidates = progress.totalCandidates,
        remaining = progress.remaining,
        statusMessage =
            when {
                progress.isPaused -> paused
                progress.isComplete -> complete
                else -> null
            },
        statusIsError = progress.isPaused,
        detailLines =
            buildList {
                if (progress.processed > 0) add(summary)
                if (progress.noIsbnSkipped > 0) add(noIsbn)
            },
    )
}

/** The films-and-shows pass's progress ([TmdbBackfillProgress]) as a [BackfillRowContent]. */
@Composable
private fun tmdbBackfillRowContent(progress: TmdbBackfillProgress): BackfillRowContent {
    val complete = stringResource(R.string.settings_backfill_complete_message)
    val summary =
        stringResource(
            R.string.settings_tmdb_backfill_summary_format,
            progress.updated,
            progress.nothingToFill,
        )
    val noId = stringResource(R.string.settings_tmdb_backfill_no_id_format, progress.noTmdbIdSkipped)

    return BackfillRowContent(
        processed = progress.processed,
        totalCandidates = progress.totalCandidates,
        remaining = progress.remaining,
        // TMDB's own sentence, not one of ours. Both of the messages it produces here already name
        // the remedy ("Add one in Settings", "Check the key or token saved in Settings"), and giving
        // the same advice a second author is how the two drift apart.
        statusMessage =
            when {
                progress.isBlocked -> progress.blockedMessage
                progress.isComplete -> complete
                else -> null
            },
        statusIsError = progress.isBlocked,
        detailLines =
            buildList {
                if (progress.processed > 0) add(summary)
                if (progress.noTmdbIdSkipped > 0) add(noId)
            },
    )
}

/** [BackfillUiState.Running]'s body: a progress bar once the first item has been checkpointed. */
@Composable
private fun BackfillRunningContent(content: BackfillRowContent?) {
    if (content != null && content.totalCandidates > 0) {
        LinearProgressIndicator(
            progress = { content.processed.toFloat() / content.totalCandidates },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
        )
        Text(
            text =
                stringResource(
                    R.string.settings_backfill_progress_format,
                    content.processed,
                    content.totalCandidates,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        // Nothing checkpointed yet (fresh start, still scanning the library for candidates), or
        // there were zero candidates to begin with -- an indeterminate bar reads better than a
        // 0/0 fraction either way.
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/**
 * [BackfillUiState.Stopped]'s body: whichever status sentence applies (paused by a quota, blocked on
 * a credential, or finished), plus the running totals so far.
 */
@Composable
private fun BackfillStoppedContent(content: BackfillRowContent) {
    content.statusMessage?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (content.statusIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    Color.Unspecified
                },
        )
    }
    if (content.totalCandidates > 0) {
        Text(
            text =
                stringResource(
                    R.string.settings_backfill_progress_format,
                    content.processed,
                    content.totalCandidates,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    content.detailLines.forEach { line ->
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * [BackfillUiState.Failed]'s body: an explicit failure signal, distinct from
 * [BackfillStoppedContent]'s "paused"/"complete" messaging, so the user isn't left thinking a
 * genuine mid-run failure was just a clean stop. [content] is `null` when nothing was
 * checkpointed before the failure, in which case there is no partial-progress line to show.
 */
@Composable
private fun BackfillFailedContent(content: BackfillRowContent?) {
    Text(
        text = stringResource(R.string.settings_backfill_failed_message),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
    if (content != null && content.totalCandidates > 0) {
        Text(
            text =
                stringResource(
                    R.string.settings_backfill_progress_format,
                    content.processed,
                    content.totalCandidates,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
