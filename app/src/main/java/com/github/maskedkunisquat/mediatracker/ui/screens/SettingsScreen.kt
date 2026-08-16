package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.export.copyFileToUri
import com.github.maskedkunisquat.mediatracker.export.copyUriToFile
import com.github.maskedkunisquat.mediatracker.export.readCsvFromUri
import com.github.maskedkunisquat.mediatracker.export.writeCsvToUri
import com.github.maskedkunisquat.mediatracker.restartApp
import com.github.maskedkunisquat.mediatracker.ui.BackfillViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.BackupViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.ExportViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.ImportViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.RestoreViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.SettingsViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.RestoreMarker
import com.hub.media.core.util.LogLevel
import com.hub.media.features.books.domain.BulkBackfillProgress
import com.hub.media.features.portability.domain.BackupResult
import com.hub.media.features.portability.domain.CsvExportBundle
import com.hub.media.features.portability.domain.DuplicatePolicy
import com.hub.media.features.portability.domain.ImportRejection
import com.hub.media.features.portability.domain.ImportSummary
import com.hub.media.features.portability.domain.StagedRestoreInfo
import com.hub.media.features.settings.data.WeekStartDay
import com.hub.media.ui.AppContainer
import com.hub.media.ui.BackfillUiState
import com.hub.media.ui.BackfillViewModel
import com.hub.media.ui.BackupUiState
import com.hub.media.ui.BackupViewModel
import com.hub.media.ui.ExportUiState
import com.hub.media.ui.ExportViewModel
import com.hub.media.ui.ImportUiState
import com.hub.media.ui.ImportViewModel
import com.hub.media.ui.RestoreUiState
import com.hub.media.ui.RestoreViewModel
import com.hub.media.ui.SettingsUiState
import com.hub.media.ui.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.time.DurationUnit

/**
 * Route-level composable for the Settings screen (ROADMAP Task 7 Phase B).
 * Connects the [SettingsViewModel] to the stateless [SettingsScreen] and handles navigation.
 *
 * @param appContainer The dependency container for creating ViewModels.
 * @param onNavigateBack Callback to navigate back (TopAppBar back icon).
 */
@Composable
fun SettingsScreenRoute(
    appContainer: AppContainer,
    onNavigateBack: () -> Unit,
    onNavigateToLogViewer: () -> Unit,
    onNavigateToChangelog: () -> Unit,
) {
    val viewModel: SettingsViewModel =
        viewModel(
            factory = SettingsViewModelFactory(appContainer),
        )
    val exportViewModel: ExportViewModel =
        viewModel(
            factory = ExportViewModelFactory(appContainer),
        )
    val importViewModel: ImportViewModel =
        viewModel(
            factory = ImportViewModelFactory(appContainer),
        )
    val backupViewModel: BackupViewModel =
        viewModel(
            factory = BackupViewModelFactory(appContainer),
        )
    val restoreViewModel: RestoreViewModel =
        viewModel(
            factory = RestoreViewModelFactory(appContainer),
        )
    val backfillViewModel: BackfillViewModel =
        viewModel(
            factory = BackfillViewModelFactory(appContainer),
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val exportUiState by exportViewModel.uiState.collectAsStateWithLifecycle()
    val importUiState by importViewModel.uiState.collectAsStateWithLifecycle()
    val backupUiState by backupViewModel.uiState.collectAsStateWithLifecycle()
    val restoreUiState by restoreViewModel.uiState.collectAsStateWithLifecycle()
    val backfillUiState by backfillViewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val exportSuccessMessage = stringResource(R.string.export_success_message)
    val exportFailureMessage = stringResource(R.string.export_failure_message)
    val exportCancelledMessage = stringResource(R.string.export_cancelled_message)
    val importCancelledMessage = stringResource(R.string.import_cancelled_message)
    val importFailureMessage = stringResource(R.string.import_failure_message)
    val backupSuccessMessage = stringResource(R.string.backup_success_message)
    val backupFailureMessage = stringResource(R.string.backup_failure_message)
    val backupCancelledMessage = stringResource(R.string.backup_cancelled_message)
    val restoreCancelledMessage = stringResource(R.string.restore_cancelled_message)
    val restoreReadFailureMessage = stringResource(R.string.restore_read_failure_message)

    // Surfaced exactly once per Settings-screen visit (see AppContainer.pendingRestoreMarker's
    // KDoc): the outcome of a restore that completed just before this process was killed and
    // relaunched (ROADMAP Task 8 Phase C -- see DefaultRestoreDatabaseUseCase's KDoc for why a
    // restart follows every restore attempt, success or failure). `null` on every ordinary launch.
    val restoreOutcomeMessage: String? =
        when (val marker = appContainer.pendingRestoreMarker) {
            RestoreMarker.Success -> stringResource(R.string.restore_previous_success_message)
            is RestoreMarker.Failure -> stringResource(R.string.restore_previous_failure_message, marker.message)
            null -> null
        }
    LaunchedEffect(Unit) {
        restoreOutcomeMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    // The duplicate-policy choice the user makes visible before every import (ROADMAP Task 8 Phase
    // B brief: "make the duplicate policy a visible user choice rather than a hidden default").
    // SKIP is the default -- the only policy that can never overwrite or discard existing data,
    // matching AGENTS.md §1's "refuse and explain over guess and proceed" for the one screen that
    // writes to the user's real library.
    var duplicatePolicy by remember { mutableStateOf(DuplicatePolicy.SKIP) }

    // Holds the library file's text between the two sequential SAF "open document" picks below,
    // mirroring pendingBundle's export-side role: the reading-logs file is optional, so the second
    // picker's Cancel still runs the import with just the library file, rather than the
    // first-picker Cancel semantics below (which abort the whole import request).
    var pendingLibraryCsvForImport by remember { mutableStateOf<String?>(null) }

    val readingLogsImportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            val libraryCsv = pendingLibraryCsvForImport
            pendingLibraryCsvForImport = null
            // Off the main thread: reading a whole document via SAF is blocking I/O that runs
            // straight inside this launcher callback, which is itself dispatched on the main thread --
            // an unbounded read here (a large reading-logs export) would otherwise ANR the app.
            coroutineScope.launch {
                val readingLogsCsv = uri?.let { withContext(Dispatchers.IO) { readCsvFromUri(context, it) } }
                // A null uri means the user cancelled this second, optional picker -- that's a
                // legitimate "library only" import (see pendingLibraryCsvForImport's KDoc above). A
                // null readingLogsCsv from a uri the user *did* pick means the read itself failed --
                // matching libraryImportLauncher's own null-content handling below, report it and stop
                // before importData silently drops the reading logs and reports a clean success.
                if (uri != null && readingLogsCsv == null) {
                    snackbarHostState.showSnackbar(importFailureMessage)
                    return@launch
                }
                importViewModel.importData(libraryCsv, readingLogsCsv, duplicatePolicy)
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
            // the more likely of the two files to be big enough to matter.
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

    // ---- Goodreads import (ROADMAP Task 8 Phase D) ---------------------------------------------
    // A deliberately separate action from the CSV import above (own duplicate-policy choice, own
    // button, own single-file SAF picker -- a Goodreads export has no reading-logs equivalent to
    // ask for) so the two are never confused, even though both ultimately run through
    // ImportViewModel's shared Idle/Loading/Success/Error state -- they write to the same library
    // and can't usefully run concurrently.
    var goodreadsDuplicatePolicy by remember { mutableStateOf(DuplicatePolicy.SKIP) }

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

    // Holds the generated bundle between the two sequential SAF "create document" picks below --
    // see SettingsScreen.kt's class-level export section KDoc for why both files are written from
    // one cached bundle rather than two independent ExportDataUseCase runs.
    var pendingBundle by remember { mutableStateOf<CsvExportBundle?>(null) }

    val readingLogsLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv"),
        ) { uri ->
            val bundle = pendingBundle
            pendingBundle = null
            exportViewModel.reset()
            coroutineScope.launch {
                // Off the main thread: writing a whole document via SAF is blocking I/O.
                val message =
                    when {
                        uri == null -> exportCancelledMessage
                        bundle == null -> exportFailureMessage
                        withContext(
                            Dispatchers.IO,
                        ) { writeCsvToUri(context, uri, bundle.readingLogsCsv) } -> exportSuccessMessage
                        else -> exportFailureMessage
                    }
                snackbarHostState.showSnackbar(message)
            }
        }

    val libraryLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv"),
        ) { uri ->
            val bundle = pendingBundle
            if (uri == null || bundle == null) {
                pendingBundle = null
                exportViewModel.reset()
                coroutineScope.launch { snackbarHostState.showSnackbar(exportCancelledMessage) }
            } else {
                // Off the main thread -- see readingLogsLauncher above.
                coroutineScope.launch {
                    if (withContext(Dispatchers.IO) { writeCsvToUri(context, uri, bundle.libraryCsv) }) {
                        // First file written; immediately prompt for the second file's destination so
                        // both documents come from the exact same generated snapshot.
                        readingLogsLauncher.launch("reading_logs_export.csv")
                    } else {
                        pendingBundle = null
                        exportViewModel.reset()
                        snackbarHostState.showSnackbar(exportFailureMessage)
                    }
                }
            }
        }

    LaunchedEffect(exportUiState) {
        when (val state = exportUiState) {
            is ExportUiState.Success -> {
                pendingBundle = state.bundle
                libraryLauncher.launch("library_export.csv")
            }
            is ExportUiState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                exportViewModel.reset()
            }
            ExportUiState.Idle, ExportUiState.Loading -> Unit
        }
    }

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
                backfillViewModel.start()
            },
        )
    }

    // ---- Backup (ROADMAP Task 8 Phase C) ------------------------------------------------------
    // Holds the staged snapshot's path between BackupUiState.Success and the SAF destination
    // picker below, mirroring pendingBundle's export-side role.
    var pendingBackupResult by remember { mutableStateOf<BackupResult?>(null) }

    val backupDestinationLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri ->
            val result = pendingBackupResult
            pendingBackupResult = null
            backupViewModel.reset()
            coroutineScope.launch {
                try {
                    // Off the main thread: copying the staged database snapshot via SAF is blocking
                    // I/O over a potentially large file.
                    val message =
                        when {
                            uri == null -> backupCancelledMessage
                            result == null -> backupFailureMessage
                            withContext(
                                Dispatchers.IO,
                            ) { copyFileToUri(context, uri, result.stagedFilePath) } -> backupSuccessMessage
                            else -> backupFailureMessage
                        }
                    snackbarHostState.showSnackbar(message)
                } finally {
                    // The staged snapshot is this screen's own private temp file (not the live
                    // database itself) -- always clean it up once the SAF copy has been attempted,
                    // success, failure, or cancellation. This `finally` (rather than a plain statement
                    // after the `when`, as before) matters because `coroutineScope` comes from
                    // `rememberCoroutineScope()`: leaving Settings while the copy above is still
                    // running cancels this launch, and a plain post-`when` statement sitting after that
                    // suspension point would simply never run, leaking a whole-database-sized file in
                    // cacheDir. Wrapped in `NonCancellable` so the delete itself can't be skipped by
                    // that same cancellation.
                    result?.let { withContext(NonCancellable + Dispatchers.IO) { File(it.stagedFilePath).delete() } }
                }
            }
        }

    LaunchedEffect(backupUiState) {
        when (val state = backupUiState) {
            is BackupUiState.Success -> {
                pendingBackupResult = state.result
                backupDestinationLauncher.launch(state.result.suggestedFileName)
            }
            is BackupUiState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                backupViewModel.reset()
            }
            BackupUiState.Idle, BackupUiState.Loading -> Unit
        }
    }

    // ---- Restore (ROADMAP Task 8 Phase C) ------------------------------------------------------
    // The picked file is streamed into the app's own private cache directory *before* the
    // non-destructive shared-layer validation ever runs -- see RestoreDatabaseUseCase.stage's KDoc
    // for why this exact copy is what "copy the incoming file to a temp location" means here.
    val restoreFilePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) {
                coroutineScope.launch { snackbarHostState.showSnackbar(restoreCancelledMessage) }
                return@rememberLauncherForActivityResult
            }
            val incomingFile = File(context.cacheDir, "restore-incoming-${System.currentTimeMillis()}.tmp")
            // Off the main thread: this copies a whole database file via SAF, the largest single I/O
            // operation on this screen -- doing it synchronously here (as before) would ANR on any
            // real-sized library.
            coroutineScope.launch {
                // Tracks whether incomingFile's lifecycle has been handed off to
                // validateSelectedFile -- once that call is made, RestoreDatabaseUseCase.stage owns
                // the file (it deletes it on every rejection path) and, on success, ownership passes
                // again to the AwaitingConfirmation/commit flow below. Until that handoff happens,
                // nothing else ever takes ownership, so the `finally` below must clean it up itself.
                var handedOffToValidation = false
                try {
                    val copied = withContext(Dispatchers.IO) { copyUriToFile(context, uri, incomingFile.absolutePath) }
                    if (!copied) {
                        snackbarHostState.showSnackbar(restoreReadFailureMessage)
                        return@launch
                    }
                    handedOffToValidation = true
                    restoreViewModel.validateSelectedFile(incomingFile.absolutePath)
                } finally {
                    // `coroutineScope` is composition-scoped: leaving Settings while the copy above is
                    // still running cancels this launch. Without this `finally`, that cancellation (or
                    // a plain copy failure -- copyUriToFile already deletes its own partial output on
                    // an IOException, but not when the resolver simply couldn't open the input stream)
                    // would leave a whole-database-sized temp file behind in cacheDir with nothing left
                    // to ever clean it up. NonCancellable so the delete itself can't be skipped by that
                    // same cancellation.
                    if (!handedOffToValidation) {
                        withContext(NonCancellable + Dispatchers.IO) { incomingFile.delete() }
                    }
                }
            }
        }

    LaunchedEffect(restoreUiState) {
        val state = restoreUiState
        if (state is RestoreUiState.Error) {
            // No staged-file cleanup needed here, and none is possible: RestoreUiState.Error only
            // ever comes from RestoreViewModel.validateSelectedFile's Resource.Error branch, i.e.
            // RestoreDatabaseUseCase.stage -- which already deletes incomingFilePath via
            // deleteFileIfExists on every one of its rejection paths before it ever returns
            // Resource.Error. RestoreUiState.Error also carries no file path (see its KDoc), so
            // there is nothing this layer could delete even if the cleanup belonged here.
            snackbarHostState.showSnackbar(state.message)
            restoreViewModel.reset()
        }
    }

    (restoreUiState as? RestoreUiState.AwaitingConfirmation)?.let { state ->
        RestoreConfirmationDialog(
            info = state.info,
            onConfirm = {
                // Deliberately NOT routed through restoreViewModel.viewModelScope: the very next
                // step closes the AppContainer this ViewModel's own use case was wired from, and
                // the process is killed immediately after -- see RestoreViewModel's KDoc.
                //
                // The launch itself still comes from rememberCoroutineScope, so its Job is
                // cancelled the moment this composable leaves composition -- but everything from
                // appContainer.close() onward runs inside a single NonCancellable block, not just
                // on Dispatchers.IO. appContainer.close() happens first, so a cancellation landing
                // anywhere after that point (including the resume-back-to-Main that would
                // otherwise happen between the old withContext(Dispatchers.IO) block and a
                // separate restartApp(context) call) would leave a closed AppContainer alive in a
                // process that never restarts -- the exact "half-live container" AGENTS.md §1
                // warns against, and worse than doing nothing since the user is left looking at a
                // running app with no working database. NonCancellable (rather than, say, a
                // longer-lived application-scoped CoroutineScope) is the minimal fix here: it
                // guarantees this exact sequence runs to completion once started, without adding a
                // new scope that would need its own lifecycle management. restartApp is called
                // unconditionally, matching DefaultRestoreDatabaseUseCase.commit's own KDoc ("a
                // full process restart follows every commit call, success or failure") -- commit
                // itself never throws (it catches internally and always returns a Resource), so
                // the only failure mode this guards against is cancellation, not an exception from
                // commit.
                coroutineScope.launch {
                    withContext(Dispatchers.IO + NonCancellable) {
                        appContainer.close()
                        appContainer.restoreDatabaseUseCase.commit(state.info)
                        restartApp(context)
                    }
                }
            },
            onCancel = {
                // Declining the restore is the one place the staged copy is discarded by an
                // explicit user action rather than a failure path -- but it is still a
                // whole-database-sized file, so the delete belongs on Dispatchers.IO like every
                // other file operation on this screen, not on the main thread inside a Compose
                // callback. NonCancellable for the same reason the two sibling cleanup sites use
                // it: `coroutineScope` is composition-scoped, so tapping Cancel and immediately
                // leaving Settings would otherwise cancel this launch before the delete ran and
                // leak the file with nothing left to clean it up.
                coroutineScope.launch {
                    withContext(NonCancellable + Dispatchers.IO) {
                        File(state.info.stagedFilePath).delete()
                    }
                }
                restoreViewModel.reset()
            },
        )
    }

    SettingsScreen(
        uiState = uiState,
        onWeekStartDayChange = viewModel::setWeekStartDay,
        onLogVerbosityChange = viewModel::setLogVerbosity,
        onNavigateToLogViewer = onNavigateToLogViewer,
        onNavigateToChangelog = onNavigateToChangelog,
        exportInProgress = exportUiState is ExportUiState.Loading,
        onExportClick = exportViewModel::exportData,
        importInProgress = importUiState is ImportUiState.Loading,
        duplicatePolicy = duplicatePolicy,
        onDuplicatePolicyChange = { duplicatePolicy = it },
        onImportClick = { libraryImportLauncher.launch(arrayOf("text/*")) },
        goodreadsDuplicatePolicy = goodreadsDuplicatePolicy,
        onGoodreadsDuplicatePolicyChange = { goodreadsDuplicatePolicy = it },
        onImportGoodreadsClick = { goodreadsImportLauncher.launch(arrayOf("text/*")) },
        backupInProgress = backupUiState is BackupUiState.Loading,
        onBackupClick = backupViewModel::backupData,
        restoreInProgress = restoreUiState is RestoreUiState.Validating,
        onRestoreClick = { restoreFilePickerLauncher.launch(arrayOf("*/*")) },
        backfillUiState = backfillUiState,
        onStartBackfillClick = backfillViewModel::start,
        onCancelBackfillClick = backfillViewModel::cancel,
        snackbarHostState = snackbarHostState,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * Full import-result summary (ROADMAP Task 8 Phase B): per-file counts for every duplicate-policy
 * outcome, plus every rejected row's reason (scrollable, since a large messy import could reject
 * many rows) -- see [ImportSummary]'s KDoc for why a bare "done" isn't acceptable here.
 *
 * @param onBackfillClick Starts the bulk cover/author backfill (ROADMAP Task 14 Phase A) and
 *   dismisses this dialog, offered as a dismiss-button-adjacent action whenever [summary] actually
 *   added books ([ImportSummary.booksImported] > 0) -- the moment a coverless/authorless import
 *   just landed (a Goodreads import above all) is exactly when the need for a backfill is obvious,
 *   per that phase's brief. Never shown for an import that added nothing (a pure duplicate-skip
 *   pass has no new gaps to fill).
 */

/**
 * The levels offered in "Log detail", most verbose first to match [LogLevel]'s declaration order.
 *
 * [LogLevel.DEBUG] is deliberately absent: there is still not one DEBUG call site in the codebase,
 * so offering it promised a level of detail that behaved identically to Detailed. A value already
 * persisted as DEBUG is left alone rather than rewritten -- it still displays and still works, and
 * silently downgrading a diagnostic setting somebody deliberately turned on would be worse than
 * leaving one unlisted option in place. Add DEBUG back here the moment something logs at it.
 */
private val SELECTABLE_LOG_LEVELS: List<LogLevel> =
    listOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)

/**
 * The log-verbosity setting row (ROADMAP Task 15 Phase B2).
 *
 * A dropdown rather than [WeekStartDaySetting]'s segmented buttons: Material 3 reserves segmented
 * buttons for a small set meant to be compared side by side, and options whose labels are words
 * rather than single tokens would crowd a phone-width row. The order follows [LogLevel]'s own
 * declaration order, most verbose first, so "more detail" reads as down-the-list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogVerbositySetting(
    selected: LogLevel,
    onSelectedChange: (LogLevel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.settings_log_verbosity_label)
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_log_verbosity_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = selected.displayLabel(),
                onValueChange = {},
                readOnly = true,
                label = null,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                // The label is drawn as a separate Text above (matching WeekStartDaySetting's
                // layout), so this field has no Material label of its own and TalkBack would
                // otherwise announce only the bare value -- "Warnings", with no indication of which
                // setting it belongs to. Restating it here as a contentDescription gives screen
                // readers that context without changing the visual layout.
                modifier =
                    Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                        .semantics { contentDescription = label },
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                SELECTABLE_LOG_LEVELS.forEach { level ->
                    DropdownMenuItem(
                        text = { Text(level.displayLabel()) },
                        onClick = {
                            onSelectedChange(level)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * The log-viewer row (ROADMAP Task 15 Phase B2): navigates to the read-only viewer rather than
 * showing entries inline, since that screen needs its own scroll and selection behaviour that a
 * card inside this screen's `LazyColumn` could not provide.
 *
 * The description states the privacy guarantee explicitly. That is deliberate: a user about to
 * share a log with someone should be able to see, at the point of doing it, that it never contained
 * their titles, authors, or notes -- the identifier rule from Phase A is only reassuring if it is
 * visible where the decision is made.
 */

/**
 * The "What's new" row (ROADMAP Task 15 Phase B2b). Sits in Diagnostics beside the log viewer
 * rather than in its own section: both are read-only reference screens reached from here, and a
 * one-row section for each would be more chrome than content.
 */
@Composable
private fun ChangelogSetting(onViewChangelogClick: () -> Unit) {
    Column {
        Text(
            text = stringResource(R.string.settings_changelog_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_changelog_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Button(onClick = onViewChangelogClick) {
            Text(stringResource(R.string.settings_changelog_button))
        }
    }
}

@Composable
private fun LogViewerSetting(onViewLogClick: () -> Unit) {
    Column {
        Text(
            text = stringResource(R.string.settings_log_viewer_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_log_viewer_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Button(onClick = onViewLogClick) {
            Text(stringResource(R.string.settings_log_viewer_button))
        }
    }
}

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
                        R.string.import_summary_books_line,
                        summary.booksImported,
                        summary.booksSkipped,
                        summary.booksReplaced,
                        summary.booksMerged,
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

/**
 * The restore destructive-action confirmation (ROADMAP Task 8 Phase C task brief: "Require an
 * explicit, unambiguous confirmation that states what will be lost. Do not make it a single tap
 * next to the export button.") -- a dedicated modal dialog, reached only after the picked file has
 * already passed non-destructive header/version validation (so this dialog never appears for a
 * file that turns out to be unusable), requiring an explicit checkbox acknowledgement before the
 * destructive confirm button becomes enabled, with that button styled in the theme's `error` color
 * to read as visually distinct from every other action on this screen.
 */
@Composable
private fun RestoreConfirmationDialog(
    info: StagedRestoreInfo,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var understood by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.restore_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.restore_confirm_message))
                if (info.isOlderSchemaVersion) {
                    Text(
                        text = stringResource(R.string.restore_confirm_message_older_version, info.schemaVersionFound),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { understood = !understood },
                ) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text(
                        text = stringResource(R.string.restore_confirm_checkbox_label),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = understood,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.restore_confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.restore_cancel_button)) }
        },
    )
}

/**
 * Stateless Settings screen composable (AGENTS.md §5 State Hoisting).
 *
 * ### Structure, built to be extended
 * The ROADMAP expects more settings to land on this screen over time, so the screen is a
 * [LazyColumn] of independent [SettingsSection]s rather than a one-off layout built around the
 * single week-start-day preference this phase adds. A future setting is added the same way this
 * one was: a new `item { SettingsSection(...) { ... } }` block below (in whichever section fits it,
 * or a new section title if none does), a new field on [SettingsUiState], and a new action
 * parameter here mirroring [onWeekStartDayChange] — no restructuring of this composable itself.
 *
 * @param uiState Current [SettingsUiState].
 * @param onWeekStartDayChange Called with the newly selected [WeekStartDay] when the week-start-day
 *   control is changed, wired to [SettingsViewModel.setWeekStartDay].
 * @param exportInProgress Whether a CSV export is currently being generated (ROADMAP Task 8 Phase
 *   A) -- wired to `ExportUiState.Loading`, disables the export button and shows a progress
 *   indicator so a double-tap can't fire two concurrent exports.
 * @param onExportClick Called when the export button is tapped, wired to
 *   `ExportViewModel.exportData`. The actual SAF file-picker/write sequence happens in the route
 *   composable, not here -- this stateless screen only ever emits the request.
 * @param snackbarHostState Hosts the success/failure/cancelled Snackbar the route composable shows
 *   once the export (and subsequent SAF writes) finish -- a silently failed export would be worse
 *   than no export button at all (this phase's task brief).
 * @param importInProgress Whether a CSV import is currently running (ROADMAP Task 8 Phase B) --
 *   wired to `ImportUiState.Loading`, disables the import button and shows a progress indicator so
 *   a double-tap can't fire two concurrent imports.
 * @param duplicatePolicy The currently-selected [DuplicatePolicy], shown as a visible three-way
 *   choice rather than a hidden default (this phase's brief).
 * @param onDuplicatePolicyChange Called with the newly selected [DuplicatePolicy].
 * @param onImportClick Called when the import button is tapped, wired to launch the library-file
 *   SAF picker. The actual SAF file-picker/read sequence and the resulting summary dialog happen
 *   in the route composable, not here.
 * @param goodreadsDuplicatePolicy The currently-selected [DuplicatePolicy] for the *Goodreads*
 *   import row (ROADMAP Task 8 Phase D) -- deliberately a separate choice from [duplicatePolicy],
 *   not shared state, since the two import actions are meant to read as distinct.
 * @param onGoodreadsDuplicatePolicyChange Called with the newly selected [DuplicatePolicy] for the
 *   Goodreads import row.
 * @param onImportGoodreadsClick Called when the "Import from Goodreads" button is tapped, wired to
 *   launch a single-file SAF picker (a Goodreads export has no reading-logs equivalent to ask for
 *   afterward, unlike [onImportClick]'s two-file sequence). The resulting summary dialog is the
 *   same [ImportSummaryDialog] [onImportClick] uses -- both populate the same [ImportUiState].
 * @param backupInProgress Whether a `.sqlite` backup snapshot is currently being generated
 *   (ROADMAP Task 8 Phase C) -- wired to `BackupUiState.Loading`.
 * @param onBackupClick Called when the backup button is tapped, wired to
 *   `BackupViewModel.backupData`. Non-destructive -- no confirmation needed, unlike restore.
 * @param restoreInProgress Whether a picked restore candidate is currently being validated
 *   (ROADMAP Task 8 Phase C) -- wired to `RestoreUiState.Validating`. This is still the
 *   *non-destructive* half; the destructive confirmation dialog itself is shown by the route
 *   composable, not here.
 * @param onRestoreClick Called when the restore button is tapped, wired to launch the SAF file
 *   picker. Deliberately placed in its own visually-distinct section from every other action on
 *   this screen (this phase's brief: restore must not be "a single tap next to the export
 *   button").
 * @param backfillUiState Current [BackfillUiState] for the bulk cover/author backfill action
 *   (ROADMAP Task 14 Phase A) -- unlike [exportInProgress]/[importInProgress]'s bare booleans, this
 *   is a full sealed state since the action reports live progress and can be resumed, not just
 *   in-flight-or-not.
 * @param onStartBackfillClick Called when the backfill start/resume button is tapped, wired to
 *   [BackfillViewModel.start].
 * @param onCancelBackfillClick Called when the backfill cancel button is tapped, wired to
 *   [BackfillViewModel.cancel].
 * @param onNavigateBack Called when the back icon is pressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onWeekStartDayChange: (WeekStartDay) -> Unit,
    onLogVerbosityChange: (LogLevel) -> Unit,
    onNavigateToLogViewer: () -> Unit,
    onNavigateToChangelog: () -> Unit,
    exportInProgress: Boolean,
    onExportClick: () -> Unit,
    importInProgress: Boolean,
    duplicatePolicy: DuplicatePolicy,
    onDuplicatePolicyChange: (DuplicatePolicy) -> Unit,
    onImportClick: () -> Unit,
    goodreadsDuplicatePolicy: DuplicatePolicy,
    onGoodreadsDuplicatePolicyChange: (DuplicatePolicy) -> Unit,
    onImportGoodreadsClick: () -> Unit,
    backupInProgress: Boolean,
    onBackupClick: () -> Unit,
    restoreInProgress: Boolean,
    onRestoreClick: () -> Unit,
    backfillUiState: BackfillUiState,
    onStartBackfillClick: () -> Unit,
    onCancelBackfillClick: () -> Unit,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item {
                    SettingsSection(title = stringResource(R.string.settings_section_stats)) {
                        WeekStartDaySetting(
                            selected = uiState.weekStartDay,
                            onSelectedChange = onWeekStartDayChange,
                        )
                    }
                }
                item {
                    SettingsSection(title = stringResource(R.string.settings_section_diagnostics)) {
                        LogVerbositySetting(
                            selected = uiState.logVerbosity,
                            onSelectedChange = onLogVerbosityChange,
                        )
                        HorizontalDivider()
                        LogViewerSetting(onViewLogClick = onNavigateToLogViewer)
                        HorizontalDivider()
                        ChangelogSetting(onViewChangelogClick = onNavigateToChangelog)
                    }
                }
                item {
                    SettingsSection(title = stringResource(R.string.settings_section_data)) {
                        ExportDataSetting(
                            exportInProgress = exportInProgress,
                            onExportClick = onExportClick,
                        )
                        HorizontalDivider()
                        ImportDataSetting(
                            importInProgress = importInProgress,
                            duplicatePolicy = duplicatePolicy,
                            onDuplicatePolicyChange = onDuplicatePolicyChange,
                            onImportClick = onImportClick,
                        )
                        HorizontalDivider()
                        ImportGoodreadsDataSetting(
                            importInProgress = importInProgress,
                            duplicatePolicy = goodreadsDuplicatePolicy,
                            onDuplicatePolicyChange = onGoodreadsDuplicatePolicyChange,
                            onImportClick = onImportGoodreadsClick,
                        )
                    }
                }
                item {
                    // Its own section, not another row in the "Data" card above -- this is a
                    // long-running, resumable, cancellable action (ROADMAP Task 14 Phase A's
                    // brief: "not a single tap"), unlike the export/import rows above it, and
                    // reads better grouped with the repair-your-library concern it serves rather
                    // than folded into the import/export card.
                    SettingsSection(title = stringResource(R.string.settings_section_backfill)) {
                        BackfillSetting(
                            uiState = backfillUiState,
                            onStartClick = onStartBackfillClick,
                            onCancelClick = onCancelBackfillClick,
                        )
                    }
                }
                item {
                    // A separate section (not another row in the "Data" card above) -- ROADMAP
                    // Task 8 Phase C's brief calls for backup and restore to be "clearly
                    // separated by risk" from CSV export/import and from each other; a whole-
                    // database restore is destructive in a way the CSV importer's DuplicatePolicy
                    // (SKIP/MERGE always preserve existing rows) never is.
                    SettingsSection(title = stringResource(R.string.settings_section_backup_restore)) {
                        BackupDataSetting(
                            backupInProgress = backupInProgress,
                            onBackupClick = onBackupClick,
                        )
                        HorizontalDivider()
                        RestoreDataSetting(
                            restoreInProgress = restoreInProgress,
                            onRestoreClick = onRestoreClick,
                        )
                    }
                }
                // Future settings sections are added here as additional `item { SettingsSection(...) }`
                // blocks -- see this composable's KDoc.
            }
        }
    }
}

/**
 * One titled card-backed group of related settings rows. The single occupant this phase is
 * [WeekStartDaySetting]; a future setting either joins an existing section's [content] or starts a
 * new [SettingsSection] with its own title.
 */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

/**
 * The week-start-day setting row (ROADMAP Task 7 Phase B): a label, a short description of what it
 * affects, and a two-option [SingleChoiceSegmentedButtonRow].
 *
 * Segmented buttons (rather than a radio-row group, as [EditBookScreen] uses for its several
 * multi-way choices) are chosen here specifically because this is exactly two mutually exclusive
 * options meant to be compared side by side — Material 3's guidance reserves segmented buttons for
 * a small (2-5), fixed, always-fully-visible set of choices shown together, which is a better fit
 * for a binary toggle like this than a vertical radio-button list (which reads more naturally for
 * the longer, unrelated-to-each-other option sets [EditBookScreen] presents, e.g. all five
 * [com.hub.media.core.database.entities.BookFormat] values).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekStartDaySetting(
    selected: WeekStartDay,
    onSelectedChange: (WeekStartDay) -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.settings_week_start_day_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_week_start_day_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val options = WeekStartDay.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = selected == option,
                    onClick = { onSelectedChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = { Text(option.displayLabel()) },
                )
            }
        }
    }
}

/**
 * The data-export setting row (ROADMAP Task 8 Phase A): a label, a short description of what it
 * produces, and a single button that generates both `library_export.csv` and
 * `reading_logs_export.csv` from one consistent snapshot and then prompts (via the route
 * composable's SAF `ActivityResultContracts.CreateDocument` launchers) for where to save each one
 * in turn.
 *
 * ### Why one button for two files, rather than two independent export actions
 * Exporting library metadata and reading-session history separately would let a book added or
 * edited between the two exports leave the two files describing different moments in time --
 * `ExportDataUseCase` deliberately reads both in one snapshot, so the UI offers exactly one
 * request that produces both, rather than two buttons that could be tapped independently and
 * reintroduce that inconsistency. Zipping the two files into one download was considered and
 * rejected: it would need either a hand-rolled ZIP writer or a new dependency (AGENTS.md §5),
 * for a two-small-CSV-files case that doesn't need it -- two sequential "save as" prompts is a
 * users-already-know-this-pattern tradeoff instead.
 */
@Composable
private fun ExportDataSetting(
    exportInProgress: Boolean,
    onExportClick: () -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.settings_export_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_export_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Button(onClick = onExportClick, enabled = !exportInProgress) {
            if (exportInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.settings_export_button))
            }
        }
    }
}

/**
 * The data-import setting row (ROADMAP Task 8 Phase B): a label/description, a visible
 * [DuplicatePolicy] choice, and a single button that starts the SAF library-then-reading-logs
 * file-picker sequence (the route composable's `ActivityResultContracts.OpenDocument` launchers).
 *
 * The [DuplicatePolicy] picker is deliberately placed *above* the import button, not hidden behind
 * a settings menu or defaulted silently -- this phase's brief calls for making the duplicate
 * policy "a visible user choice rather than a hidden default," since it directly controls whether
 * an import can overwrite existing data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportDataSetting(
    importInProgress: Boolean,
    duplicatePolicy: DuplicatePolicy,
    onDuplicatePolicyChange: (DuplicatePolicy) -> Unit,
    onImportClick: () -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.settings_import_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_import_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = stringResource(R.string.settings_import_duplicate_policy_label),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.settings_import_duplicate_policy_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val options = DuplicatePolicy.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = duplicatePolicy == option,
                    onClick = { onDuplicatePolicyChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = { Text(option.displayLabel()) },
                )
            }
        }
        Button(
            onClick = onImportClick,
            enabled = !importInProgress,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            if (importInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.settings_import_button))
            }
        }
    }
}

/**
 * The Goodreads-import setting row (ROADMAP Task 8 Phase D) -- structurally a near-twin of
 * [ImportDataSetting] (label/description, a visible [DuplicatePolicy] choice, a single button
 * launching an SAF picker), but a genuinely **separate** action with its own state, not a shared
 * control: this phase's brief calls for the Goodreads import to be distinct from the app's own CSV
 * import "so the two aren't confused" -- a user with both a `library_export.csv` and a
 * `goodreads_library_export.csv` on hand must never be unsure which button reads which file
 * format. Only a single-file SAF picker is launched (no second "reading logs" prompt) -- a
 * Goodreads export carries no session-level history, only Goodreads' own book-level shelf/date
 * fields (mapped by `GoodreadsCsvImporter` in the shared module).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportGoodreadsDataSetting(
    importInProgress: Boolean,
    duplicatePolicy: DuplicatePolicy,
    onDuplicatePolicyChange: (DuplicatePolicy) -> Unit,
    onImportClick: () -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.settings_import_goodreads_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_import_goodreads_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = stringResource(R.string.settings_import_duplicate_policy_label),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.settings_import_duplicate_policy_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val options = DuplicatePolicy.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = duplicatePolicy == option,
                    onClick = { onDuplicatePolicyChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = { Text(option.displayLabel()) },
                )
            }
        }
        Button(
            onClick = onImportClick,
            enabled = !importInProgress,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            if (importInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.settings_import_goodreads_button))
            }
        }
    }
}

/**
 * The `.sqlite` backup setting row (ROADMAP Task 8 Phase C): a label, a short description, and a
 * single button that produces a complete database snapshot and then prompts (via the route
 * composable's SAF `CreateDocument` launcher) for where to save it. Non-destructive -- unlike
 * [RestoreDataSetting], this never reads anything other than the live database and never writes to
 * it, so it needs no confirmation dialog, matching [ExportDataSetting]'s shape.
 */
@Composable
private fun BackupDataSetting(
    backupInProgress: Boolean,
    onBackupClick: () -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.settings_backup_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_backup_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Button(onClick = onBackupClick, enabled = !backupInProgress) {
            if (backupInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.settings_backup_button))
            }
        }
    }
}

/**
 * The `.sqlite` restore setting row (ROADMAP Task 8 Phase C) -- the single most dangerous action in
 * the app (AGENTS.md §1). Deliberately styled and worded to read as higher-risk than every other
 * row on this screen:
 * - An [OutlinedButton] in the theme's `error` color, not a filled primary [Button] like every
 *   other action here -- visually distinct at a glance, before the user even reads the label.
 * - The description states plainly that this replaces the whole library and cannot be undone,
 *   rather than a neutral "restore your data" framing.
 * - Tapping this button only ever *launches the file picker* -- it never touches the live database
 *   by itself. The actual destructive action requires the picked file to first pass non-destructive
 *   validation, then an explicit checkbox-gated confirmation dialog (see
 *   `RestoreConfirmationDialog`, shown by the route composable), satisfying this phase's brief that
 *   restore must not be "a single tap next to the export button."
 */
@Composable
private fun RestoreDataSetting(
    restoreInProgress: Boolean,
    onRestoreClick: () -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.settings_restore_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_restore_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedButton(
            onClick = onRestoreClick,
            enabled = !restoreInProgress,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        ) {
            if (restoreInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.restore_validating_message),
                    modifier = Modifier.padding(start = 8.dp),
                )
            } else {
                Text(stringResource(R.string.settings_restore_button))
            }
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
private fun BackfillSetting(
    uiState: BackfillUiState,
    onStartClick: () -> Unit,
    onCancelClick: () -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.settings_backfill_description),
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
                BackfillRunningContent(progress = uiState.progress)
                OutlinedButton(onClick = onCancelClick, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.settings_backfill_cancel_button))
                }
            }
            is BackfillUiState.Stopped -> {
                BackfillStoppedContent(progress = uiState.progress)
                Button(onClick = onStartClick, modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(
                            if (uiState.progress.remaining > 0) {
                                R.string.settings_backfill_resume_button
                            } else {
                                R.string.settings_backfill_start_button
                            },
                        ),
                    )
                }
            }
            is BackfillUiState.Failed -> {
                BackfillFailedContent(progress = uiState.progress)
                Button(onClick = onStartClick, modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(
                            if ((uiState.progress?.remaining ?: 0) > 0) {
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

/** [BackfillUiState.Running]'s body: a progress bar once the first book has been checkpointed. */
@Composable
private fun BackfillRunningContent(progress: BulkBackfillProgress?) {
    if (progress != null && progress.totalCandidates > 0) {
        LinearProgressIndicator(
            progress = { progress.processed.toFloat() / progress.totalCandidates },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
        )
        Text(
            text =
                stringResource(
                    R.string.settings_backfill_progress_format,
                    progress.processed,
                    progress.totalCandidates,
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
 * [BackfillUiState.Stopped]'s body: whether this is a paused-by-quota run, a finished run, or
 * resumable leftover state from a previous session, plus the running totals so far.
 */
@Composable
private fun BackfillStoppedContent(progress: BulkBackfillProgress) {
    when {
        progress.isPaused -> {
            val retryAfter = progress.retryAfter
            val message =
                if (retryAfter != null) {
                    // Round up, floored at one minute, so a sub-minute wait (e.g. 30s) never renders
                    // as the misleading "about 0 min" -- any nonzero wait is at least "about 1 min".
                    val minutes = ceil(retryAfter.toDouble(DurationUnit.MINUTES)).toInt().coerceAtLeast(1)
                    pluralStringResource(R.plurals.settings_backfill_paused_with_wait_format, minutes, minutes)
                } else {
                    stringResource(R.string.settings_backfill_paused_message)
                }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        progress.isComplete ->
            Text(
                text = stringResource(R.string.settings_backfill_complete_message),
                style = MaterialTheme.typography.bodySmall,
            )
    }
    if (progress.totalCandidates > 0) {
        Text(
            text =
                stringResource(
                    R.string.settings_backfill_progress_format,
                    progress.processed,
                    progress.totalCandidates,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (progress.processed > 0) {
        Text(
            text =
                stringResource(
                    R.string.settings_backfill_summary_format,
                    progress.updated,
                    progress.noProviderData,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (progress.noIsbnSkipped > 0) {
        Text(
            text = stringResource(R.string.settings_backfill_no_isbn_format, progress.noIsbnSkipped),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * [BackfillUiState.Failed]'s body: an explicit failure signal, distinct from
 * [BackfillStoppedContent]'s "paused"/"complete" messaging, so the user isn't left thinking a
 * genuine mid-run failure was just a clean stop. [progress] is `null` when nothing was
 * checkpointed before the failure, in which case there is no partial-progress line to show.
 */
@Composable
private fun BackfillFailedContent(progress: BulkBackfillProgress?) {
    Text(
        text = stringResource(R.string.settings_backfill_failed_message),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
    if (progress != null && progress.totalCandidates > 0) {
        Text(
            text =
                stringResource(
                    R.string.settings_backfill_progress_format,
                    progress.processed,
                    progress.totalCandidates,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Preview of the Settings screen with the default (Monday) week-start-day selected. */
@Preview(showBackground = true)
@Composable
private fun SettingsScreenMondayPreview() {
    MediaTrackerTheme {
        SettingsScreen(
            uiState = SettingsUiState(weekStartDay = WeekStartDay.MONDAY),
            onWeekStartDayChange = {},
            onLogVerbosityChange = {},
            onNavigateToLogViewer = {},
            onNavigateToChangelog = {},
            exportInProgress = false,
            onExportClick = {},
            importInProgress = false,
            duplicatePolicy = DuplicatePolicy.SKIP,
            onDuplicatePolicyChange = {},
            onImportClick = {},
            goodreadsDuplicatePolicy = DuplicatePolicy.SKIP,
            onGoodreadsDuplicatePolicyChange = {},
            onImportGoodreadsClick = {},
            backupInProgress = false,
            onBackupClick = {},
            restoreInProgress = false,
            onRestoreClick = {},
            backfillUiState = BackfillUiState.Idle,
            onStartBackfillClick = {},
            onCancelBackfillClick = {},
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
        )
    }
}

/** Preview of the Settings screen with Sunday selected as the week-start day. */
@Preview(showBackground = true)
@Composable
private fun SettingsScreenSundayPreview() {
    MediaTrackerTheme {
        SettingsScreen(
            uiState = SettingsUiState(weekStartDay = WeekStartDay.SUNDAY),
            onWeekStartDayChange = {},
            onLogVerbosityChange = {},
            onNavigateToLogViewer = {},
            onNavigateToChangelog = {},
            exportInProgress = false,
            onExportClick = {},
            importInProgress = false,
            duplicatePolicy = DuplicatePolicy.SKIP,
            onDuplicatePolicyChange = {},
            onImportClick = {},
            goodreadsDuplicatePolicy = DuplicatePolicy.SKIP,
            onGoodreadsDuplicatePolicyChange = {},
            onImportGoodreadsClick = {},
            backupInProgress = false,
            onBackupClick = {},
            restoreInProgress = false,
            onRestoreClick = {},
            backfillUiState = BackfillUiState.Idle,
            onStartBackfillClick = {},
            onCancelBackfillClick = {},
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
        )
    }
}

/** Preview of the Settings screen mid-export (progress indicator on the export button). */
@Preview(showBackground = true)
@Composable
private fun SettingsScreenExportingPreview() {
    MediaTrackerTheme {
        SettingsScreen(
            uiState = SettingsUiState(weekStartDay = WeekStartDay.MONDAY),
            onWeekStartDayChange = {},
            onLogVerbosityChange = {},
            onNavigateToLogViewer = {},
            onNavigateToChangelog = {},
            exportInProgress = true,
            onExportClick = {},
            importInProgress = false,
            duplicatePolicy = DuplicatePolicy.SKIP,
            onDuplicatePolicyChange = {},
            onImportClick = {},
            goodreadsDuplicatePolicy = DuplicatePolicy.SKIP,
            onGoodreadsDuplicatePolicyChange = {},
            onImportGoodreadsClick = {},
            backupInProgress = false,
            onBackupClick = {},
            restoreInProgress = false,
            onRestoreClick = {},
            backfillUiState = BackfillUiState.Idle,
            onStartBackfillClick = {},
            onCancelBackfillClick = {},
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
        )
    }
}

/** Preview of the Settings screen mid-backup (progress indicator on the backup button). */
@Preview(showBackground = true)
@Composable
private fun SettingsScreenBackingUpPreview() {
    MediaTrackerTheme {
        SettingsScreen(
            uiState = SettingsUiState(weekStartDay = WeekStartDay.MONDAY),
            onWeekStartDayChange = {},
            onLogVerbosityChange = {},
            onNavigateToLogViewer = {},
            onNavigateToChangelog = {},
            exportInProgress = false,
            onExportClick = {},
            importInProgress = false,
            duplicatePolicy = DuplicatePolicy.SKIP,
            onDuplicatePolicyChange = {},
            onImportClick = {},
            goodreadsDuplicatePolicy = DuplicatePolicy.SKIP,
            onGoodreadsDuplicatePolicyChange = {},
            onImportGoodreadsClick = {},
            backupInProgress = true,
            onBackupClick = {},
            restoreInProgress = false,
            onRestoreClick = {},
            backfillUiState = BackfillUiState.Idle,
            onStartBackfillClick = {},
            onCancelBackfillClick = {},
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
        )
    }
}

/** Preview of the Settings screen while a picked restore candidate is being validated. */
@Preview(showBackground = true)
@Composable
private fun SettingsScreenValidatingRestorePreview() {
    MediaTrackerTheme {
        SettingsScreen(
            uiState = SettingsUiState(weekStartDay = WeekStartDay.MONDAY),
            onWeekStartDayChange = {},
            onLogVerbosityChange = {},
            onNavigateToLogViewer = {},
            onNavigateToChangelog = {},
            exportInProgress = false,
            onExportClick = {},
            importInProgress = false,
            duplicatePolicy = DuplicatePolicy.SKIP,
            onDuplicatePolicyChange = {},
            onImportClick = {},
            goodreadsDuplicatePolicy = DuplicatePolicy.SKIP,
            onGoodreadsDuplicatePolicyChange = {},
            onImportGoodreadsClick = {},
            backupInProgress = false,
            onBackupClick = {},
            restoreInProgress = true,
            onRestoreClick = {},
            backfillUiState = BackfillUiState.Idle,
            onStartBackfillClick = {},
            onCancelBackfillClick = {},
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
        )
    }
}

/** Preview of the destructive restore confirmation dialog, for a backup at the current schema version. */
@Preview(showBackground = true)
@Composable
private fun RestoreConfirmationDialogPreview() {
    MediaTrackerTheme {
        RestoreConfirmationDialog(
            info =
                StagedRestoreInfo(
                    stagedFilePath = "/data/user/0/com.github.maskedkunisquat.mediatracker/cache/restore-incoming.tmp",
                    schemaVersionFound = 4,
                    isOlderSchemaVersion = false,
                ),
            onConfirm = {},
            onCancel = {},
        )
    }
}

/** Preview of the destructive restore confirmation dialog, for a backup from an older schema version. */
@Preview(showBackground = true)
@Composable
private fun RestoreConfirmationDialogOlderVersionPreview() {
    MediaTrackerTheme {
        RestoreConfirmationDialog(
            info =
                StagedRestoreInfo(
                    stagedFilePath = "/data/user/0/com.github.maskedkunisquat.mediatracker/cache/restore-incoming.tmp",
                    schemaVersionFound = 2,
                    isOlderSchemaVersion = true,
                ),
            onConfirm = {},
            onCancel = {},
        )
    }
}
