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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
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
 * The two CSV import flows (this app's own format, and Goodreads) -- their SAF picker chains,
 * pending state, and shared outcome dialog -- lifted out of the route (#81) as the first cut of
 * its remaining SAF chains.
 */

/**
 * The three-picker CSV import chain (library, then reading logs, then episodes): owns the pending
 * state handed between pickers and registers all three launchers. The returned lambda launches the
 * first (library) picker.
 */
@Composable
internal fun rememberCsvImportLauncher(
    importViewModel: ImportViewModel,
    duplicatePolicy: DuplicatePolicy,
    snackbarHostState: SnackbarHostState,
): () -> Unit {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val importCancelledMessage = stringResource(R.string.import_cancelled_message)
    val importFailureMessage = stringResource(R.string.import_failure_message)

    // Holds each earlier file's text between the three sequential SAF "open document" picks below,
    // mirroring pendingBundle's export-side role: the reading-logs and episodes files are both
    // optional, so a later picker's Cancel still runs the import with whatever was gathered so far,
    // rather than the first-picker Cancel semantics below (which abort the whole import request).
    //
    // Three picks now rather than two (Issue #106): the export side has written three files since
    // Task 13 Phase C, and the import side asked for two, which is the shape the episodes half of
    // that bug took at the UI layer. The order matches the export chain's.
    var pendingLibraryCsvForImport by remember { mutableStateOf<String?>(null) }
    var pendingReadingLogsCsvForImport by remember { mutableStateOf<String?>(null) }

    val episodesImportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            val libraryCsv = pendingLibraryCsvForImport
            val readingLogsCsv = pendingReadingLogsCsvForImport
            pendingLibraryCsvForImport = null
            pendingReadingLogsCsvForImport = null
            // Off the main thread -- see the launchers below; an episodes export of a large TV
            // library is easily the biggest of the three files.
            coroutineScope.launch {
                val episodesCsv = uri?.let { withContext(Dispatchers.IO) { readCsvFromUri(context, it) } }
                // Same split as readingLogsImportLauncher: a cancelled picker is a legitimate
                // "no episodes file" import, a failed read of a file the user *did* pick is not.
                if (uri != null && episodesCsv == null) {
                    snackbarHostState.showSnackbar(importFailureMessage)
                    return@launch
                }
                importViewModel.importData(libraryCsv, readingLogsCsv, episodesCsv, duplicatePolicy)
            }
        }

    val readingLogsImportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            // Off the main thread: reading a whole document via SAF is blocking I/O that runs
            // straight inside this launcher callback, which is itself dispatched on the main thread --
            // an unbounded read here (a large reading-logs export) would otherwise ANR the app.
            coroutineScope.launch {
                val readingLogsCsv = uri?.let { withContext(Dispatchers.IO) { readCsvFromUri(context, it) } }
                // A null uri means the user cancelled this second, optional picker -- that's a
                // legitimate "no reading logs" import (see pendingLibraryCsvForImport's comment
                // above). A null readingLogsCsv from a uri the user *did* pick means the read itself
                // failed -- matching libraryImportLauncher's own null-content handling below, report
                // it and stop. Stopping here abandons the whole chain, including the episodes pick,
                // rather than importing part of what the user selected.
                if (uri != null && readingLogsCsv == null) {
                    pendingLibraryCsvForImport = null
                    snackbarHostState.showSnackbar(importFailureMessage)
                    return@launch
                }
                pendingReadingLogsCsvForImport = readingLogsCsv
                episodesImportLauncher.launch(arrayOf("text/*"))
            }
        }

    val libraryImportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) {
                coroutineScope.launch { snackbarHostState.showSnackbar(importCancelledMessage) }
                return@rememberLauncherForActivityResult
            }
            // Off the main thread -- see readingLogsImportLauncher above; a large library export is
            // the more likely of the three files to be big enough to matter.
            coroutineScope.launch {
                val content = withContext(Dispatchers.IO) { readCsvFromUri(context, uri) }
                if (content == null) {
                    snackbarHostState.showSnackbar(importFailureMessage)
                    return@launch
                }
                pendingLibraryCsvForImport = content
                readingLogsImportLauncher.launch(arrayOf("text/*"))
            }
        }

    return { libraryImportLauncher.launch(arrayOf("text/*")) }
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
 * on success -- the [ImportSummaryDialog]. [onStartBackfill] is what that dialog's optional
 * "start backfill" action calls.
 */
@Composable
internal fun ImportOutcome(
    importUiState: ImportUiState,
    importViewModel: ImportViewModel,
    snackbarHostState: SnackbarHostState,
    onStartBackfill: () -> Unit,
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
            onBackfillClick = {
                importViewModel.reset()
                onStartBackfill()
            },
        )
    }
}

/**
 * Full import-result summary (ROADMAP Task 8 Phase B): per-file counts for every duplicate-policy
 * outcome, plus every rejected row's reason (scrollable, since a large messy import could reject
 * many rows) -- see [ImportSummary]'s KDoc for why a bare "done" isn't acceptable here.
 *
 * @param onBackfillClick Starts the bulk cover/author backfill (ROADMAP Task 14 Phase A) and
 *   dismisses this dialog, offered as a dismiss-button-adjacent action whenever [summary] actually
 *   added **books** ([ImportSummary.booksImported] > 0) -- the moment a coverless/authorless import
 *   just landed (a Goodreads import above all) is exactly when the need for a backfill is obvious,
 *   per that phase's brief. Never shown for an import that added nothing (a pure duplicate-skip
 *   pass has no new gaps to fill).
 *
 *   Books specifically, not [ImportSummary.itemsImported]. That backfill seeds itself from
 *   `BookRepository.getAllBooksWithDetails()`, so an import of only films and shows gives it
 *   nothing to do -- offering it there would produce a button whose entire effect is to log
 *   "nothing pending". The distinction did not exist while only books could be imported.
 */

@Composable
private fun ImportSummaryDialog(
    summary: ImportSummary,
    onDismiss: () -> Unit,
    onBackfillClick: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.ok_button)) }
        },
        dismissButton = {
            if (summary.booksImported > 0) {
                TextButton(onClick = onBackfillClick) {
                    Text(stringResource(R.string.settings_backfill_start_button))
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
