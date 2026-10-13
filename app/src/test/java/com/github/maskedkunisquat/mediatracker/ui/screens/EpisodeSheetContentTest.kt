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
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.WatchLogEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

/**
 * The stateless [EpisodeSheetContent] (#90), on the Robolectric lane. It holds no picker and no
 * sheet window -- the route hosts those -- so this lane can drive it (AGENTS.md §7).
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(kotlin.time.ExperimentalTime::class)
class EpisodeSheetContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val episode =
        EpisodeEntity(id = "e1", mediaId = "m", seasonNumber = 1, episodeNumber = 4, title = "Please Remain Calm")

    private fun viewing(
        id: String,
        at: String,
    ) = WatchLogEntity(id = id, mediaId = "m", episodeId = "e1", watchedAt = Instant.parse(at))

    private val viewings =
        listOf(
            viewing("v3", "2026-02-20T12:00:00Z"),
            viewing("v2", "2025-01-10T12:00:00Z"),
            viewing("v1", "2024-03-03T12:00:00Z"),
        )

    private var ticks = 0
    private var again = 0
    private val edited = mutableListOf<String>()
    private val deleted = mutableListOf<String>()

    private fun show(viewings: List<WatchLogEntity>) {
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                EpisodeSheetContent(
                    episode = episode,
                    viewings = viewings,
                    onTick = { ticks++ },
                    onWatchedAgain = { again++ },
                    onEditViewing = { edited += it.id },
                    onDeleteViewing = { deleted += it.id },
                )
            }
        }
    }

    @Test
    fun unwatched_saysSo_andOffersMarkWatched() {
        show(emptyList())
        composeRule.onNodeWithText("Not watched yet").assertIsDisplayed()
        composeRule.onNode(hasText("Mark watched") and hasClickAction()).performClick()
        assertEquals(1, ticks)
        assertEquals(0, composeRule.onAllNodesWithText("Watched again").fetchSemanticsNodes().size)
    }

    @Test
    fun watched_listsViewingsNewestFirst_andOffersWatchedAgain() {
        show(viewings)
        composeRule.onNodeWithText("Please Remain Calm").assertIsDisplayed()
        val dates =
            composeRule
                .onAllNodesWithText(
                    ", 20",
                    substring = true,
                ).fetchSemanticsNodes()
                .map { it.config.toString() }
        assertEquals(3, dates.size)
        composeRule.onNode(hasText("Watched again") and hasClickAction()).performClick()
        assertEquals(1, again)
    }

    @Test
    fun editAndDelete_callBackWithThatViewing() {
        show(viewings)
        composeRule.onAllNodesWithContentDescription("Edit date", substring = true)[1].performClick()
        composeRule.onAllNodesWithContentDescription("Delete the viewing", substring = true)[2].performClick()
        assertEquals(listOf("v2"), edited)
        assertEquals(listOf("v1"), deleted)
    }
}
