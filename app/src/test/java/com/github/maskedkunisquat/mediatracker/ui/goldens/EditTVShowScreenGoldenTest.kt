package com.github.maskedkunisquat.mediatracker.ui.goldens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.screens.EditTVShowScreen
import com.hub.media.ui.EditTVShowUiState
import com.hub.media.ui.ShowFormState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Golden for edit TV show (#174): the header, the Details and Purchase cards and the pinned
 * Cancel/Save bar. Deliberately no Status card -- abandoning a show is the detail screen's toggle --
 * so this is the one edit screen whose form is two cards, and the image is where a stray third one
 * would show. No cover, for the reason [EditMovieScreenGoldenTest] gives.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditTVShowScreenGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editTvShow() {
        composeRule.captureGolden(
            name = "edit-tv",
            alsoAssert = {
                assertTagsExist(
                    TestTags.EditTVShow.FORM,
                    TestTags.EditTVShow.SAVE_BUTTON,
                    TestTags.EditTVShow.CANCEL_BUTTON,
                )
            },
        ) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        EditTVShowScreen(
            uiState =
                EditTVShowUiState.Editing(
                    form =
                        ShowFormState(
                            title = "Chernobyl",
                            releaseYear = "2019",
                            totalSeasons = "1",
                            purchasePrice = "19.99",
                        ),
                    savedYear = 2019,
                ),
            coverStorageDir = "",
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
}
