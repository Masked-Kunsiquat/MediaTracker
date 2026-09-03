package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.TVDetailsEntity
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.features.media.data.MediaWithDetails
import com.hub.media.ui.SeasonGroup
import com.hub.media.ui.TVShowDetailUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Fold behaviour for the TV show detail screen's seasons.
 *
 * In `app/src/test/` rather than `androidTest/`: the screen is stateless with respect to everything
 * except the fold itself, so this needs no device and runs inside `:app:testDebugUnitTest`, a
 * required CI check.
 *
 * The fold is deliberately *not* covered by this screen's golden. A multi-season show opens folded,
 * so a golden of one would capture shut headers and none of the episode rows that image exists for —
 * see `TVShowDetailScreenGoldenTest`'s KDoc. Behaviour belongs here instead.
 *
 * Every assertion is on the header's content description rather than on the presence of episode
 * rows. `LazyColumn` composes only what is visible, so "no episode node exists" is true both for a
 * folded season and for one scrolled off screen; the chevron's label distinguishes them.
 */
@OptIn(ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
class TVShowDetailSeasonFoldTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aSingleSeasonShowOpensExpanded() {
        // A single-season show is its episode list; opening it to one shut row would hide the screen.
        setContent(seasonNumbers = listOf(1))

        composeRule.onNodeWithContentDescription("Collapse season 1").assertExists()
    }

    @Test
    fun aMultiSeasonShowOpensFolded() {
        // 458 episodes across four seasons is the case this exists for -- see #130.
        setContent(seasonNumbers = listOf(1, 2, 3))

        composeRule.onNodeWithContentDescription("Expand season 1").assertExists()
        composeRule.onNodeWithContentDescription("Expand season 2").assertExists()
        composeRule.onNodeWithContentDescription("Expand season 3").assertExists()
    }

    @Test
    fun tappingASeasonHeaderTogglesOnlyThatSeason() {
        setContent(seasonNumbers = listOf(1, 2))

        composeRule.onNodeWithContentDescription("Expand season 1").performClick()

        composeRule.onNodeWithContentDescription("Collapse season 1").assertExists()
        composeRule.onNodeWithContentDescription("Expand season 2").assertExists()
    }

    @Test
    fun removingTheFirstSeasonLeavesTheSurvivorExpanded() {
        // The survivor holds a fold it only ever got for being one of several. Left alone, the
        // screen opens on a single shut row -- the state the single-season rule exists to avoid.
        val state = mutableStateOf(uiState(listOf(1, 2)))
        setContent(state)

        composeRule.onNodeWithContentDescription("Expand season 2").assertExists()

        state.value = uiState(listOf(2))

        composeRule.onNodeWithContentDescription("Collapse season 2").assertExists()
    }

    @Test
    fun removingTheSecondSeasonLeavesTheSurvivorExpanded() {
        // The same transition from the other side, so the fix cannot be one that happens to work
        // only for the season that sorts first.
        val state = mutableStateOf(uiState(listOf(1, 2)))
        setContent(state)

        composeRule.onNodeWithContentDescription("Expand season 1").assertExists()

        state.value = uiState(listOf(1))

        composeRule.onNodeWithContentDescription("Collapse season 1").assertExists()
    }

    @Test
    fun gainingASeasonDoesNotFoldTheOneAlreadyOpen() {
        // The converse regression: a one-season show that gains a season must not have the season
        // being worked in shut underneath the user.
        val state = mutableStateOf(uiState(listOf(1)))
        setContent(state)

        composeRule.onNodeWithContentDescription("Collapse season 1").assertExists()

        state.value = uiState(listOf(1, 2))

        composeRule.onNodeWithContentDescription("Collapse season 1").assertExists()
    }

    // ---- fixture -------------------------------------------------------------------------------

    private fun setContent(seasonNumbers: List<Int>) = setContent(mutableStateOf(uiState(seasonNumbers)))

    private fun setContent(state: MutableState<TVShowDetailUiState>) {
        composeRule.setContent { Fixture(state) }
    }

    @Composable
    private fun Fixture(state: MutableState<TVShowDetailUiState>) {
        val current by remember { state }
        TVShowDetailScreen(
            uiState = current,
            onEpisodeWatchedChange = { _, _ -> },
            onSeasonWatchedChange = { _, _ -> },
            onSetSeasonLength = { _, _ -> },
            onRemoveSeason = {},
            onAbandonedChange = {},
            onRefreshMetadata = {},
            onDelete = {},
            onErrorShown = {},
            onNavigateBack = {},
        )
    }

    private fun uiState(seasonNumbers: List<Int>): TVShowDetailUiState {
        val seasons =
            seasonNumbers.map { number ->
                SeasonGroup(
                    seasonNumber = number,
                    episodes = (1..3).map { episode(number, it) },
                    watchedCount = 0,
                )
            }
        return TVShowDetailUiState.Ready(
            show = show(),
            seasons = seasons,
            watchedEpisodes = 0,
            totalEpisodes = seasons.sumOf { it.episodes.size },
            isAbandoned = false,
        )
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

    private fun episode(
        seasonNumber: Int,
        episodeNumber: Int,
    ) = EpisodeEntity(
        id = "ep-$seasonNumber-$episodeNumber",
        mediaId = "show-1",
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )
}
