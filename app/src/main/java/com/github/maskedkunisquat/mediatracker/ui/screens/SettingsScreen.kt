package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.export.copyFileToUri
import com.github.maskedkunisquat.mediatracker.export.copyUriToFile
import com.github.maskedkunisquat.mediatracker.restartApp
import com.github.maskedkunisquat.mediatracker.ui.BackfillViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.BackupViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.ExportViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.ImportViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.MismatchReviewViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.RestoreViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.SettingsViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.TmdbBackfillViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.insets.scrollingContentPadding
import com.hub.media.core.database.RestoreMarker
import com.hub.media.core.util.LogLevel
import com.hub.media.core.util.Resource
import com.hub.media.features.books.domain.BulkBackfillProgress
import com.hub.media.features.media.domain.TmdbBackfillProgress
import com.hub.media.features.portability.domain.BackupResult
import com.hub.media.features.portability.domain.DuplicatePolicy
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
import com.hub.media.ui.MismatchReviewViewModel
import com.hub.media.ui.RestoreUiState
import com.hub.media.ui.RestoreViewModel
import com.hub.media.ui.SettingsUiState
import com.hub.media.ui.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    onNavigateToAbout: () -> Unit,
    onNavigateToMismatchReview: () -> Unit,
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
    // Both backfill ViewModels are the same erased class -- BackfillViewModel became generic in #140
    // so one implementation drives both passes -- and ViewModelProvider keys by class name. Without
    // distinct keys the second call here is handed the first one back, and the Settings screen shows
    // the book pass's progress under both headings. The keys are load-bearing, not decoration.
    val backfillViewModel: BackfillViewModel<BulkBackfillProgress> =
        viewModel(
            key = "backfill-books",
            factory = BackfillViewModelFactory(appContainer),
        )
    val tmdbBackfillViewModel: BackfillViewModel<TmdbBackfillProgress> =
        viewModel(
            key = "backfill-tmdb",
            factory = TmdbBackfillViewModelFactory(appContainer),
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val exportUiState by exportViewModel.uiState.collectAsStateWithLifecycle()
    val importUiState by importViewModel.uiState.collectAsStateWithLifecycle()
    val backupUiState by backupViewModel.uiState.collectAsStateWithLifecycle()
    val restoreUiState by restoreViewModel.uiState.collectAsStateWithLifecycle()
    val backfillUiState by backfillViewModel.uiState.collectAsStateWithLifecycle()
    val tmdbBackfillUiState by tmdbBackfillViewModel.uiState.collectAsStateWithLifecycle()

    // The disagreement count comes from the stored findings, which is the only place it is true
    // after a run has finished -- TmdbBackfillProgress carries one too, but a completed run clears
    // its resume state, so that number is unreachable by the time anyone opens Settings (#123).
    val mismatchViewModel: MismatchReviewViewModel =
        viewModel(factory = remember(appContainer) { MismatchReviewViewModelFactory(appContainer) })
    val mismatchUiState by mismatchViewModel.uiState.collectAsStateWithLifecycle()
    // No effect re-reading this: the ViewModel follows the stored findings, so a backfill finding
    // something and a season being reconciled on the review screen both reach this count on their
    // own. An effect keyed on the run's state would have covered only the first of those.

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val backupSuccessMessage = stringResource(R.string.backup_success_message)
    val backupFailureMessage = stringResource(R.string.backup_failure_message)
    val backupCancelledMessage = stringResource(R.string.backup_cancelled_message)
    val restoreCancelledMessage = stringResource(R.string.restore_cancelled_message)
    val restoreReadFailureMessage = stringResource(R.string.restore_read_failure_message)
    val apiKeySavedMessage = stringResource(R.string.settings_google_books_key_saved_message)
    val apiKeyClearedMessage = stringResource(R.string.settings_google_books_key_cleared_message)
    val tmdbSavedMessage = stringResource(R.string.settings_tmdb_key_saved_message)
    val tmdbClearedMessage = stringResource(R.string.settings_tmdb_key_cleared_message)
    val tmdbCheckingMessage = stringResource(R.string.settings_tmdb_key_test_checking)
    val tmdbTestOkMessage = stringResource(R.string.settings_tmdb_key_test_ok)

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

    val launchCsvImport = rememberCsvImportLauncher(importViewModel, duplicatePolicy, snackbarHostState)

    // ---- Goodreads import (ROADMAP Task 8 Phase D) ---------------------------------------------
    // A deliberately separate action from the CSV import above (own duplicate-policy choice, own
    // button, own single-file SAF picker -- a Goodreads export has no reading-logs equivalent to
    // ask for) so the two are never confused, even though both ultimately run through
    // ImportViewModel's shared Idle/Loading/Success/Error state -- they write to the same library
    // and can't usefully run concurrently.
    var goodreadsDuplicatePolicy by remember { mutableStateOf(DuplicatePolicy.SKIP) }

    val launchGoodreadsImport =
        rememberGoodreadsImportLauncher(importViewModel, goodreadsDuplicatePolicy, snackbarHostState)

    CsvExportFlow(exportUiState, exportViewModel, snackbarHostState)

    ImportOutcome(
        importUiState = importUiState,
        importViewModel = importViewModel,
        snackbarHostState = snackbarHostState,
        onStartBackfill = backfillViewModel::start,
    )

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
            credentialsWillBeCleared = state.credentialsWillBeCleared,
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
        onGoogleBooksApiKeySave = { key ->
            coroutineScope.launch {
                val result = viewModel.setGoogleBooksApiKey(key)
                if (result is Resource.Success) {
                    snackbarHostState.showSnackbar(apiKeySavedMessage)
                }
            }
        },
        onGoogleBooksApiKeyClear = {
            coroutineScope.launch {
                val result = viewModel.clearGoogleBooksApiKey()
                if (result is Resource.Success) {
                    snackbarHostState.showSnackbar(apiKeyClearedMessage)
                }
            }
        },
        onTmdbCredentialSave = { credential ->
            coroutineScope.launch {
                val result = viewModel.setTmdbCredential(credential)
                if (result is Resource.Success) {
                    snackbarHostState.showSnackbar(tmdbSavedMessage)
                }
            }
        },
        onTmdbCredentialClear = {
            coroutineScope.launch {
                val result = viewModel.clearTmdbCredential()
                if (result is Resource.Success) {
                    snackbarHostState.showSnackbar(tmdbClearedMessage)
                }
            }
        },
        onTmdbCredentialTest = {
            coroutineScope.launch {
                // showSnackbar suspends until the snackbar is dismissed, so calling it inline would
                // hold the request behind the message meant to cover it -- the check would not start
                // until "Checking" timed out. Instead it runs in its own coroutine, Indefinite so it
                // cannot expire early, and is cancelled the moment the answer arrives; cancelling
                // the caller is what dismisses a snackbar.
                val checking =
                    launch {
                        snackbarHostState.showSnackbar(
                            tmdbCheckingMessage,
                            duration = SnackbarDuration.Indefinite,
                        )
                    }
                val outcome = viewModel.verifyTmdbCredential()
                checking.cancel()
                when (val result = outcome) {
                    is Resource.Success -> snackbarHostState.showSnackbar(tmdbTestOkMessage)
                    // The client's own message is used verbatim: it already distinguishes "TMDB
                    // rejected the credential" from "could not reach TMDB", which is the whole
                    // point of pressing this, and restating it here would only blur that.
                    is Resource.Error -> snackbarHostState.showSnackbar(result.message)
                }
            }
        },
        onNavigateToLogViewer = onNavigateToLogViewer,
        onNavigateToChangelog = onNavigateToChangelog,
        onNavigateToAbout = onNavigateToAbout,
        exportInProgress = exportUiState is ExportUiState.Loading,
        onExportClick = exportViewModel::exportData,
        importInProgress = importUiState is ImportUiState.Loading,
        duplicatePolicy = duplicatePolicy,
        onDuplicatePolicyChange = { duplicatePolicy = it },
        onImportClick = launchCsvImport,
        goodreadsDuplicatePolicy = goodreadsDuplicatePolicy,
        onGoodreadsDuplicatePolicyChange = { goodreadsDuplicatePolicy = it },
        onImportGoodreadsClick = launchGoodreadsImport,
        backupInProgress = backupUiState is BackupUiState.Loading,
        onBackupClick = backupViewModel::backupData,
        restoreInProgress = restoreUiState is RestoreUiState.Validating,
        onRestoreClick = { restoreFilePickerLauncher.launch(arrayOf("*/*")) },
        backfillUiState = backfillUiState,
        onStartBackfillClick = backfillViewModel::start,
        onCancelBackfillClick = backfillViewModel::cancel,
        tmdbBackfillUiState = tmdbBackfillUiState,
        onStartTmdbBackfillClick = tmdbBackfillViewModel::start,
        onCancelTmdbBackfillClick = tmdbBackfillViewModel::cancel,
        mismatchedShows = mismatchUiState.rows.distinctBy { it.mediaId }.size,
        onReviewMismatchesClick = onNavigateToMismatchReview,
        snackbarHostState = snackbarHostState,
        onNavigateBack = onNavigateBack,
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
 * @param onGoogleBooksApiKeySave Called with the raw contents of the API-key field when Save is
 *   tapped, wired to [SettingsViewModel.setGoogleBooksApiKey] (which trims, and treats blank as
 *   "clear"). The value is passed straight through and never stored on this screen -- see
 *   [GoogleBooksApiKeySetting]'s KDoc.
 * @param onGoogleBooksApiKeyClear Called when the API key's Clear button is tapped, wired to
 *   [SettingsViewModel.clearGoogleBooksApiKey].
 * @param onTmdbCredentialSave Called with the raw contents of the TMDB field when Save is tapped,
 *   wired to [SettingsViewModel.setTmdbCredential]. Accepts either credential shape TMDB issues --
 *   see that method's KDoc for why nothing here inspects which.
 * @param onTmdbCredentialClear Called when the TMDB credential's Clear button is tapped, wired to
 *   [SettingsViewModel.clearTmdbCredential].
 * @param onTmdbCredentialTest Called when the TMDB credential's Test button is tapped, wired to
 *   [SettingsViewModel.verifyTmdbCredential]. Costs one request and reports the answer in the
 *   snackbar -- see that method's KDoc for what a success does and does not prove.
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
 *   same `ImportSummaryDialog` [onImportClick] uses -- both populate the same [ImportUiState].
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
 * @param tmdbBackfillUiState Current state of the films-and-shows artwork/metadata pass (#140). A
 *   second state rather than a merged one because the two passes are two actions, decided against
 *   #126's domain-sectioned Settings screen — see [FilmsAndTvBackfillSection] on why they cannot
 *   share a progress type either.
 * @param onStartTmdbBackfillClick Called when that pass's start/resume button is tapped.
 * @param onCancelTmdbBackfillClick Called when that pass's cancel button is tapped.
 * @param onNavigateBack Called when the back icon is pressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onWeekStartDayChange: (WeekStartDay) -> Unit,
    onLogVerbosityChange: (LogLevel) -> Unit,
    onGoogleBooksApiKeySave: (String) -> Unit,
    onGoogleBooksApiKeyClear: () -> Unit,
    onTmdbCredentialSave: (String) -> Unit,
    onTmdbCredentialClear: () -> Unit,
    onTmdbCredentialTest: () -> Unit,
    onNavigateToLogViewer: () -> Unit,
    onNavigateToChangelog: () -> Unit,
    onNavigateToAbout: () -> Unit,
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
    backfillUiState: BackfillUiState<BulkBackfillProgress>,
    onStartBackfillClick: () -> Unit,
    onCancelBackfillClick: () -> Unit,
    tmdbBackfillUiState: BackfillUiState<TmdbBackfillProgress>,
    onStartTmdbBackfillClick: () -> Unit,
    onCancelTmdbBackfillClick: () -> Unit,
    mismatchedShows: Int,
    onReviewMismatchesClick: () -> Unit,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        // The Google Books API key field lives on this screen, and Scaffold's default insets
        // leave out the IME -- safeDrawing keeps the keyboard from covering it.
        contentWindowInsets = WindowInsets.safeDrawing,
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
            // The keyboard shrinks the viewport, the bars do not: the list draws behind the bars
            // and re-adds their space as contentPadding, while the API key field still has to end
            // up above the IME.
            modifier =
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .consumeWindowInsets(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(TestTags.Settings.LIST),
                contentPadding = scrollingContentPadding(innerPadding, PaddingValues(16.dp)),
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
                    BookLookupsSection(
                        credentialSet = uiState.googleBooksApiKeySet,
                        onSave = onGoogleBooksApiKeySave,
                        onClear = onGoogleBooksApiKeyClear,
                    )
                }
                item {
                    FilmAndTvLookupsSection(
                        credentialSet = uiState.tmdbCredentialSet,
                        onSave = onTmdbCredentialSave,
                        onClear = onTmdbCredentialClear,
                        onTest = onTmdbCredentialTest,
                    )
                }
                item {
                    DiagnosticsSection(
                        logVerbosity = uiState.logVerbosity,
                        onLogVerbosityChange = onLogVerbosityChange,
                        onViewLogClick = onNavigateToLogViewer,
                        onViewChangelogClick = onNavigateToChangelog,
                    )
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
                    BookBackfillSection(
                        uiState = backfillUiState,
                        onStartClick = onStartBackfillClick,
                        onCancelClick = onCancelBackfillClick,
                    )
                }
                item {
                    FilmsAndTvBackfillSection(
                        uiState = tmdbBackfillUiState,
                        onStartClick = onStartTmdbBackfillClick,
                        onCancelClick = onCancelTmdbBackfillClick,
                        mismatchedShows = mismatchedShows,
                        onReviewClick = onReviewMismatchesClick,
                    )
                }
                item {
                    BackupRestoreSection(
                        backupInProgress = backupInProgress,
                        onBackupClick = onBackupClick,
                        restoreInProgress = restoreInProgress,
                        onRestoreClick = onRestoreClick,
                    )
                }
                item {
                    AboutSection(onViewAboutClick = onNavigateToAbout)
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
internal fun SettingsSection(
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
 * The data-export setting row (ROADMAP Task 8 Phase A; third file added ROADMAP Task 13 Phase C):
 * a label, a short description of what it produces, and a single button that generates
 * `library_export.csv`, `reading_logs_export.csv`, and `episodes_export.csv` from one consistent
 * snapshot and then prompts (via the route composable's SAF `ActivityResultContracts.CreateDocument`
 * launchers) for where to save each one in turn.
 *
 * ### Why one button for three files, rather than independent export actions
 * Exporting library metadata, reading-session history, and episode watched state separately would
 * let a book (or show) added or edited between exports leave the files describing different
 * moments in time -- `ExportDataUseCase` deliberately reads all three in one snapshot, so the UI
 * offers exactly one request that produces every file, rather than separate buttons that could be
 * tapped independently and reintroduce that inconsistency. Zipping the files into one download was
 * considered and rejected: it would need either a hand-rolled ZIP writer or a new dependency
 * (AGENTS.md §5), for a few-small-CSV-files case that doesn't need it -- sequential "save as"
 * prompts is a users-already-know-this-pattern tradeoff instead.
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
