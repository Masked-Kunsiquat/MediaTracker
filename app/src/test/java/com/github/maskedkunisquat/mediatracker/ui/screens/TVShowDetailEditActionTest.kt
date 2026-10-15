package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.ui.test.assertIsEnabled
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
 * The Edit action on the show detail screen (#173). Robolectric, so it runs in the required
 * `:app:testDebugUnitTest` gate. Edit is gated on Ready like films' (there is nothing to edit until
 * the show has loaded), unlike Delete, which stays reachable while loading.
 */
@OptIn(ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
class TVShowDetailEditActionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aLoadedShowOffersEdit_andTappingItNavigates() {
        var edits = 0
        setContent(ready = true, onEdit = { edits++ })

        composeRule.onNodeWithContentDescription("Edit show").assertIsEnabled().performClick()

        assertEquals(1, edits)
    }

    @Test
    fun aHandEnteredShowOffersEditToo() {
        // The case #173 exists for: no TMDB record, so no Refresh -- Edit must not share its guard.
        setContent(ready = true, canRefresh = false)

        composeRule.onNodeWithContentDescription("Edit show").assertIsEnabled()
    }

    @Test
    fun whileLoading_neitherEditNorMoreOptionsIsShown() {
        // #172 settled TV's old divergence: Delete used to show while loading. Like the film and
        // book screens, nothing is offered until the show has loaded.
        setContent(ready = false)

        composeRule.onNodeWithContentDescription("Edit show").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("More options").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Delete show").assertDoesNotExist()
    }

    private fun setContent(
        ready: Boolean,
        canRefresh: Boolean = true,
        onEdit: () -> Unit = {},
    ) {
        composeRule.setContent {
            TVShowDetailScreen(
                coverStorageDir = NO_COVERS,
                uiState =
                    if (ready) {
                        TVShowDetailUiState.Ready(
                            show = show(),
                            seasons = listOf(SeasonGroup(1, listOf(episode(1, 1)), watchedCount = 0)),
                            watchedEpisodes = 0,
                            totalEpisodes = 1,
                            isAbandoned = false,
                            canRefreshMetadata = canRefresh,
                        )
                    } else {
                        TVShowDetailUiState.Loading
                    },
                onEpisodeWatchedChange = { _, _ -> },
                onSeasonWatchedChange = { _, _ -> },
                onSetSeasonLength = { _, _ -> },
                onRemoveSeason = {},
                onAbandonedChange = {},
                onRefreshMetadata = {},
                onAddMissingEpisodes = {},
                onAddAllMissingEpisodes = {},
                onDelete = {},
                onErrorShown = {},
                onUndoUntick = {},
                onUntickShown = {},
                onEpisodeOpen = {},
                onNavigateBack = {},
                onNavigateToEdit = onEdit,
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

    private companion object {
        /** A directory holding no images; every fixture row has a null cover hash. */
        const val NO_COVERS = "no-covers-in-this-fixture"
    }
}
