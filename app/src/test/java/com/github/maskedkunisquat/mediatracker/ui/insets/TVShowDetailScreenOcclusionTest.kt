package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.ui.screens.TVShowDetailScreen
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
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
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Instant

/**
 * Navigation-bar occlusion guard for the TV show detail screen.
 *
 * This screen is the one issue #83 was about: a control on a season row that was present and
 * enabled and could not be reached. #99 then moved this screen's padding off its `LazyColumn`'s
 * `Modifier` and onto its `contentPadding`, so the episode list now passes *under* the navigation
 * bar by design and only its last row is required to clear it. That is a strictly harder thing to
 * get right than the arrangement it replaced, and this is the assertion that it was.
 *
 * Episode rows carry checkboxes, so the bottom of this list is interactive and the rule has
 * something to measure — which is not true of every screen #99 touched (see
 * [AddMovieScreenOcclusionTest]'s note on why some have no navigation-bar test at all).
 *
 * No keyboard test: the fields on this screen live in the add-season and season-length dialogs,
 * which are separate windows with their own insets, and the screen underneath renders no text
 * field of its own.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
class TVShowDetailScreenOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withTheNavigationBarShowing_theLastEpisodeRowStaysAboveIt() {
        composeRule.assertNoInteractiveNodeIsBehindTheNavigationBar { Fixture() }
    }

    /**
     * #163: on a narrow row, a font scale of 1.1 squeezed the season overflow button to zero width.
     * The goldens render at full width, where the header fits even at 2.0, so neither they nor a
     * default-scale test could see it. 220dp matches `TVShowDetailScreenTest`'s narrow-phone width.
     *
     * NATIVE graphics is what makes this able to fail: Robolectric's default text measurement is not
     * real, and under it this test passed with the fix removed.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun onANarrowRow_theSeasonMenuStaysDisplayed_atEnlargedFontScales() {
        var fontScale by mutableFloatStateOf(1.1f)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MediaTrackerTheme {
                    Box(modifier = Modifier.width(220.dp)) { Fixture() }
                }
            }
        }

        listOf(1.1f, 2f).forEach { scale ->
            fontScale = scale
            composeRule
                .onNodeWithContentDescription("Season 1 options")
                .assertIsDisplayed()
        }
    }

    @Composable
    private fun Fixture() {
        val episodes = (1..EPISODE_COUNT).map { episode(seasonNumber = 1, episodeNumber = it) }
        TVShowDetailScreen(
            coverStorageDir = NO_COVERS,
            uiState =
                TVShowDetailUiState.Ready(
                    show = show(),
                    seasons = listOf(SeasonGroup(seasonNumber = 1, episodes = episodes, watchedCount = 0)),
                    watchedEpisodes = 0,
                    totalEpisodes = EPISODE_COUNT,
                    isAbandoned = false,
                ),
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

    private fun show(id: String = "show-1") =
        MediaWithDetails.TVShow(
            item =
                MediaItemEntity(
                    id = id,
                    type = MediaType.TV_SHOW,
                    title = "Chernobyl",
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

    private companion object {
        /**
         * A directory holding no images. Every fixture row has a null cover hash, so
         * [CoverImage] draws its placeholder and never reads from disk.
         */
        const val NO_COVERS = "no-covers-in-this-fixture"

        /**
         * Enough episode rows to run past the bottom of the test display, so the list has a row at
         * the navigation bar to be wrong about. A short season would put the last checkbox nowhere
         * near the bar and this test would pass whatever the padding did — the no-op that the
         * falsification sweep on #99 caught in four other fixtures.
         */
        const val EPISODE_COUNT = 30
    }
}
