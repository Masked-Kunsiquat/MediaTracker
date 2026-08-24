package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.TVDetailsEntity
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.features.media.data.MediaWithDetails
import com.hub.media.ui.SeasonGroup
import com.hub.media.ui.TVShowDetailUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Behavioural tests for the TV Show Detail screen's season-length dialog (Issue #83), covering
 * both the numeric-validation refactor and the #83 layout regression it names specifically: a
 * two-digit episode count clipping the season overflow menu's [androidx.compose.material3.IconButton]
 * -- and its content description with it -- off the header row, fixed in 3042a48.
 *
 * [SeasonLengthDialog] itself is private, so it can only be reached the way a user reaches it: by
 * opening a season's overflow menu and tapping "Change episode count." There is deliberately no
 * shortcut around that navigation here -- driving the real menu is what proves the menu's content
 * description survives the two-digit case, which is the whole point of the first test.
 */
@OptIn(ExperimentalTime::class)
class TVShowDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(
        id: String = "show-1",
        title: String = "Chernobyl",
    ) = MediaWithDetails.TVShow(
        item =
            MediaItemEntity(
                id = id,
                type = MediaType.TV_SHOW,
                title = title,
                releaseYear = 2019,
                purchasePrice = null,
                createdAt = Instant.fromEpochMilliseconds(0),
                coverImageHash = null,
            ),
        details = TVDetailsEntity(mediaId = id, totalSeasons = 1, status = WatchStatus.WATCHLIST),
    )

    private fun episode(
        seasonNumber: Int,
        episodeNumber: Int,
        showId: String = "show-1",
    ) = EpisodeEntity(
        id = "ep-$showId-$seasonNumber-$episodeNumber",
        mediaId = showId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )

    /** One [TVShowDetailUiState.Ready] with a single season of [episodeCount] unwatched episodes. */
    private fun readyState(
        episodeCount: Int,
        seasonNumber: Int = 1,
    ): TVShowDetailUiState.Ready {
        val episodes = (1..episodeCount).map { episode(seasonNumber, it) }
        return TVShowDetailUiState.Ready(
            show = show(),
            seasons = listOf(SeasonGroup(seasonNumber = seasonNumber, episodes = episodes, watchedCount = 0)),
            watchedEpisodes = 0,
            totalEpisodes = episodeCount,
            isAbandoned = false,
        )
    }

    private fun setContent(
        uiState: TVShowDetailUiState,
        onSetSeasonLength: (Int, Int) -> Unit = { _, _ -> },
        onRemoveSeason: (Int) -> Unit = {},
        // Pins the composable to a narrow-phone logical width instead of letting it fill this test
        // device's actual (much wider) screen. The #83 layout regression this test class guards
        // against only reproduces once the season header row is genuinely over its width budget --
        // see 3042a48's commit message for the pixel breakdown -- and this test device is wide
        // enough that a two-digit episode count alone does not run out of room on it.
        narrowWidth: Boolean = false,
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                val content =
                    @Composable {
                        TVShowDetailScreen(
                            uiState = uiState,
                            onEpisodeWatchedChange = { _, _ -> },
                            onSeasonWatchedChange = { _, _ -> },
                            onSetSeasonLength = onSetSeasonLength,
                            onRemoveSeason = onRemoveSeason,
                            onAbandonedChange = {},
                            onDelete = {},
                            onErrorShown = {},
                            onNavigateBack = {},
                        )
                    }
                if (narrowWidth) {
                    Box(modifier = Modifier.width(220.dp)) { content() }
                } else {
                    content()
                }
            }
        }
    }

    /** Opens a season's overflow menu and taps "Change episode count," landing on [SeasonLengthDialog]. */
    private fun openChangeEpisodeCountDialog(seasonNumber: Int) {
        val menuDesc = context.getString(R.string.tv_show_detail_season_menu_content_description, seasonNumber)
        composeRule.onNodeWithContentDescription(menuDesc).performClick()

        val changeCountText = context.getString(R.string.tv_show_detail_change_episode_count)
        composeRule.onNodeWithText(changeCountText).performClick()
    }

    /** Replaces whatever the episode-count field holds (the season's current length) with [text]. */
    private fun setEpisodeCountField(text: String) {
        val label = context.getString(R.string.tv_episode_count_label)
        val field = composeRule.onNode(hasText(label) and hasSetTextAction())
        field.performTextClearance()
        if (text.isNotEmpty()) field.performTextInput(text)
    }

    private fun dialogConfirmMatcher() = hasText(context.getString(R.string.save_button)) and hasClickAction()

    @Test
    fun seasonMenuButton_hasANonEmptyContentDescription_atATwoDigitEpisodeCount() {
        // #83's regression (fixed in 3042a48): without weight(fill = false) on the header text, the
        // header, the "Mark season watched" button and the overflow icon together demand more width
        // than a narrow header row has, and the icon -- with it, its content description -- gets
        // pushed out past the LazyColumn's clip bounds. assertIsDisplayed (not assertExists) is the
        // point: a clipped/zero-size node can still be present in the semantics tree, so only
        // "displayed" actually catches this. narrowWidth pins this test to a narrow-phone width
        // rather than this test device's actual (much wider) one -- see [setContent]'s KDoc.
        setContent(readyState(episodeCount = 10), narrowWidth = true)

        val menuDesc = context.getString(R.string.tv_show_detail_season_menu_content_description, 1)
        composeRule.onNodeWithContentDescription(menuDesc).assertIsDisplayed()
    }

    @Test
    fun dialogConfirm_isDisabledWhenEpisodeCountIsBlank() {
        setContent(readyState(episodeCount = 5))

        openChangeEpisodeCountDialog(seasonNumber = 1)
        setEpisodeCountField("")

        composeRule.onNode(dialogConfirmMatcher()).assertIsNotEnabled()
    }

    @Test
    fun shrinkingASeason_showsConfirmationDialog_andDoesNotCallOnSetSeasonLengthUntilConfirmed() {
        var calls = 0
        setContent(readyState(episodeCount = 5), onSetSeasonLength = { _, _ -> calls++ })

        openChangeEpisodeCountDialog(seasonNumber = 1)
        setEpisodeCountField("3")
        composeRule.onNode(dialogConfirmMatcher()).performClick()

        // Two episodes (numbers 4 and 5) would be removed by this shrink.
        val shrinkTitle = context.resources.getQuantityString(R.plurals.tv_show_detail_shrink_season_title, 2, 2)
        composeRule.onNodeWithText(shrinkTitle).assertIsDisplayed()
        assertEquals("a shrink must be confirmed before it is applied", 0, calls)
    }

    @Test
    fun cancellingTheShrinkConfirmation_callsOnSetSeasonLengthZeroTimes() {
        var calls = 0
        setContent(readyState(episodeCount = 5), onSetSeasonLength = { _, _ -> calls++ })

        openChangeEpisodeCountDialog(seasonNumber = 1)
        setEpisodeCountField("3")
        composeRule.onNode(dialogConfirmMatcher()).performClick()

        val cancelText = context.getString(R.string.cancel_button)
        composeRule.onNodeWithText(cancelText).performClick()

        assertEquals(0, calls)
    }

    @Test
    fun growingASeason_callsOnSetSeasonLengthImmediately_withNoConfirmation() {
        var calls = 0
        var capturedSeason = -1
        var capturedCount = -1
        setContent(
            readyState(episodeCount = 5),
            onSetSeasonLength = { season, count ->
                calls++
                capturedSeason = season
                capturedCount = count
            },
        )

        openChangeEpisodeCountDialog(seasonNumber = 1)
        setEpisodeCountField("8")
        composeRule.onNode(dialogConfirmMatcher()).performClick()

        assertEquals(1, calls)
        assertEquals(1, capturedSeason)
        assertEquals(8, capturedCount)
    }
}
