package com.github.maskedkunisquat.mediatracker.ui.goldens

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
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline golden for settings.
 *
 * The screen with the most sections and therefore the most spacing to get wrong: #99 moved its
 * padding off the `Box` and onto the `LazyColumn`'s `contentPadding` so the list draws behind the
 * bars, while the API key field still has to lift above the keyboard. The occlusion lane asserts
 * the second half; only a picture shows whether the first left the section headers evenly spaced.
 *
 * Light only, per the variant policy on [LibraryScreenGoldenTest] — dark and large-font are
 * recorded for the library alone, so a review carries ten images rather than twenty-four.
 *
 * Paired with a tag assertion (#102 rule 1), falsified by dropping `TestTags.Settings.LIST`, which
 * fails on the assertion rather than on the image.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settings() {
        composeRule.captureGolden(
            name = "settings",
            alsoAssert = {
                assertTagsExist(
                    TestTags.Settings.LIST,
                    TestTags.Settings.API_KEY_FIELD,
                    // Both credential rows, not just the first: they are two calls to one
                    // generalised composable (#75), so a wiring mistake that drops one would
                    // leave the other rendering perfectly and this assertion is what notices.
                    TestTags.Settings.TMDB_KEY_FIELD,
                )
            },
        ) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        SettingsScreen(
            uiState = SettingsUiState(),
            onWeekStartDayChange = {},
            onLogVerbosityChange = {},
            onGoogleBooksApiKeySave = {},
            onGoogleBooksApiKeyClear = {},
            onTmdbCredentialSave = {},
            onTmdbCredentialClear = {},
            onTmdbCredentialTest = {},
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
