package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.BackfillViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.BackupViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.ExportViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.ImportViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.MismatchReviewViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.RestoreViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.SettingsViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.TmdbBackfillViewModelFactory
import com.hub.media.core.database.RestoreMarker
import com.hub.media.core.util.Resource
import com.hub.media.features.books.domain.BulkBackfillProgress
import com.hub.media.features.media.domain.TmdbBackfillProgress
import com.hub.media.features.portability.domain.DuplicatePolicy
import com.hub.media.ui.AppContainer
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
import com.hub.media.ui.SettingsViewModel
import kotlinx.coroutines.launch

/*
 * The Settings route composable, lifted out of `SettingsScreen.kt` (#81) as the fourth and final
 * cut: a pure move, once the four SAF chains it wires together had already moved to their own
 * files in the first three cuts.
 */

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

    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
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

    DatabaseBackupFlow(backupUiState, backupViewModel, snackbarHostState)

    val launchRestoreFile = rememberRestoreFileLauncher(restoreViewModel, snackbarHostState)

    RestoreOutcome(restoreUiState, restoreViewModel, appContainer, snackbarHostState)

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
        onRestoreClick = launchRestoreFile,
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
