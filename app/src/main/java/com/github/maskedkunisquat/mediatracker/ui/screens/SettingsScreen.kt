package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.SettingsViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.features.settings.data.WeekStartDay
import com.hub.media.ui.AppContainer
import com.hub.media.ui.SettingsUiState
import com.hub.media.ui.SettingsViewModel

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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScreen(
        uiState = uiState,
        onWeekStartDayChange = viewModel::setWeekStartDay,
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
 * @param onNavigateBack Called when the back icon is pressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onWeekStartDayChange: (WeekStartDay) -> Unit,
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

/** Preview of the Settings screen with the default (Monday) week-start-day selected. */
@Preview(showBackground = true)
@Composable
private fun SettingsScreenMondayPreview() {
    MediaTrackerTheme {
        SettingsScreen(
            uiState = SettingsUiState(weekStartDay = WeekStartDay.MONDAY),
            onWeekStartDayChange = {},
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
            onNavigateBack = {},
        )
    }
}
