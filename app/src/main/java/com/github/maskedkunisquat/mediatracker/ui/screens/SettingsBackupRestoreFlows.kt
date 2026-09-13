package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.export.copyFileToUri
import com.github.maskedkunisquat.mediatracker.export.copyUriToFile
import com.github.maskedkunisquat.mediatracker.restartApp
import com.hub.media.features.portability.domain.BackupResult
import com.hub.media.ui.AppContainer
import com.hub.media.ui.BackupUiState
import com.hub.media.ui.BackupViewModel
import com.hub.media.ui.RestoreUiState
import com.hub.media.ui.RestoreViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * The backup and restore SAF flows, lifted out of the route (#81) as the third cut of its
 * remaining SAF chains.
 */

/**
 * The `.sqlite` backup destination picker: owns `pendingBackupResult` and the `CreateDocument`
 * launcher, and reacts to [backupUiState] to launch it once a snapshot has been staged.
 */
@Composable
internal fun DatabaseBackupFlow(
    backupUiState: BackupUiState,
    backupViewModel: BackupViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val backupSuccessMessage = stringResource(R.string.backup_success_message)
    val backupFailureMessage = stringResource(R.string.backup_failure_message)
    val backupCancelledMessage = stringResource(R.string.backup_cancelled_message)

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
}

/**
 * The restore file picker: streams the picked file into a private cache location and hands it to
 * [RestoreViewModel.validateSelectedFile]. Returns the lambda the restore button calls to launch it.
 */
@Composable
internal fun rememberRestoreFileLauncher(
    restoreViewModel: RestoreViewModel,
    snackbarHostState: SnackbarHostState,
): () -> Unit {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val restoreCancelledMessage = stringResource(R.string.restore_cancelled_message)
    val restoreReadFailureMessage = stringResource(R.string.restore_read_failure_message)

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
                // validateSelectedFile -- once that call accepts it, RestoreDatabaseUseCase.stage owns
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
                    // A refused file (a validation already running or awaiting confirmation) stays
                    // ours, so the `finally` below deletes it.
                    handedOffToValidation = restoreViewModel.validateSelectedFile(incomingFile.absolutePath)
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

    return { restoreFilePickerLauncher.launch(arrayOf("*/*")) }
}

/**
 * Renders the outcome of the restore chain: an error Snackbar, or -- once the picked file has
 * passed non-destructive validation -- the [RestoreConfirmationDialog] that guards the destructive
 * commit.
 */
@Composable
internal fun RestoreOutcome(
    restoreUiState: RestoreUiState,
    restoreViewModel: RestoreViewModel,
    appContainer: AppContainer,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Set synchronously on the first Confirm tap. The dialog stays up until the process restarts, and
    // a second commit would move the freshly restored database over the pre-restore backup, while a
    // Cancel or dismiss would delete the staged file mid-commit.
    var commitStarted by remember { mutableStateOf(false) }

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
            commitInProgress = commitStarted,
            onConfirm = onConfirm@{
                if (commitStarted) return@onConfirm
                commitStarted = true
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
            onCancel = onCancel@{
                if (commitStarted) return@onCancel
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
}
