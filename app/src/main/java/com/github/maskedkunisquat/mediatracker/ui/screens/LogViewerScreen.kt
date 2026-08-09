package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.export.writeCsvToUri
import com.github.maskedkunisquat.mediatracker.ui.LogViewerViewModelFactory
import com.hub.media.core.storage.LogEntry
import com.hub.media.ui.AppContainer
import com.hub.media.ui.LogViewerUiState
import com.hub.media.ui.LogViewerViewModel
import kotlinx.coroutines.launch

/**
 * Route wrapper for the in-app log viewer (ROADMAP Task 15 Phase B2), owning the
 * [LogViewerViewModel] and the SAF launcher for "export full log".
 *
 * The export reuses [writeCsvToUri] despite the name: it writes an arbitrary [String] to a
 * user-picked [android.net.Uri], which is exactly what is needed here. Renaming it is deliberately
 * left alone rather than widened into this change -- the log content itself is produced by
 * [LogViewerViewModel.readFullLogForExport] in `shared/`, keeping the platform file picker on this
 * side of the boundary exactly as the CSV export already does.
 */
@Composable
fun LogViewerScreenRoute(
    appContainer: AppContainer,
    onNavigateBack: () -> Unit,
) {
    val viewModel: LogViewerViewModel = viewModel(
        factory = remember(appContainer) { LogViewerViewModelFactory(appContainer) },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val exportSuccess = stringResource(R.string.log_viewer_export_success)
    val exportFailed = stringResource(R.string.log_viewer_export_failed)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val content = viewModel.readFullLogForExport()
            val ok = writeCsvToUri(context, uri, content)
            snackbarHostState.showSnackbar(if (ok) exportSuccess else exportFailed)
        }
    }

    LogViewerScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onRefresh = viewModel::refresh,
        onExport = { exportLauncher.launch("mediatracker_log.txt") },
        onNavigateBack = onNavigateBack,
    )
}

/**
 * The log viewer itself (ROADMAP Task 15 Phase B2).
 *
 * ### A scrollable [Column], deliberately not a `LazyColumn`
 * Every other list in this app is lazy. This one must not be, and the reason is the whole point of
 * the screen: text selection across a `LazyColumn` breaks as items recycle, so a user dragging
 * across more entries than fit on screen would lose the selection. Since the requirement here is
 * genuinely selectable text rather than a copy-everything button, the entries are rendered eagerly
 * inside a [SelectionContainer]. That is affordable precisely because
 * [com.hub.media.ui.LOG_VIEWER_ENTRY_LIMIT] bounds the window; everything beyond it is reachable
 * through "export full log" instead, which is why that action exists rather than being a
 * convenience.
 *
 * ### Auto-scroll to the bottom on open
 * Paired with the oldest-first ordering (see
 * [com.hub.media.ui.LOG_ENTRIES_OLDEST_FIRST]), matching a terminal, which parks you at the tail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    uiState: LogViewerUiState,
    snackbarHostState: SnackbarHostState,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val scrollState = rememberScrollState()

    // Jump to the newest entry whenever the snapshot changes -- on open, and again after each
    // refresh brings new entries in. Keyed on the entry count rather than the list itself so a
    // refresh that returned nothing new does not yank a user's scroll position out from under them.
    LaunchedEffect(uiState.entries.size) {
        if (uiState.entries.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.log_viewer_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onRefresh, enabled = !uiState.isLoading) {
                        Text(stringResource(R.string.log_viewer_refresh))
                    }
                    TextButton(onClick = onExport) {
                        Text(stringResource(R.string.log_viewer_export))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                uiState.isLoading && uiState.entries.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                uiState.isEmpty -> {
                    Text(
                        text = stringResource(R.string.log_viewer_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                    )
                }

                else -> {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            uiState.entries.forEachIndexed { index, entry ->
                                if (index == uiState.firstNewEntryIndex) {
                                    NewEntriesDivider()
                                }
                                LogEntryRow(entry)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The "everything below this line is new" marker. Renders *above* the first entry newer than the
 * refresh boundary, which reads correctly only because entries are oldest-first -- see
 * [com.hub.media.ui.LOG_ENTRIES_OLDEST_FIRST].
 */
@Composable
private fun NewEntriesDivider() {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.primary)
        Text(
            text = stringResource(R.string.log_viewer_new_entries_divider),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** One log entry, monospaced so timestamps and levels line up down the column. */
@Composable
private fun LogEntryRow(entry: LogEntry) {
    Text(
        text = "${entry.level.name} ${entry.tag}\n${entry.message}",
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
