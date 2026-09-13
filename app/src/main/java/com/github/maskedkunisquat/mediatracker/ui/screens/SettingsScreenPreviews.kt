package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.features.portability.domain.DuplicatePolicy
import com.hub.media.features.portability.domain.StagedRestoreInfo
import com.hub.media.features.settings.data.WeekStartDay
import com.hub.media.ui.BackfillUiState
import com.hub.media.ui.SettingsUiState

/*
 * Previews for the Settings screen and its restore confirmation dialog, lifted out of
 * `SettingsScreen.kt` (#81), following the same split `BookDetailScreenPreviews.kt` already made.
 *
 * Previews are the cheapest thing a large screen file can shed: they are leaves, nothing calls
 * them, and each one is a wall of `{}` callbacks whose bulk is out of all proportion to what it
 * says. Moving them takes a quarter of the file with it and leaves the screen's actual behaviour
 * easier to read through.
 */

/** Preview of the Settings screen with the default (Monday) week-start-day selected. */
@Preview(showBackground = true)
@Composable
private fun SettingsScreenMondayPreview() {
    MediaTrackerTheme {
        SettingsScreen(
            uiState = SettingsUiState(weekStartDay = WeekStartDay.MONDAY),
            onWeekStartDayChange = {},
            onLogVerbosityChange = {},
            onGoogleBooksApiKeySave = {},
            onGoogleBooksApiKeyClear = {},
            onTmdbCredentialSave = {},
            onTmdbCredentialClear = {},
            onTmdbCredentialTest = {},
            onNavigateToLogViewer = {},
            onNavigateToChangelog = {},
            onNavigateToAbout = {},
            exportInProgress = false,
            onExportClick = {},
            importInProgress = false,
            duplicatePolicy = DuplicatePolicy.SKIP,
            onDuplicatePolicyChange = {},
            onImportClick = {},
            goodreadsDuplicatePolicy = DuplicatePolicy.SKIP,
            onGoodreadsDuplicatePolicyChange = {},
            onImportGoodreadsClick = {},
            backupInProgress = false,
            onBackupClick = {},
            restoreInProgress = false,
            onRestoreClick = {},
            backfillUiState = BackfillUiState.Idle,
            onStartBackfillClick = {},
            onCancelBackfillClick = {},
            tmdbBackfillUiState = BackfillUiState.Idle,
            onStartTmdbBackfillClick = {},
            onCancelTmdbBackfillClick = {},
            mismatchedShows = 0,
            onReviewMismatchesClick = {},
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
            onLogVerbosityChange = {},
            onGoogleBooksApiKeySave = {},
            onGoogleBooksApiKeyClear = {},
            onTmdbCredentialSave = {},
            onTmdbCredentialClear = {},
            onTmdbCredentialTest = {},
            onNavigateToLogViewer = {},
            onNavigateToChangelog = {},
            onNavigateToAbout = {},
            exportInProgress = false,
            onExportClick = {},
            importInProgress = false,
            duplicatePolicy = DuplicatePolicy.SKIP,
            onDuplicatePolicyChange = {},
            onImportClick = {},
            goodreadsDuplicatePolicy = DuplicatePolicy.SKIP,
            onGoodreadsDuplicatePolicyChange = {},
            onImportGoodreadsClick = {},
            backupInProgress = false,
            onBackupClick = {},
            restoreInProgress = false,
            onRestoreClick = {},
            backfillUiState = BackfillUiState.Idle,
            onStartBackfillClick = {},
            onCancelBackfillClick = {},
            tmdbBackfillUiState = BackfillUiState.Idle,
            onStartTmdbBackfillClick = {},
            onCancelTmdbBackfillClick = {},
            mismatchedShows = 0,
            onReviewMismatchesClick = {},
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
            onLogVerbosityChange = {},
            onGoogleBooksApiKeySave = {},
            onGoogleBooksApiKeyClear = {},
            onTmdbCredentialSave = {},
            onTmdbCredentialClear = {},
            onTmdbCredentialTest = {},
            onNavigateToLogViewer = {},
            onNavigateToChangelog = {},
            onNavigateToAbout = {},
            exportInProgress = true,
            onExportClick = {},
            importInProgress = false,
            duplicatePolicy = DuplicatePolicy.SKIP,
            onDuplicatePolicyChange = {},
            onImportClick = {},
            goodreadsDuplicatePolicy = DuplicatePolicy.SKIP,
            onGoodreadsDuplicatePolicyChange = {},
            onImportGoodreadsClick = {},
            backupInProgress = false,
            onBackupClick = {},
            restoreInProgress = false,
            onRestoreClick = {},
            backfillUiState = BackfillUiState.Idle,
            onStartBackfillClick = {},
            onCancelBackfillClick = {},
            tmdbBackfillUiState = BackfillUiState.Idle,
            onStartTmdbBackfillClick = {},
            onCancelTmdbBackfillClick = {},
            mismatchedShows = 0,
            onReviewMismatchesClick = {},
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
        )
    }
}

/** Preview of the Settings screen mid-backup (progress indicator on the backup button). */
@Preview(showBackground = true)
@Composable
private fun SettingsScreenBackingUpPreview() {
    MediaTrackerTheme {
        SettingsScreen(
            uiState = SettingsUiState(weekStartDay = WeekStartDay.MONDAY),
            onWeekStartDayChange = {},
            onLogVerbosityChange = {},
            onGoogleBooksApiKeySave = {},
            onGoogleBooksApiKeyClear = {},
            onTmdbCredentialSave = {},
            onTmdbCredentialClear = {},
            onTmdbCredentialTest = {},
            onNavigateToLogViewer = {},
            onNavigateToChangelog = {},
            onNavigateToAbout = {},
            exportInProgress = false,
            onExportClick = {},
            importInProgress = false,
            duplicatePolicy = DuplicatePolicy.SKIP,
            onDuplicatePolicyChange = {},
            onImportClick = {},
            goodreadsDuplicatePolicy = DuplicatePolicy.SKIP,
            onGoodreadsDuplicatePolicyChange = {},
            onImportGoodreadsClick = {},
            backupInProgress = true,
            onBackupClick = {},
            restoreInProgress = false,
            onRestoreClick = {},
            backfillUiState = BackfillUiState.Idle,
            onStartBackfillClick = {},
            onCancelBackfillClick = {},
            tmdbBackfillUiState = BackfillUiState.Idle,
            onStartTmdbBackfillClick = {},
            onCancelTmdbBackfillClick = {},
            mismatchedShows = 0,
            onReviewMismatchesClick = {},
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
        )
    }
}

/** Preview of the Settings screen while a picked restore candidate is being validated. */
@Preview(showBackground = true)
@Composable
private fun SettingsScreenValidatingRestorePreview() {
    MediaTrackerTheme {
        SettingsScreen(
            uiState = SettingsUiState(weekStartDay = WeekStartDay.MONDAY),
            onWeekStartDayChange = {},
            onLogVerbosityChange = {},
            onGoogleBooksApiKeySave = {},
            onGoogleBooksApiKeyClear = {},
            onTmdbCredentialSave = {},
            onTmdbCredentialClear = {},
            onTmdbCredentialTest = {},
            onNavigateToLogViewer = {},
            onNavigateToChangelog = {},
            onNavigateToAbout = {},
            exportInProgress = false,
            onExportClick = {},
            importInProgress = false,
            duplicatePolicy = DuplicatePolicy.SKIP,
            onDuplicatePolicyChange = {},
            onImportClick = {},
            goodreadsDuplicatePolicy = DuplicatePolicy.SKIP,
            onGoodreadsDuplicatePolicyChange = {},
            onImportGoodreadsClick = {},
            backupInProgress = false,
            onBackupClick = {},
            restoreInProgress = true,
            onRestoreClick = {},
            backfillUiState = BackfillUiState.Idle,
            onStartBackfillClick = {},
            onCancelBackfillClick = {},
            tmdbBackfillUiState = BackfillUiState.Idle,
            onStartTmdbBackfillClick = {},
            onCancelTmdbBackfillClick = {},
            mismatchedShows = 0,
            onReviewMismatchesClick = {},
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
        )
    }
}

/** Preview of the destructive restore confirmation dialog, for a backup at the current schema version. */
@Preview(showBackground = true)
@Composable
private fun RestoreConfirmationDialogPreview() {
    MediaTrackerTheme {
        RestoreConfirmationDialog(
            info =
                StagedRestoreInfo(
                    stagedFilePath = "/data/user/0/com.github.maskedkunisquat.mediatracker/cache/restore-incoming.tmp",
                    schemaVersionFound = 4,
                    isOlderSchemaVersion = false,
                ),
            credentialsWillBeCleared = false,
            onConfirm = {},
            onCancel = {},
        )
    }
}

/** Preview of the destructive restore confirmation dialog, for a backup from an older schema version. */
@Preview(showBackground = true)
@Composable
private fun RestoreConfirmationDialogOlderVersionPreview() {
    MediaTrackerTheme {
        RestoreConfirmationDialog(
            info =
                StagedRestoreInfo(
                    stagedFilePath = "/data/user/0/com.github.maskedkunisquat.mediatracker/cache/restore-incoming.tmp",
                    schemaVersionFound = 2,
                    isOlderSchemaVersion = true,
                ),
            credentialsWillBeCleared = true,
            onConfirm = {},
            onCancel = {},
        )
    }
}
