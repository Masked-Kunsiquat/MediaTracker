package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.screens.AddTVShowScreen
import com.hub.media.ui.AddTVShowUiState
import com.hub.media.ui.SeasonRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Occlusion guards for the Add TV Show screen's form.
 *
 * Unlike [AddMovieScreen][com.github.maskedkunisquat.mediatracker.ui.screens.AddMovieScreen], every
 * field on this screen -- including the season rows -- is bound directly to [AddTVShowUiState]
 * rather than held in local `rememberSaveable` state (see that class's KDoc), so this fabricates a
 * populated state with two season rows rather than the default empty one: an empty season list
 * collapses to a single hint [androidx.compose.material3.Text] with nothing to lay out, which would
 * pass this check regardless of inset handling.
 *
 * As with [AddMovieScreenOcclusionTest], every field, season row, and the save button live in
 * the one `verticalScroll` `Column` that makes up this screen's whole body. That is not an
 * exemption: the harness scrolls that container to its end and measures there, which is as high as
 * the save button can get, so a save button still under the keyboard at that point is gone for
 * good. Confirmed by deleting `contentWindowInsets` from this screen and watching this fail.
 */
@RunWith(RobolectricTestRunner::class)
class AddTVShowScreenOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withTheKeyboardUp_theFormAndSeasonRowsStayReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheKeyboard(expectedTags = TAGS) { Fixture() }
    }

    /**
     * The navigation bar half, added with #99.
     *
     * As with [AddMovieScreenOcclusionTest]'s nav-bar test, this screen routes the keyboard outside
     * the form's scroll via `imePadding()` and the bars inside it via `scrollingContentPadding` on
     * the same `contentPadding` -- separate mechanisms sharing one scroller, so one can regress
     * without the other. Deleting the bar half here leaves the keyboard test green and fails this
     * one.
     */
    @Test
    fun withTheNavigationBarShowing_theFormAndSeasonRowsStayReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheNavigationBar(expectedTags = TAGS) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        AddTVShowScreen(
            uiState =
                AddTVShowUiState(
                    title = "Breaking Bad",
                    releaseYear = "2008",
                    totalSeasons = "5",
                    purchasePrice = "39.99",
                    seasons =
                        listOf(
                            SeasonRow(seasonNumber = "1", episodeCount = "7"),
                            SeasonRow(seasonNumber = "2", episodeCount = "13"),
                        ),
                ),
            onTitleChange = {},
            onReleaseYearChange = {},
            onTotalSeasonsChange = {},
            onPurchasePriceChange = {},
            onAddSeasonRow = {},
            onRemoveSeasonRow = {},
            onSeasonNumberChange = { _, _ -> },
            onEpisodeCountChange = { _, _ -> },
            onSave = {},
            onErrorShown = {},
            onNavigateBack = {},
        )
    }

    private companion object {
        /** Named here rather than centrally so a dropped tag fails the screen that owns it. */
        val TAGS =
            listOf(
                TestTags.AddTVShow.FORM,
                TestTags.AddTVShow.SAVE_BUTTON,
            )
    }
}
