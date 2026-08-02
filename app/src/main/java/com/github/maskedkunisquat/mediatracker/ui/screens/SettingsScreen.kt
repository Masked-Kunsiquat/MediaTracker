package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import com.github.maskedkunisquat.mediatracker.export.readCsvFromUri
import com.github.maskedkunisquat.mediatracker.export.writeCsvToUri
import com.github.maskedkunisquat.mediatracker.ui.ExportViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.ImportViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.SettingsViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.features.portability.domain.CsvExportBundle
import com.hub.media.features.portability.domain.DuplicatePolicy
import com.hub.media.features.portability.domain.ImportRejection
import com.hub.media.features.portability.domain.ImportSummary
import com.hub.media.features.settings.data.WeekStartDay
import com.hub.media.ui.AppContainer
import com.hub.media.ui.ExportUiState
import com.hub.media.ui.ExportViewModel
import com.hub.media.ui.ImportUiState
import com.hub.media.ui.ImportViewModel
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
    val importViewModel: ImportViewModel = viewModel(
        factory = ImportViewModelFactory(appContainer),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val exportUiState by exportViewModel.uiState.collectAsStateWithLifecycle()
    val importUiState by importViewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val exportSuccessMessage = stringResource(R.string.export_success_message)
    val exportFailureMessage = stringResource(R.string.export_failure_message)
    val exportCancelledMessage = stringResource(R.string.export_cancelled_message)
    val importCancelledMessage = stringResource(R.string.import_cancelled_message)
    val importFailureMessage = stringResource(R.string.import_failure_message)

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

    val readingLogsImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val libraryCsv = pendingLibraryCsvForImport
        pendingLibraryCsvForImport = null
        val readingLogsCsv = uri?.let { readCsvFromUri(context, it) }
        importViewModel.importData(libraryCsv, readingLogsCsv, duplicatePolicy)
    }

    val libraryImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            coroutineScope.launch { snackbarHostState.showSnackbar(importCancelledMessage) }
            return@rememberLauncherForActivityResult
        }
        val content = readCsvFromUri(context, uri)
        if (content == null) {
            coroutineScope.launch { snackbarHostState.showSnackbar(importFailureMessage) }
            return@rememberLauncherForActivityResult
        }
        pendingLibraryCsvForImport = content
        readingLogsImportLauncher.launch(arrayOf("text/*"))
    }

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
        ImportSummaryDialog(summary = state.summary, onDismiss = importViewModel::reset)
    }

    SettingsScreen(
        uiState = uiState,
        onWeekStartDayChange = viewModel::setWeekStartDay,
        exportInProgress = exportUiState is ExportUiState.Loading,
        onExportClick = exportViewModel::exportData,
        importInProgress = importUiState is ImportUiState.Loading,
        duplicatePolicy = duplicatePolicy,
        onDuplicatePolicyChange = { duplicatePolicy = it },
        onImportClick = { libraryImportLauncher.launch(arrayOf("text/*")) },
        snackbarHostState = snackbarHostState,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * Full import-result summary (ROADMAP Task 8 Phase B): per-file counts for every duplicate-policy
 * outcome, plus every rejected row's reason (scrollable, since a large messy import could reject
 * many rows) -- see [ImportSummary]'s KDoc for why a bare "done" isn't acceptable here.
 */
@Composable
private fun ImportSummaryDialog(summary: ImportSummary, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.ok_button)) }
        },
        title = { Text(stringResource(R.string.import_summary_title)) },
        text = {
            Column(
                modifier = Modifier
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
                            text = stringResource(
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
 * @param onNavigateBack Called when the back icon is pressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onWeekStartDayChange: (WeekStartDay) -> Unit,
    exportInProgress: Boolean,
    onExportClick: () -> Unit,
    importInProgress: Boolean,
    duplicatePolicy: DuplicatePolicy,
    onDuplicatePolicyChange: (DuplicatePolicy) -> Unit,
    onImportClick: () -> Unit,
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
                        HorizontalDivider()
                        ImportDataSetting(
                            importInProgress = importInProgress,
                            duplicatePolicy = duplicatePolicy,
                            onDuplicatePolicyChange = onDuplicatePolicyChange,
                            onImportClick = onImportClick,
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
            importInProgress = false,
            duplicatePolicy = DuplicatePolicy.SKIP,
            onDuplicatePolicyChange = {},
            onImportClick = {},
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
            importInProgress = false,
            duplicatePolicy = DuplicatePolicy.SKIP,
            onDuplicatePolicyChange = {},
            onImportClick = {},
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
            importInProgress = false,
            duplicatePolicy = DuplicatePolicy.SKIP,
            onDuplicatePolicyChange = {},
            onImportClick = {},
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
        )
    }
}
