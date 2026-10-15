package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.dao.UpNextRow
import com.hub.media.ui.UpNextUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The stateless [UpNextScreen] (#89) with fabricated state and fake callbacks: proves the card and
 * its check button each reach the right callback, and that the empty state renders.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
class UpNextScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val fleabag =
        UpNextRow(
            mediaId = "show-1",
            showTitle = "Fleabag",
            coverImageHash = null,
            episodeId = "ep-4",
            seasonNumber = 1,
            episodeNumber = 4,
            episodeTitle = null,
            lastWatchedAt = null,
            remaining = 3,
        )

    private fun setContent(
        uiState: UpNextUiState,
        onOpenShow: (String) -> Unit = {},
        onMarkWatched: (UpNextRow) -> Unit = {},
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                UpNextScreen(
                    uiState = uiState,
                    coverStorageDir = "unused",
                    snackbarHostState = SnackbarHostState(),
                    onOpenShow = onOpenShow,
                    onMarkWatched = onMarkWatched,
                    onNavigateBack = {},
                )
            }
        }
    }

    @Test
    fun card_showsTheNextEpisode_withAnUntitledFallback() {
        setContent(UpNextUiState(items = listOf(fleabag), isLoading = false))

        composeRule.onNodeWithText("Fleabag").assertIsDisplayed()
        composeRule.onNodeWithText("S1 · E4 · Episode 4").assertIsDisplayed()
        composeRule.onNodeWithText("3 left").assertIsDisplayed()
    }

    @Test
    fun checkButton_marksThatEpisode_andCardTap_opensTheShow() {
        val marked = mutableListOf<UpNextRow>()
        val opened = mutableListOf<String>()
        setContent(
            UpNextUiState(items = listOf(fleabag), isLoading = false),
            onOpenShow = { opened += it },
            onMarkWatched = { marked += it },
        )

        composeRule.onNodeWithContentDescription("Mark Fleabag S1 E4 watched").performClick()
        assertEquals(listOf(fleabag), marked)
        assertEquals("the check must not also open the show", emptyList<String>(), opened)

        composeRule.onNodeWithText("Fleabag").performClick()
        assertEquals(listOf("show-1"), opened)
    }

    @Test
    fun emptyQueue_saysSo() {
        setContent(UpNextUiState(items = emptyList(), isLoading = false))
        composeRule.onNodeWithText("Nothing up next", substring = true).assertIsDisplayed()
    }
}
