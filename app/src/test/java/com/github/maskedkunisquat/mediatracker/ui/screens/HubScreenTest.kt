package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.ui.HubUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The hub (#126), driven statelessly: its cards, their numbers, and every way out of it. */
@RunWith(RobolectricTestRunner::class)
class HubScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun setContent(
        state: HubUiState = HubUiState(isLoading = false, readingBooks = 3, watchlistFilms = 5, showsUpNext = 1),
    ) {
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                HubScreen(
                    uiState = state,
                    onOpenDomain = { opened += it.name },
                    onOpenAllMedia = { opened += "all" },
                    onOpenStats = { opened += "stats" },
                    onOpenYearReview = { opened += "yearReview" },
                    onOpenSettings = { opened += "settings" },
                )
            }
        }
    }

    @Test
    fun theCards_showTheirNumbers() {
        setContent()

        composeRule.onNodeWithText("3 reading", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("5 on watchlist", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("1 up next", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun zero_isShown_notHidden() {
        setContent(HubUiState(isLoading = false))

        composeRule.onNodeWithText("0 reading", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun whileLoading_theNumbersAreBlank_notZero() {
        setContent(HubUiState())

        // Positive control: the card itself is there.
        composeRule.onNodeWithTag(TestTags.Hub.BOOKS).assertIsDisplayed()
        composeRule.onNodeWithText("0 reading", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun afterAFailedRead_theNumbersAreBlank_notZero() {
        setContent(HubUiState(isLoading = false, loadFailed = true))

        composeRule.onNodeWithTag(TestTags.Hub.FILMS).assertIsDisplayed()
        composeRule.onNodeWithText("0 on watchlist", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun everyTarget_isWired() {
        setContent()

        composeRule.onNodeWithTag(TestTags.Hub.BOOKS).performClick()
        composeRule.onNodeWithTag(TestTags.Hub.FILMS).performClick()
        composeRule.onNodeWithTag(TestTags.Hub.SHOWS).performClick()
        composeRule.onNodeWithTag(TestTags.Hub.ALL_MEDIA).performScrollTo().performClick()
        composeRule.onNodeWithTag(TestTags.Hub.STATS).performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Open settings").performClick()

        assertEquals(listOf("BOOK", "MOVIE", "TV_SHOW", "all", "stats", "settings"), opened)
    }

    @Test
    fun aCard_readsAsOneStop_withItsNumber() {
        setContent()

        val text =
            composeRule
                .onNodeWithTag(TestTags.Hub.BOOKS)
                .fetchSemanticsNode()
                .config
                .getOrElse(SemanticsProperties.Text) { emptyList() }
                .map { it.text }
        assertTrue("merged text was $text", text.containsAll(listOf("Books", "3 reading")))
    }

    @Test
    fun theRows_areButtons_likeTheCards() {
        setContent()

        for (tag in listOf(TestTags.Hub.ALL_MEDIA, TestTags.Hub.STATS, TestTags.Hub.BOOKS)) {
            composeRule.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        }
    }

    @Test
    fun theYearInReviewRow_opensIt() {
        setContent()

        composeRule.onNodeWithTag(TestTags.Hub.YEAR_REVIEW).performClick()

        assertEquals(listOf("yearReview"), opened)
    }
}
