package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.export.writeCsvToUri
import com.github.maskedkunisquat.mediatracker.ui.ExportViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.SettingsViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.features.portability.domain.CsvExportBundle
import com.hub.media.features.settings.data.WeekStartDay
import com.hub.media.ui.AppContainer
import com.hub.media.ui.ExportUiState
import com.hub.media.ui.ExportViewModel
import com.hub.media.ui.SettingsUiState
import com.hub.media.ui.SettingsViewModel
import kotlinx.coroutines.launch

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
) {
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory(appContainer),
    )
    val exportViewModel: ExportViewModel = viewModel(
        factory = ExportViewModelFactory(appContainer),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val exportUiState by exportViewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val exportSuccessMessage = stringResource(R.string.export_success_message)
    val exportFailureMessage = stringResource(R.string.export_failure_message)
    val exportCancelledMessage = stringResource(R.string.export_cancelled_message)

    // Holds the generated bundle between the two sequential SAF "create document" picks below --
    // see SettingsScreen.kt's class-level export section KDoc for why both files are written from
    // one cached bundle rather than two independent ExportDataUseCase runs.
    var pendingBundle by remember { mutableStateOf<CsvExportBundle?>(null) }

    val readingLogsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val bundle = pendingBundle
        pendingBundle = null
        exportViewModel.reset()
        coroutineScope.launch {
            val message = when {
                uri == null -> exportCancelledMessage
                bundle == null -> exportFailureMessage
                writeCsvToUri(context, uri, bundle.readingLogsCsv) -> exportSuccessMessage
                else -> exportFailureMessage
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    val libraryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val bundle = pendingBundle
        if (uri == null || bundle == null) {
            pendingBundle = null
            exportViewModel.reset()
            coroutineScope.launch { snackbarHostState.showSnackbar(exportCancelledMessage) }
        } else if (writeCsvToUri(context, uri, bundle.libraryCsv)) {
            // First file written; immediately prompt for the second file's destination so both
            // documents come from the exact same generated snapshot.
            readingLogsLauncher.launch("reading_logs_export.csv")
        } else {
            pendingBundle = null
            exportViewModel.reset()
            coroutineScope.launch { snackbarHostState.showSnackbar(exportFailureMessage) }
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

    SettingsScreen(
        uiState = uiState,
        onWeekStartDayChange = viewModel::setWeekStartDay,
        exportInProgress = exportUiState is ExportUiState.Loading,
        onExportClick = exportViewModel::exportData,
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
 * @param exportInProgress Whether a CSV export is currently being generated (ROADMAP Task 8 Phase
 *   A) -- wired to `ExportUiState.Loading`, disables the export button and shows a progress
 *   indicator so a double-tap can't fire two concurrent exports.
 * @param onExportClick Called when the export button is tapped, wired to
 *   `ExportViewModel.exportData`. The actual SAF file-picker/write sequence happens in the route
 *   composable, not here -- this stateless screen only ever emits the request.
 * @param snackbarHostState Hosts the success/failure/cancelled Snackbar the route composable shows
 *   once the export (and subsequent SAF writes) finish -- a silently failed export would be worse
 *   than no export button at all (this phase's task brief).
 * @param onNavigateBack Called when the back icon is pressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onWeekStartDayChange: (WeekStartDay) -> Unit,
    exportInProgress: Boolean,
    onExportClick: () -> Unit,
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
            modifier = Modifier
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
                    SettingsSection(title = stringResource(R.string.settings_section_data)) {
                        ExportDataSetting(
                            exportInProgress = exportInProgress,
                            onExportClick = onExportClick,
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
                modifier = Modifier
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

/** Preview of the Settings screen with the default (Monday) week-start-day selected. */
@Preview(showBackground = true)
@Composable
private fun SettingsScreenMondayPreview() {
    MediaTrackerTheme {
        SettingsScreen(
            uiState = SettingsUiState(weekStartDay = WeekStartDay.MONDAY),
            onWeekStartDayChange = {},
            exportInProgress = false,
            onExportClick = {},
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
            exportInProgress = false,
            onExportClick = {},
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
            exportInProgress = true,
            onExportClick = {},
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
        )
    }
}
