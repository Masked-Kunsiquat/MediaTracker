package com.github.maskedkunisquat.mediatracker.ui.screens

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.maskedkunisquat.mediatracker.R
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The Remove-season prompt (#83): it is its own confirmation, it states the whole season's cost,
 * and nothing is removed until it is confirmed.
 *
 * Here, in the Robolectric lane that runs on every PR, because the prompt has no text field. #83's
 * other two gaps (the equal-count edge of the shrink gate, and the shrink prompt's wording) are in
 * the device lane's `TVShowDetailScreenTest`: they go through the episode-count dialog, and under
 * Robolectric a text field inside a dialog never lets Compose go idle (AppNotIdleException), while
 * with the clock stopped the dialog never composes at all.
 *
 * Dialog text is resolved through resources, so a reworded string changes one place; the plural
 * resolved is the one the screen should have picked, which is what each test pins.
 */
@OptIn(ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
class TVShowDetailSeasonEditPromptTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun removeSeason_hasItsOwnPrompt_withTheWholeSeasonsCounts_andRemovesOnlyOnConfirm() {
        val removed = mutableListOf<Int>()
        setContent(season(watched = setOf(1, 2)), onRemoveSeason = { removed += it })

        openRemoveSeasonPrompt(seasonNumber = 1)

        composeRule.onNodeWithText(context.getString(R.string.tv_show_detail_remove_season_title, 1)).assertExists()
        composeRule.onNodeWithText(plural(R.plurals.tv_show_detail_episode_removal_count, 5)).assertExists()
        composeRule.onNodeWithText(plural(R.plurals.tv_show_detail_episodes_watched_warning, 2)).assertExists()
        assertEquals("nothing is removed until the prompt is confirmed", emptyList<Int>(), removed)

        // The menu item is gone once tapped, so the only "Remove season" button left is the prompt's.
        composeRule.onNode(hasText(string(R.string.tv_show_detail_remove_season)) and hasClickAction()).performClick()
        assertEquals(listOf(1), removed)
    }

    @Test
    fun removeSeason_withNothingWatched_saysOnlyThatItCannotBeUndone() {
        setContent(season(watched = emptySet()))

        openRemoveSeasonPrompt(seasonNumber = 1)

        composeRule.onNodeWithText(plural(R.plurals.tv_show_detail_episode_removal_count, 5)).assertExists()
        composeRule.onNodeWithText(string(R.string.tv_show_detail_removal_undone_warning)).assertExists()
        composeRule.onNodeWithText(plural(R.plurals.tv_show_detail_episodes_watched_warning, 0)).assertDoesNotExist()
    }

    @Test
    fun removeSeason_cancelled_removesNothing() {
        val removed = mutableListOf<Int>()
        setContent(season(watched = emptySet()), onRemoveSeason = { removed += it })

        openRemoveSeasonPrompt(seasonNumber = 1)
        composeRule.onNodeWithText(context.getString(R.string.tv_show_detail_remove_season_title, 1)).assertExists()
        composeRule.onNodeWithText(string(R.string.cancel_button)).performClick()

        composeRule
            .onNodeWithText(
                context.getString(R.string.tv_show_detail_remove_season_title, 1),
            ).assertDoesNotExist()
        assertEquals(emptyList<Int>(), removed)
    }

    // ---- interactions ---------------------------------------------------------------------------

    /** Season ⋮ → "Remove season", which opens the prompt rather than removing anything. */
    private fun openRemoveSeasonPrompt(seasonNumber: Int) {
        composeRule
            .onNodeWithContentDescription(
                context.getString(R.string.tv_show_detail_season_menu_content_description, seasonNumber),
            ).performClick()
        composeRule.onNodeWithText(string(R.string.tv_show_detail_remove_season)).performClick()
    }

    // ---- fixture --------------------------------------------------------------------------------

    private fun setContent(
        season: SeasonGroup,
        onRemoveSeason: (Int) -> Unit = {},
    ) {
        composeRule.setContent {
            TVShowDetailScreen(
                coverStorageDir = NO_COVERS,
                uiState =
                    TVShowDetailUiState.Ready(
                        show = show(),
                        seasons = listOf(season),
                        watchedEpisodes = season.watchedCount,
                        totalEpisodes = season.episodes.size,
                        isAbandoned = false,
                    ),
                onEpisodeWatchedChange = { _, _ -> },
                onSeasonWatchedChange = { _, _ -> },
                onSetSeasonLength = { _, _ -> },
                onRemoveSeason = onRemoveSeason,
                onAbandonedChange = {},
                onRefreshMetadata = {},
                onAddMissingEpisodes = {},
                onAddAllMissingEpisodes = {},
                onDelete = {},
                onErrorShown = {},
                onNavigateBack = {},
            )
        }
    }

    /** Season 1 of five episodes, with [watched] naming which episode numbers are watched. */
    private fun season(watched: Set<Int>): SeasonGroup {
        val episodes =
            (1..5).map { n ->
                EpisodeEntity(
                    id = "ep-1-$n",
                    mediaId = "show-1",
                    seasonNumber = 1,
                    episodeNumber = n,
                    watchedAt = if (n in watched) Instant.fromEpochMilliseconds(n * 1_000L) else null,
                )
            }
        return SeasonGroup(seasonNumber = 1, episodes = episodes, watchedCount = watched.size)
    }

    private fun show() =
        MediaWithDetails.TVShow(
            item =
                MediaItemEntity(
                    id = "show-1",
                    type = MediaType.TV_SHOW,
                    title = "Chernobyl",
                    releaseYear = 2019,
                    purchasePrice = null,
                    createdAt = Instant.fromEpochMilliseconds(0),
                    coverImageHash = null,
                ),
            details = TVDetailsEntity(mediaId = "show-1", totalSeasons = 1, status = WatchStatus.WATCHING),
        )

    private fun string(id: Int) = context.getString(id)

    private fun plural(
        id: Int,
        count: Int,
    ) = context.resources.getQuantityString(id, count, count)

    private companion object {
        /** No fixture row has a cover, so `CoverImage` never reads from disk. */
        const val NO_COVERS = "no-covers-in-this-fixture"
    }
}
