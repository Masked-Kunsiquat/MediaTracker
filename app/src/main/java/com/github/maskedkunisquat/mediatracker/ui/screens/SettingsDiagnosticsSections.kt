package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.core.util.LogLevel

/*
 * The Diagnostics and About sections of the Settings screen, lifted out of `SettingsScreen.kt`
 * (#81) as the third cut.
 *
 * About rides along with Diagnostics rather than getting a file of its own: it is a single
 * nineteen-line row, it sits inside this same contiguous run of the original file, and both
 * sections are the bottom-of-the-screen "tell me about the app" concern rather than anything that
 * changes how it behaves. A file per section would be a file per row here.
 *
 * Only the two `*Section` composables are visible outside this file.
 */

/**
 * The levels offered in "Log detail", most verbose first to match [LogLevel]'s declaration order.
 *
 * [LogLevel.DEBUG] is deliberately absent: there is still not one DEBUG call site in the codebase,
 * so offering it promised a level of detail that behaved identically to Detailed. A value already
 * persisted as DEBUG is left alone rather than rewritten -- it still displays and still works, and
 * silently downgrading a diagnostic setting somebody deliberately turned on would be worse than
 * leaving one unlisted option in place. Add DEBUG back here the moment something logs at it.
 */
private val SELECTABLE_LOG_LEVELS: List<LogLevel> =
    listOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)

/**
 * The Diagnostics section: how much the app logs, and the two ways to read what it logged.
 *
 * @param logVerbosity Current log level, driving the dropdown's selection.
 * @param onLogVerbosityChange Called with the newly chosen level.
 * @param onViewLogClick Opens the log viewer.
 * @param onViewChangelogClick Opens the changelog.
 */
@Composable
internal fun DiagnosticsSection(
    logVerbosity: LogLevel,
    onLogVerbosityChange: (LogLevel) -> Unit,
    onViewLogClick: () -> Unit,
    onViewChangelogClick: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_section_diagnostics)) {
        LogVerbositySetting(
            selected = logVerbosity,
            onSelectedChange = onLogVerbosityChange,
        )
        HorizontalDivider()
        LogViewerSetting(onViewLogClick = onViewLogClick)
        HorizontalDivider()
        ChangelogSetting(onViewChangelogClick = onViewChangelogClick)
    }
}

/**
 * The About section: the single way into the about screen.
 *
 * Its own section, and last, rather than a third row in Diagnostics beside the log viewer and the
 * changelog (#137).
 *
 * `ChangelogSetting`'s KDoc argues that a one-row section is "more chrome than content", and that
 * reasoning is sound for what it covered: two read-only reference screens that belong together. It
 * does not extend here. TMDB's terms require their attribution to live in an "About or Credits type
 * section", so the section heading is part of what satisfies the term -- filing it under Diagnostics
 * would put a licence notice behind a word that means "something has gone wrong", which is both
 * wrong and harder to find.
 *
 * @param onViewAboutClick Opens the about screen.
 */
@Composable
internal fun AboutSection(onViewAboutClick: () -> Unit) {
    SettingsSection(title = stringResource(R.string.settings_section_about)) {
        AboutSetting(onViewAboutClick = onViewAboutClick)
    }
}

/**
 * The log-verbosity setting row (ROADMAP Task 15 Phase B2).
 *
 * A dropdown rather than `WeekStartDaySetting`'s segmented buttons: Material 3 reserves segmented
 * buttons for a small set meant to be compared side by side, and options whose labels are words
 * rather than single tokens would crowd a phone-width row. The order follows [LogLevel]'s own
 * declaration order, most verbose first, so "more detail" reads as down-the-list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogVerbositySetting(
    selected: LogLevel,
    onSelectedChange: (LogLevel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.settings_log_verbosity_label)
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_log_verbosity_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = selected.displayLabel(),
                onValueChange = {},
                readOnly = true,
                label = null,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                // The label is drawn as a separate Text above (matching WeekStartDaySetting's
                // layout), so this field has no Material label of its own and TalkBack would
                // otherwise announce only the bare value -- "Warnings", with no indication of which
                // setting it belongs to. Restating it here as a contentDescription gives screen
                // readers that context without changing the visual layout.
                modifier =
                    Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                        .semantics { contentDescription = label },
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                SELECTABLE_LOG_LEVELS.forEach { level ->
                    DropdownMenuItem(
                        text = { Text(level.displayLabel()) },
                        onClick = {
                            onSelectedChange(level)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * The log-viewer row (ROADMAP Task 15 Phase B2): navigates to the read-only viewer rather than
 * showing entries inline, since that screen needs its own scroll and selection behaviour that a
 * card inside this screen's `LazyColumn` could not provide.
 *
 * The description states the privacy guarantee explicitly. That is deliberate: a user about to
 * share a log with someone should be able to see, at the point of doing it, that it never contained
 * their titles, authors, or notes -- the identifier rule from Phase A is only reassuring if it is
 * visible where the decision is made.
 */

/**
 * The "What's new" row (ROADMAP Task 15 Phase B2b). Sits in Diagnostics beside the log viewer
 * rather than in its own section: both are read-only reference screens reached from here, and a
 * one-row section for each would be more chrome than content.
 */
@Composable
private fun ChangelogSetting(onViewChangelogClick: () -> Unit) {
    Column {
        Text(
            text = stringResource(R.string.settings_changelog_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_changelog_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Button(onClick = onViewChangelogClick) {
            Text(stringResource(R.string.settings_changelog_button))
        }
    }
}

/**
 * The About row (#137). Opens the credits screen where the provider attributions live.
 *
 * The description names what is on the other side rather than saying "about this app", because the
 * one thing a user might come looking for here -- which catalogue their film data came from -- is
 * otherwise invisible from the row.
 */
@Composable
private fun AboutSetting(onViewAboutClick: () -> Unit) {
    Column {
        Text(
            text = stringResource(R.string.settings_about_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_about_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Button(onClick = onViewAboutClick) {
            Text(stringResource(R.string.settings_about_button))
        }
    }
}

@Composable
private fun LogViewerSetting(onViewLogClick: () -> Unit) {
    Column {
        Text(
            text = stringResource(R.string.settings_log_viewer_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_log_viewer_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Button(onClick = onViewLogClick) {
            Text(stringResource(R.string.settings_log_viewer_button))
        }
    }
}
