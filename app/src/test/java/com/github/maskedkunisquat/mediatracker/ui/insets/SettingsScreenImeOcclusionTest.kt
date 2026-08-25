package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.screens.SettingsScreen
import com.hub.media.features.portability.domain.DuplicatePolicy
import com.hub.media.ui.BackfillUiState
import com.hub.media.ui.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * IME-occlusion guard for the Settings screen.
 *
 * The only text-entry control on this screen is the Google Books API key field
 * (`GoogleBooksApiKeySetting`), which -- like every other row here -- is an item inside the
 * screen's single top-level `LazyColumn`. The individual rows are exempt from the occlusion check,
 * but that `LazyColumn`'s own viewport is not: it cannot be scrolled past its end, so a viewport
 * running under the keyboard puts the last setting out of reach permanently. Confirmed by deleting
 * `contentWindowInsets` from this screen and watching this fail.
 *
 * [SettingsUiState]'s defaults already render every section -- there is no empty/loading variant to
 * avoid the way [AddBookScreenImeOcclusionTest] avoids an empty search result list -- so no
 * particular field population is needed to make the layout meaningful.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenImeOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withTheKeyboardUp_theSettingsListStaysReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheKeyboard {
            SettingsScreen(
                uiState = SettingsUiState(),
                onWeekStartDayChange = {},
                onLogVerbosityChange = {},
                onGoogleBooksApiKeySave = {},
                onGoogleBooksApiKeyClear = {},
                onNavigateToLogViewer = {},
                onNavigateToChangelog = {},
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
                snackbarHostState = SnackbarHostState(),
                onNavigateBack = {},
            )
        }
    }
}
