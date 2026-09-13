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
import com.github.maskedkunisquat.mediatracker.export.writeCsvToUri
import com.hub.media.features.portability.domain.CsvExportBundle
import com.hub.media.ui.ExportUiState
import com.hub.media.ui.ExportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The CSV export flow's SAF picker chain, lifted out of the route (#81) as the second cut of its
 * remaining SAF chains.
 */

/**
 * The three-picker CSV export chain (library, then reading logs, then episodes), all written from
 * one cached [CsvExportBundle] snapshot -- see `ExportDataSetting`'s KDoc for why one bundle
 * produces all three files. Registers the three `CreateDocument` launchers and reacts to
 * [exportUiState] to kick off the first one.
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

    // Holds the generated bundle between the three sequential SAF "create document" picks below --
    // see ExportDataSetting's KDoc for why all three files are written from one cached bundle
    // rather than three independent ExportDataUseCase runs.
    var pendingBundle by remember { mutableStateOf<CsvExportBundle?>(null) }

    val episodesLauncher =
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
                        ) { writeCsvToUri(context, uri, bundle.episodesCsv) } -> exportSuccessMessage
                        else -> exportFailureMessage
                    }
                snackbarHostState.showSnackbar(message)
            }
        }

    val readingLogsLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv"),
        ) { uri ->
            val bundle = pendingBundle
            if (uri == null || bundle == null) {
                pendingBundle = null
                exportViewModel.reset()
                coroutineScope.launch { snackbarHostState.showSnackbar(exportCancelledMessage) }
            } else {
                // Off the main thread -- see episodesLauncher above.
                coroutineScope.launch {
                    if (withContext(Dispatchers.IO) { writeCsvToUri(context, uri, bundle.readingLogsCsv) }) {
                        // Second file written; immediately prompt for the third file's destination so
                        // all three documents come from the exact same generated snapshot.
                        episodesLauncher.launch("episodes_export.csv")
                    } else {
                        pendingBundle = null
                        exportViewModel.reset()
                        snackbarHostState.showSnackbar(exportFailureMessage)
                    }
                }
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
                // Off the main thread -- see episodesLauncher above.
                coroutineScope.launch {
                    if (withContext(Dispatchers.IO) { writeCsvToUri(context, uri, bundle.libraryCsv) }) {
                        // First file written; immediately prompt for the second file's destination so
                        // every document comes from the exact same generated snapshot.
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
}
