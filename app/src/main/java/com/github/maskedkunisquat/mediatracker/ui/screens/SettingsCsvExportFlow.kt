package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.export.deleteCreatedDocument
import com.github.maskedkunisquat.mediatracker.export.writeBytesToUri
import com.hub.media.ui.ExportUiState
import com.hub.media.ui.ExportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one-file export (#196): one save dialog for one `.zip` holding the four CSVs and a manifest.
 * Replaced a chain of four dialogs, one per CSV.
 *
 * The built archive waits for its destination in the ViewModel ([ExportUiState.AwaitingDestination]),
 * not in this composable -- the pattern #162 established for backup -- so a rotation inside the dialog
 * neither loses the bytes nor opens the dialog a second time.
 */
@Composable
internal fun CsvExportFlow(
    exportUiState: ExportUiState,
    exportViewModel: ExportViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val exportSuccessMessage = stringResource(R.string.export_success_message)
    val exportFailureMessage = stringResource(R.string.export_failure_message)
    val exportCancelledMessage = stringResource(R.string.export_cancelled_message)
    val exportExpiredMessage = stringResource(R.string.export_expired_message)

    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri ->
            val file = exportViewModel.takePendingFile()
            coroutineScope.launch {
                val message =
                    when {
                        uri == null -> exportCancelledMessage
                        // Nothing pending: the process died while the dialog was open. It has already
                        // created an empty file there; remove it rather than leave an export that holds nothing.
                        file == null -> {
                            withContext(Dispatchers.IO) { deleteCreatedDocument(context, uri) }
                            exportExpiredMessage
                        }
                        // Off the main thread: writing a whole document via SAF is blocking I/O.
                        withContext(
                            Dispatchers.IO,
                        ) { writeBytesToUri(context, uri, file.bytes) } -> exportSuccessMessage
                        else -> exportFailureMessage
                    }
                snackbarHostState.showSnackbar(message)
            }
        }

    LaunchedEffect(exportUiState) {
        when (val state = exportUiState) {
            is ExportUiState.Success -> {
                exportViewModel.awaitDestination()
                exportLauncher.launch(state.file.suggestedFileName)
            }
            is ExportUiState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                exportViewModel.reset()
            }
            ExportUiState.Idle, ExportUiState.Loading, is ExportUiState.AwaitingDestination -> Unit
        }
    }
}
