package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.features.portability.domain.StagedRestoreInfo

/*
 * The Backup & restore section of the Settings screen, and the confirmation dialog that guards the
 * destructive half of it, lifted out of `SettingsScreen.kt` (#81) as the fifth cut.
 *
 * The dialog travels with the rows rather than staying with the route that shows it: it exists only
 * to confirm this section's one destructive action, and the reasoning in the two KDocs is a single
 * argument split across them. `RestoreConfirmationDialog` stays `internal` because the route and the
 * previews both reach it.
 */

/**
 * The Backup & restore section: save a copy of everything, or replace everything from a copy.
 *
 * A separate section, not another row in the "Data" card above it -- ROADMAP Task 8 Phase C's brief
 * calls for backup and restore to be "clearly separated by risk" from CSV export/import and from
 * each other; a whole-database restore is destructive in a way the CSV importer's `DuplicatePolicy`
 * (SKIP/MERGE always preserve existing rows) never is.
 *
 * @param backupInProgress Whether a backup is currently being written.
 * @param onBackupClick Starts a backup.
 * @param restoreInProgress Whether a restore is currently being validated or applied.
 * @param onRestoreClick Starts the restore flow, which leads to [RestoreConfirmationDialog].
 */
@Composable
internal fun BackupRestoreSection(
    backupInProgress: Boolean,
    onBackupClick: () -> Unit,
    restoreInProgress: Boolean,
    onRestoreClick: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_section_backup_restore)) {
        BackupDataSetting(
            backupInProgress = backupInProgress,
            onBackupClick = onBackupClick,
        )
        HorizontalDivider()
        RestoreDataSetting(
            restoreInProgress = restoreInProgress,
            onRestoreClick = onRestoreClick,
        )
    }
}

/**
 * The restore destructive-action confirmation (ROADMAP Task 8 Phase C task brief: "Require an
 * explicit, unambiguous confirmation that states what will be lost. Do not make it a single tap
 * next to the export button.") -- a dedicated modal dialog, reached only after the picked file has
 * already passed non-destructive header/version validation (so this dialog never appears for a
 * file that turns out to be unusable), requiring an explicit checkbox acknowledgement before the
 * destructive confirm button becomes enabled, with that button styled in the theme's `error` color
 * to read as visually distinct from every other action on this screen.
 *
 * @param credentialsWillBeCleared Mirrors
 *   [com.hub.media.ui.RestoreUiState.AwaitingConfirmation.credentialsWillBeCleared] -- when true,
 *   an extra sentence warns that the provider keys the user has entered will need to be entered
 *   again afterward, since backups never carry them (see that property's KDoc for why). It does
 *   not name which: the warning is driven off the credential list so it stays true as providers
 *   are added, and the user's next step is the same either way.
 * @param commitInProgress True once Confirm has been tapped. Both buttons disable, since the dialog
 *   stays up until the process restarts and a second tap must not start a second commit.
 */
@Composable
internal fun RestoreConfirmationDialog(
    info: StagedRestoreInfo,
    credentialsWillBeCleared: Boolean,
    commitInProgress: Boolean = false,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var understood by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.restore_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.restore_confirm_message))
                if (info.isOlderSchemaVersion) {
                    Text(
                        text = stringResource(R.string.restore_confirm_message_older_version, info.schemaVersionFound),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (credentialsWillBeCleared) {
                    Text(
                        text = stringResource(R.string.restore_confirm_message_credentials),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { understood = !understood },
                ) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text(
                        text = stringResource(R.string.restore_confirm_checkbox_label),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = understood && !commitInProgress,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.restore_confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !commitInProgress) {
                Text(stringResource(R.string.restore_cancel_button))
            }
        },
    )
}

/**
 * The `.sqlite` backup setting row (ROADMAP Task 8 Phase C): a label, a short description, and a
 * single button that produces a complete database snapshot and then prompts (via the route
 * composable's SAF `CreateDocument` launcher) for where to save it. Non-destructive -- unlike
 * [RestoreDataSetting], this never reads anything other than the live database and never writes to
 * it, so it needs no confirmation dialog, matching `ExportDataSetting`'s shape.
 */
@Composable
private fun BackupDataSetting(
    backupInProgress: Boolean,
    onBackupClick: () -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.settings_backup_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_backup_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Button(onClick = onBackupClick, enabled = !backupInProgress) {
            if (backupInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.settings_backup_button))
            }
        }
    }
}

/**
 * The `.sqlite` restore setting row (ROADMAP Task 8 Phase C) -- the single most dangerous action in
 * the app (AGENTS.md §1). Deliberately styled and worded to read as higher-risk than every other
 * row on this screen:
 * - An [OutlinedButton] in the theme's `error` color, not a filled primary [Button] like every
 *   other action here -- visually distinct at a glance, before the user even reads the label.
 * - The description states plainly that this replaces the whole library and cannot be undone,
 *   rather than a neutral "restore your data" framing.
 * - Tapping this button only ever *launches the file picker* -- it never touches the live database
 *   by itself. The actual destructive action requires the picked file to first pass non-destructive
 *   validation, then an explicit checkbox-gated confirmation dialog (see
 *   `RestoreConfirmationDialog`, shown by the route composable), satisfying this phase's brief that
 *   restore must not be "a single tap next to the export button."
 */
@Composable
private fun RestoreDataSetting(
    restoreInProgress: Boolean,
    onRestoreClick: () -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.settings_restore_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_restore_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedButton(
            onClick = onRestoreClick,
            enabled = !restoreInProgress,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        ) {
            if (restoreInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.restore_validating_message),
                    modifier = Modifier.padding(start = 8.dp),
                )
            } else {
                Text(stringResource(R.string.settings_restore_button))
            }
        }
    }
}
