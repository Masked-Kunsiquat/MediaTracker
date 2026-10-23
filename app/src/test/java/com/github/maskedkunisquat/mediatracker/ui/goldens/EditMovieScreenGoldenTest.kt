package com.github.maskedkunisquat.mediatracker.ui.goldens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.screens.EditMovieScreen
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.ui.EditMovieUiState
import com.hub.media.ui.FilmFormState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Golden for edit film (#174): the header, the Details / Status / Purchase cards and the pinned
 * Cancel/Save bar -- Edit Book's arrangement, which this screen did not have before. What only the
 * image shows is that the three edit screens now look like one family: the same card shading, the
 * same 16dp margins, the status chip drawn as on the detail screen. No cover, because the fixture
 * has no file to draw; the header's no-artwork shape is the one a hand-entered film gets.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditMovieScreenGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editFilm() {
        composeRule.captureGolden(
            name = "edit-film",
            alsoAssert = {
                assertTagsExist(
                    TestTags.EditMovie.FORM,
                    TestTags.EditMovie.SAVE_BUTTON,
                    TestTags.EditMovie.CANCEL_BUTTON,
                )
            },
        ) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        EditMovieScreen(
            uiState =
                EditMovieUiState.Editing(
                    form =
                        FilmFormState(
                            title = "Interstellar",
                            releaseYear = "2014",
                            runtimeMinutes = "169",
                            purchasePrice = "14.99",
                            status = WatchStatus.WATCHLIST,
                        ),
                    savedYear = 2014,
                ),
            coverStorageDir = "",
            onTitleChange = {},
            onReleaseYearChange = {},
            onRuntimeChange = {},
            onPurchasePriceChange = {},
            onStatusChange = {},
            onSave = {},
            onErrorShown = {},
            onRetry = {},
            onNavigateBack = {},
        )
    }
}
