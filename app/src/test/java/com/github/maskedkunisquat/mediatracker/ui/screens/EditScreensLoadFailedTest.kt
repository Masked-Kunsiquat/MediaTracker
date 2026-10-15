package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hub.media.ui.EditBookUiState
import com.hub.media.ui.EditMovieUiState
import com.hub.media.ui.EditTVShowUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The three edit screens when their item could not be read (#173 review): a message with Retry and
 * Go back, in place of the spinner a failed load used to leave up forever -- or the crash it used
 * to cause. Robolectric, so it runs in the required `:app:testDebugUnitTest` gate. Each screen is
 * asserted separately because each wires its own callbacks.
 */
@RunWith(RobolectricTestRunner::class)
class EditScreensLoadFailedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var retries = 0
    private var backs = 0

    @Test
    fun editShow_loadFailed_offersRetryAndGoBack() = assertWired { EditShow() }

    @Test
    fun editFilm_loadFailed_offersRetryAndGoBack() = assertWired { EditFilm() }

    @Test
    fun editBook_loadFailed_offersRetryAndGoBack() = assertWired { EditBook() }

    private fun assertWired(screen: @Composable () -> Unit) {
        composeRule.setContent { screen() }

        composeRule.onNodeWithText("Couldn't load this item.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText("Go back").performClick()

        assertEquals(1, retries)
        assertEquals(1, backs)
    }

    @Composable
    private fun EditShow() =
        EditTVShowScreen(
            uiState = EditTVShowUiState.LoadFailed,
            onTitleChange = {},
            onReleaseYearChange = {},
            onTotalSeasonsChange = {},
            onPurchasePriceChange = {},
            onSave = {},
            onErrorShown = {},
            onRetry = { retries++ },
            onNavigateBack = { backs++ },
        )

    @Composable
    private fun EditFilm() =
        EditMovieScreen(
            uiState = EditMovieUiState.LoadFailed,
            onTitleChange = {},
            onReleaseYearChange = {},
            onRuntimeChange = {},
            onPurchasePriceChange = {},
            onStatusChange = {},
            onSave = {},
            onErrorShown = {},
            onRetry = { retries++ },
            onNavigateBack = { backs++ },
        )

    @Composable
    private fun EditBook() =
        EditBookScreen(
            coverStorageDir = "",
            uiState = EditBookUiState.LoadFailed,
            onNavigateBack = { backs++ },
            onRetry = { retries++ },
            onSave = { _, _, _, _, _, _, _ -> },
        )
}
