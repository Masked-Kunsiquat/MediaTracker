package com.github.maskedkunisquat.mediatracker.ui.screens

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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.insets.scrollingContentPadding
import com.hub.media.core.util.LogLevel
import com.hub.media.features.books.domain.BulkBackfillProgress
import com.hub.media.features.media.domain.TmdbBackfillProgress
import com.hub.media.features.portability.domain.DuplicatePolicy
import com.hub.media.features.settings.data.WeekStartDay
import com.hub.media.ui.BackfillUiState
import com.hub.media.ui.BackfillViewModel
import com.hub.media.ui.ImportUiState
import com.hub.media.ui.SettingsUiState
import com.hub.media.ui.SettingsViewModel

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
