package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.screens.EditTVShowScreen
import com.hub.media.ui.EditTVShowUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Occlusion guard for the Edit Show form (#173), shaped like [EditMovieScreenOcclusionTest] and
 * for the same reasons: [EditTVShowUiState.Editing] with every field filled (Loading renders no
 * fields and would pass regardless), measured at the end of the one scroll container, and no
 * navigation-bar test because this short form never reaches the bar.
 *
 * Rendered on a **landscape** phone (891x411dp), not this lane's default portrait one. In portrait
 * the four fields and Save end at about 477dp -- above a 300dp keyboard on an 891dp screen even at
 * font scale 2 -- so the test passed with both `contentWindowInsets` and `imePadding` deleted and
 * could not fail. Landscape leaves about 110dp above the keyboard, which is where this form really
 * can be covered. Confirmed by deleting the inset handling and watching this fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w891dp-h411dp-xxhdpi")
class EditTVShowScreenOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withTheKeyboardUp_theFormStaysReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheKeyboard(expectedTags = TAGS) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        EditTVShowScreen(
            coverStorageDir = "",
            uiState =
                EditTVShowUiState.Editing(
                    title = "Chernobyl",
                    releaseYear = "2019",
                    totalSeasons = "1",
                    purchasePrice = "19.99",
                ),
            onTitleChange = {},
            onReleaseYearChange = {},
            onTotalSeasonsChange = {},
            onPurchasePriceChange = {},
            onSave = {},
            onErrorShown = {},
            onRetry = {},
            onNavigateBack = {},
        )
    }

    private companion object {
        /** Named here rather than centrally so a dropped tag fails the screen that owns it. */
        val TAGS =
            listOf(
                TestTags.EditTVShow.FORM,
                TestTags.EditTVShow.SAVE_BUTTON,
                TestTags.EditTVShow.CANCEL_BUTTON,
            )
    }
}
