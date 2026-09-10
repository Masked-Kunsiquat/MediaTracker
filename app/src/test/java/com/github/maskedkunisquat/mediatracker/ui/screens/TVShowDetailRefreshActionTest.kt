package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The "refresh episode details from TMDB" action on the show detail screen.
 *
 * In `app/src/test/` rather than `androidTest/`: the screen is stateless, so this needs no device
 * and runs inside `:app:testDebugUnitTest`, a required CI check.
 *
 * The action is absent rather than disabled for a show with no TMDB record, so these assert on
 * existence as well as on enablement -- a control that is present and always fails is the failure
 * mode this screen has shipped before.
 */
@OptIn(ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
class TVShowDetailRefreshActionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aShowWithNoTmdbRecordDoesNotOfferTheAction() {
        // A hand-entered show has nothing to refresh against. Absent, not disabled: a control that
        // always fails is worse than one that is not there.
        setContent(canRefresh = false)

        composeRule
            .onNodeWithContentDescription("Refresh episode details from TMDB")
            .assertDoesNotExist()
    }

    @Test
    fun aShowWithNoTmdbRecordCanStillBeDeleted() {
        // The regression a golden caught rather than a test: nesting the delete action inside the
        // refresh action's visibility guard removed delete from every hand-entered show. A picture
        // should not be the thing that notices a missing control.
        setContent(canRefresh = false)

        composeRule.onNodeWithContentDescription("Delete show").assertIsEnabled()
    }

    @Test
    fun aShowAddedFromTmdbOffersTheAction() {
        setContent(canRefresh = true)

        composeRule
            .onNodeWithContentDescription("Refresh episode details from TMDB")
            .assertIsEnabled()
    }

    @Test
    fun theActionIsDisabledWhileARefreshIsRunning() {
        // Two taps would issue two requests for the same show and report twice.
        setContent(canRefresh = true, refreshing = true)

        composeRule
            .onNodeWithContentDescription("Refresh episode details from TMDB")
            .assertIsNotEnabled()
    }

    @Test
    fun tappingItInvokesTheCallback() {
        var refreshes = 0
        setContent(canRefresh = true, onRefresh = { refreshes++ })

        composeRule.onNodeWithContentDescription("Refresh episode details from TMDB").performClick()

        assertEquals(1, refreshes)
    }

    private fun setContent(
        canRefresh: Boolean,
        refreshing: Boolean = false,
        onRefresh: () -> Unit = {},
    ) {
        composeRule.setContent {
            TVShowDetailScreen(
                uiState =
                    TVShowDetailUiState.Ready(
                        show = show(),
                        seasons = listOf(SeasonGroup(1, listOf(episode(1, 1)), watchedCount = 0)),
                        watchedEpisodes = 0,
                        totalEpisodes = 1,
                        isAbandoned = false,
                        canRefreshMetadata = canRefresh,
                        isRefreshingMetadata = refreshing,
                    ),
                onEpisodeWatchedChange = { _, _ -> },
                onSeasonWatchedChange = { _, _ -> },
                onSetSeasonLength = { _, _ -> },
                onRemoveSeason = {},
                onAbandonedChange = {},
                onRefreshMetadata = onRefresh,
                onDelete = {},
                onErrorShown = {},
                onNavigateBack = {},
            )
        }
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
