package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.export.MAX_IMPORT_FILE_BYTES
import com.github.maskedkunisquat.mediatracker.export.readAtMost
import com.github.maskedkunisquat.mediatracker.export.readCsvFromUri
import com.hub.media.features.portability.domain.DuplicatePolicy
import com.hub.media.features.portability.domain.ImportRejection
import com.hub.media.features.portability.domain.ImportSummary
import com.hub.media.ui.ImportUiState
import com.hub.media.ui.ImportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The two CSV import flows (this app's own format, and Goodreads) -- their SAF pickers,
 * pending state, and shared outcome dialog -- lifted out of the route (#81) as the first cut of
 * its remaining SAF chains.
 */

/**
 * The one-dialog import (#196): picks an export `.zip`, or a lone `library_export.csv`, reads it off
 * the main thread, and hands the bytes to [ImportViewModel.importArchive]. Replaced a chain of four
 * open dialogs, one per CSV. The returned lambda opens the dialog.
 *
 * @param restoreSettings The "Also restore settings" choice, read when the file comes back.
 */
@Composable
internal fun rememberCsvImportLauncher(
    importViewModel: ImportViewModel,
    duplicatePolicy: DuplicatePolicy,
    restoreSettings: Boolean,
    snackbarHostState: SnackbarHostState,
): () -> Unit {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val importCancelledMessage = stringResource(R.string.import_cancelled_message)
    val importFailureMessage = stringResource(R.string.import_failure_message)

    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) {
                coroutineScope.launch { snackbarHostState.showSnackbar(importCancelledMessage) }
                return@rememberLauncherForActivityResult
            }
            coroutineScope.launch {
                // Off the main thread: an export of a large library is a few megabytes. Bounded, so a
                // huge file picked by mistake is refused rather than read whole into memory.
                val bytes =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver
                                .openInputStream(
                                    uri,
                                )?.use { readAtMost(it, MAX_IMPORT_FILE_BYTES) }
                        }.getOrNull()
                    }
                if (bytes == null) {
                    snackbarHostState.showSnackbar(importFailureMessage)
                    return@launch
                }
                importViewModel.importArchive(bytes, duplicatePolicy, restoreSettings)
            }
        }

    return { importLauncher.launch(arrayOf("application/zip", "text/*", "application/octet-stream")) }
}

/**
 * The single-file Goodreads import picker (ROADMAP Task 8 Phase D) -- see the `goodreadsDuplicatePolicy`
 * state declared alongside its caller for why this is a separate action from [rememberCsvImportLauncher].
 * The returned lambda launches the picker.
 */
@Composable
internal fun rememberGoodreadsImportLauncher(
    importViewModel: ImportViewModel,
    goodreadsDuplicatePolicy: DuplicatePolicy,
    snackbarHostState: SnackbarHostState,
): () -> Unit {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val importCancelledMessage = stringResource(R.string.import_cancelled_message)
    val importFailureMessage = stringResource(R.string.import_failure_message)

    val goodreadsImportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) {
                coroutineScope.launch { snackbarHostState.showSnackbar(importCancelledMessage) }
                return@rememberLauncherForActivityResult
            }
            // Off the main thread -- see the CSV import launchers above.
            coroutineScope.launch {
                val content = withContext(Dispatchers.IO) { readCsvFromUri(context, uri) }
                if (content == null) {
                    snackbarHostState.showSnackbar(importFailureMessage)
                    return@launch
                }
                importViewModel.importGoodreads(content, goodreadsDuplicatePolicy)
            }
        }

    return { goodreadsImportLauncher.launch(arrayOf("text/*")) }
}

/**
 * Renders the outcome of either import chain: an error [SnackbarHostState.showSnackbar], or --
 * on success -- the [ImportSummaryDialog]. [onStartBackfill] and [onStartTmdbBackfill] are what that
 * dialog's optional backfill actions call: the books pass and the films-and-shows pass.
 */
@Composable
internal fun ImportOutcome(
    importUiState: ImportUiState,
    importViewModel: ImportViewModel,
    snackbarHostState: SnackbarHostState,
    onStartBackfill: () -> Unit,
    onStartTmdbBackfill: () -> Unit,
) {
    // Import's Success state is rendered as a summary AlertDialog (below) rather than a Snackbar --
    // a Snackbar's single line can't show per-row rejection reasons, and this phase's brief is
    // explicit that a bare "done" is not an acceptable result for an operation that may have
    // silently skipped rows otherwise. Error is still a Snackbar, matching export's convention,
    // since a refused-outright import has no partial summary to show.
    LaunchedEffect(importUiState) {
        val state = importUiState
        if (state is ImportUiState.Error) {
            snackbarHostState.showSnackbar(state.message)
            importViewModel.reset()
        }
    }

    (importUiState as? ImportUiState.Success)?.let { state ->
        ImportSummaryDialog(
            summary = state.summary,
            onDismiss = importViewModel::reset,
            onBackfillBooksClick = {
                importViewModel.reset()
                onStartBackfill()
            },
            onBackfillFilmsAndShowsClick = {
                importViewModel.reset()
                onStartTmdbBackfill()
            },
        )
    }
}

/**
 * Full import-result summary (ROADMAP Task 8 Phase B): per-file counts for every duplicate-policy
 * outcome, plus every rejected row's reason (scrollable, since a large messy import could reject
 * many rows) -- see [ImportSummary]'s KDoc for why a bare "done" isn't acceptable here.
 *
 * Each backfill is offered beside the dismiss button only when this import gave it something to do.
 * The moment a coverless import just landed (a Goodreads import above all) is exactly when the need
 * is obvious (ROADMAP Task 14 Phase A). Neither is shown for an import that added nothing: a pure
 * duplicate-skip pass has no new gaps to fill.
 *
 * @param onBackfillBooksClick Starts the cover/author backfill and dismisses this dialog. Offered when
 *   the import added **books** ([ImportSummary.booksImported] > 0), not any item: that pass seeds
 *   itself from `BookRepository.getAllBooksWithDetails()`, so after a films-and-shows-only import it
 *   could only log "nothing pending".
 * @param onBackfillFilmsAndShowsClick Starts the films-and-shows pass (#140) and dismisses this dialog.
 *   Offered when the import added a film or show (#149): every library item that is not a book.
 *   A mixed import offers both, which is why each button names its pass.
 */
@Composable
internal fun ImportSummaryDialog(
    summary: ImportSummary,
    onDismiss: () -> Unit,
    onBackfillBooksClick: () -> Unit,
    onBackfillFilmsAndShowsClick: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.ok_button)) }
        },
        dismissButton = {
            // Every library item that is not a book is a film or a show. The buttons are emitted
            // directly, not in a Row, so the dialog's own flow row can wrap them at a large font.
            val filmsAndShowsImported = summary.itemsImported - summary.booksImported
            if (summary.booksImported > 0) {
                TextButton(onClick = onBackfillBooksClick) {
                    Text(stringResource(R.string.import_summary_backfill_books_button))
                }
            }
            if (filmsAndShowsImported > 0) {
                TextButton(onClick = onBackfillFilmsAndShowsClick) {
                    Text(stringResource(R.string.import_summary_backfill_films_shows_button))
                }
            }
        },
        title = { Text(stringResource(R.string.import_summary_title)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(
                        R.string.import_summary_items_line,
                        summary.itemsImported,
                        summary.itemsSkipped,
                        summary.itemsReplaced,
                        summary.itemsMerged,
                    ),
                )
                Text(
                    stringResource(
                        R.string.import_summary_sessions_line,
                        summary.sessionsImported,
                        summary.sessionsSkipped,
                        summary.sessionsReplaced,
                        summary.sessionsMerged,
                    ),
                )
                // Only when an import restored settings (#196): the user asked for it, so say it happened.
                if (summary.settingsRestored > 0) {
                    Text(
                        pluralStringResource(
                            R.plurals.import_summary_settings_restored,
                            summary.settingsRestored,
                            summary.settingsRestored,
                        ),
                    )
                }
                // Always rendered, including when every count is zero -- that is the entire point
                // of the line (Issue #106). An import that silently dropped every episode used to
                // look identical to one that had none to carry; "Episodes: 0 imported" is what
                // makes the difference visible.
                Text(
                    stringResource(
                        R.string.import_summary_episodes_line,
                        summary.episodesImported,
                        summary.episodesSkipped,
                        summary.episodesReplaced,
                        summary.episodesMerged,
                    ),
                )
                // Advisory notes (ROADMAP Task 8 Phase D) -- e.g. the Goodreads importer's "these
                // columns weren't imported, keep the file to backfill later" notice. Rendered
                // in full, the same "no silent partial result" rule ImportSummary's KDoc applies
                // to rejections -- never truncated or summarized down to a count.
                if (summary.notes.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.import_summary_notes_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    summary.notes.forEach { note ->
                        Text(text = note, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (summary.rejections.isEmpty()) {
                    Text(stringResource(R.string.import_summary_no_rejections))
                } else {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.import_summary_rejections_title, summary.rejections.size),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    summary.rejections.forEach { rejection: ImportRejection ->
                        Text(
                            text =
                                stringResource(
                                    R.string.import_summary_rejection_line,
                                    rejection.source.name,
                                    rejection.rowNumber,
                                    rejection.reason,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
    )
}
