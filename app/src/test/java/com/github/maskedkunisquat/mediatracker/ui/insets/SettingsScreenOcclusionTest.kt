package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.screens.SettingsScreen
import com.hub.media.features.portability.domain.DuplicatePolicy
import com.hub.media.ui.BackfillUiState
import com.hub.media.ui.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Occlusion guards for the Settings screen.
 *
 * The only text-entry control on this screen is the Google Books API key field
 * (`GoogleBooksApiKeySetting`), which -- like every other row here -- is an item inside the
 * screen's single top-level `LazyColumn`. The rows are measured with that list scrolled to its
 * end, which is the highest the last setting can travel -- under the keyboard there means out of
 * reach permanently. Confirmed by deleting `contentWindowInsets` from this screen and watching this
 * fail.
 *
 * [SettingsUiState]'s defaults already render every section -- there is no empty/loading variant to
 * avoid the way [AddBookScreenOcclusionTest] avoids an empty search result list -- so no
 * particular field population is needed to make the layout meaningful.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withTheKeyboardUp_theSettingsListStaysReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheKeyboard(expectedTags = TAGS) { Fixture() }
    }

    /**
     * The navigation bar half, added with #99.
     *
     * This screen's `LazyColumn` carries `imePadding()` outside its `contentPadding` and the bars
     * as part of that same `scrollingContentPadding` -- one container, two insets wired
     * separately. Deleting the bar's share of that padding leaves the keyboard test above green
     * (the IME rule reports no bar inset at all) and fails this one instead.
     */
    @Test
    fun withTheNavigationBarShowing_theSettingsListStaysReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheNavigationBar(expectedTags = TAGS) { Fixture() }
    }

    @Composable
    private fun Fixture() {
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

    private companion object {
        /** Named here rather than centrally so a dropped tag fails the screen that owns it. */
        val TAGS =
            listOf(
                TestTags.Settings.LIST,
                TestTags.Settings.API_KEY_FIELD,
            )
    }
}
