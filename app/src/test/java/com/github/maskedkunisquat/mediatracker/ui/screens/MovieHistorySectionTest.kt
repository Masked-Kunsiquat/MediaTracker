package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.WatchLogEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

/**
 * The stateless [MovieHistorySection] (#192), on the Robolectric lane: each viewing's Edit and
 * Delete report that viewing, and Watched again exists only while the film is Watched.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(kotlin.time.ExperimentalTime::class)
class MovieHistorySectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun viewing(
        id: String,
        at: String,
    ) = WatchLogEntity(id = id, mediaId = "m", episodeId = null, watchedAt = Instant.parse(at))

    private val viewings = listOf(viewing("v2", "2025-01-10T12:00:00Z"), viewing("v1", "2024-03-03T12:00:00Z"))

    private var again = 0
    private val edited = mutableListOf<String>()
    private val deleted = mutableListOf<String>()

    private fun show(
        viewings: List<WatchLogEntity>,
        isWatched: Boolean,
    ) {
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                MovieHistorySection(
                    viewings = viewings,
                    isWatched = isWatched,
                    onWatchedAgain = { again++ },
                    onEditViewing = { edited += it.id },
                    onDeleteViewing = { deleted += it.id },
                )
            }
        }
    }

    @Test
    fun eachRow_reportsItsOwnViewing() {
        show(viewings, isWatched = true)
        composeRule.onAllNodesWithContentDescription("Edit date", substring = true)[1].performClick()
        composeRule.onAllNodesWithContentDescription("Delete", substring = true)[0].performClick()
        assertEquals(listOf("v1"), edited)
        assertEquals(listOf("v2"), deleted)
    }

    @Test
    fun watchedAgain_whileWatched_reportsAClick() {
        show(viewings, isWatched = true)
        composeRule.onNode(hasText("Watched again") and hasClickAction()).performClick()
        assertEquals(1, again)
    }

    @Test
    fun notWatched_withNoViewings_saysSo_andOffersNoRewatch() {
        show(emptyList(), isWatched = false)
        // Positive control: the section itself rendered.
        composeRule.onNodeWithText("History").assertIsDisplayed()
        composeRule.onNodeWithText("Not watched yet").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Watched again").fetchSemanticsNodes().size)
    }

    @Test
    fun leftWatched_keepsItsHistory_withoutRewatch() {
        show(viewings, isWatched = false)
        assertEquals(
            2,
            composeRule.onAllNodesWithContentDescription("Edit date", substring = true).fetchSemanticsNodes().size,
        )
        assertEquals(0, composeRule.onAllNodesWithText("Watched again").fetchSemanticsNodes().size)
    }
}
