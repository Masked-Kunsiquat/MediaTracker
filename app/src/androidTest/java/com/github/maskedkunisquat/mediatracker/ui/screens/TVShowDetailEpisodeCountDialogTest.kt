package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.AiringStatus
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.TVDetailsEntity
import com.hub.media.features.media.data.MediaWithDetails
import com.hub.media.features.tv.domain.SeasonCountMismatch
import com.hub.media.ui.SeasonGroup
import com.hub.media.ui.TVShowDetailUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The TV Show Detail tests that type into the season-length (episode-count) dialog (#83), kept on
 * a device by #208: under Robolectric a text field inside a dialog never lets Compose go idle
 * (AGENTS.md section 7), and the hang poisons every later test in the JVM. Everything else on this
 * screen is tested in the Robolectric lane's `TVShowDetailScreenTest`.
 *
 * [SeasonLengthDialog] is private, so each test reaches it the way a user does: a season's overflow
 * menu, then "Change episode count".
 */
@OptIn(ExperimentalTime::class)
class TVShowDetailEpisodeCountDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(
        id: String = "show-1",
        title: String = "Chernobyl",
        releaseYear: Int? = 2019,
        synopsis: String? = null,
        airingStatus: AiringStatus? = null,
        totalSeasons: Int? = 1,
    ) = MediaWithDetails.TVShow(
        item =
            MediaItemEntity(
                id = id,
                type = MediaType.TV_SHOW,
                title = title,
                releaseYear = releaseYear,
                purchasePrice = null,
                createdAt = Instant.fromEpochMilliseconds(0),
                coverImageHash = null,
                synopsis = synopsis,
            ),
        details =
            TVDetailsEntity(
                mediaId = id,
                totalSeasons = totalSeasons,
                airingStatus = airingStatus,
            ),
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
        releaseYear: Int? = 2019,
        isAbandoned: Boolean = false,
        errorMessage: String? = null,
        canRefreshMetadata: Boolean = false,
        isRefreshingMetadata: Boolean = false,
        synopsis: String? = null,
        seasonFindings: List<SeasonCountMismatch> = emptyList(),
        addingSeasonNumbers: Set<Int> = emptySet(),
    ): TVShowDetailUiState.Ready {
        val episodes = (1..episodeCount).map { episode(seasonNumber, it) }
        return TVShowDetailUiState.Ready(
            show = show(releaseYear = releaseYear, synopsis = synopsis),
            seasons = listOf(SeasonGroup(seasonNumber = seasonNumber, episodes = episodes, watchedCount = 0)),
            watchedEpisodes = 0,
            totalEpisodes = episodeCount,
            isAbandoned = isAbandoned,
            errorMessage = errorMessage,
            canRefreshMetadata = canRefreshMetadata,
            isRefreshingMetadata = isRefreshingMetadata,
            seasonFindings = seasonFindings,
            addingSeasonNumbers = addingSeasonNumbers,
        )
    }

    /**
     * A [TVShowDetailUiState.Ready] built for the read-only status chip's derived-bucket tests
     * (#141 step 2): independent control over watched/total episodes and airing status --
     * two of the inputs [com.hub.media.ui.LibraryStatusFilter.ofShow] combines.
     */
    private fun readyStateForBucket(
        watched: Int,
        total: Int,
        airingStatus: AiringStatus? = null,
    ): TVShowDetailUiState.Ready {
        val episodes =
            (1..total).map { n ->
                episode(seasonNumber = 1, episodeNumber = n).copy(
                    watchedAt = if (n <= watched) Instant.fromEpochMilliseconds(1_700_000_000_000L) else null,
                )
            }
        return TVShowDetailUiState.Ready(
            show = show(airingStatus = airingStatus),
            seasons = listOf(SeasonGroup(seasonNumber = 1, episodes = episodes, watchedCount = watched)),
            watchedEpisodes = watched,
            totalEpisodes = total,
            isAbandoned = false,
        )
    }

    private fun setContent(
        uiState: TVShowDetailUiState,
        onSetSeasonLength: (Int, Int) -> Unit = { _, _ -> },
        onRemoveSeason: (Int) -> Unit = {},
        onAbandonedChange: (Boolean) -> Unit = {},
        onDelete: () -> Unit = {},
        onErrorShown: () -> Unit = {},
        onNavigateBack: () -> Unit = {},
        onRefreshMetadata: () -> Unit = {},
        onAddMissingEpisodes: (Int) -> Unit = {},
        onAddAllMissingEpisodes: () -> Unit = {},
        onNavigateToEdit: () -> Unit = {},
        // Pins the composable to a narrow-phone logical width instead of letting it fill this test
        // device's actual (much wider) screen. The #83 layout regression this test class guards
        // against only reproduces once the season header row is genuinely over its width budget --
        // see 3042a48's commit message for the pixel breakdown -- and this test device is wide
        // enough that a two-digit episode count alone does not run out of room on it.
        narrowWidth: Boolean = false,
        // Overrides the device's font scale, so a layout guard fails the same way on every device.
        fontScale: Float? = null,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
            ) {
                MediaTrackerTheme {
                    val content =
                        @Composable {
                            TVShowDetailScreen(
                                // See MovieDetailScreenTest: no artwork is asserted here either.
                                coverStorageDir = NO_COVERS,
                                uiState = uiState,
                                onEpisodeWatchedChange = { _, _ -> },
                                onSeasonWatchedChange = { _, _ -> },
                                onSetSeasonLength = onSetSeasonLength,
                                onRemoveSeason = onRemoveSeason,
                                onAbandonedChange = onAbandonedChange,
                                onDelete = onDelete,
                                onErrorShown = onErrorShown,
                                onUndoUntick = {},
                                onUntickShown = {},
                                onEpisodeOpen = {},
                                onNavigateBack = onNavigateBack,
                                onRefreshMetadata = onRefreshMetadata,
                                onAddMissingEpisodes = onAddMissingEpisodes,
                                onAddAllMissingEpisodes = onAddAllMissingEpisodes,
                                onNavigateToEdit = onNavigateToEdit,
                                runActions = ShowRunActions({}, {}, { _, _ -> }, {}, {}),
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

    // #83's last gaps. In this lane rather than app/src/test/ because under Robolectric a text
    // field inside a dialog never lets Compose go idle (AppNotIdleException), and with the clock
    // stopped the dialog never composes; the Remove-season prompt, which has no text field, is
    // covered there instead (TVShowDetailSeasonEditPromptTest).

    private fun quantity(
        id: Int,
        count: Int,
    ) = context.resources.getQuantityString(id, count, count)

    private companion object {
        /** See [MovieDetailScreenTest]: this class asserts episodes and controls, never artwork. */
        const val NO_COVERS = "no-covers-in-this-fixture"
    }

    // Device-only: types into the dialog's text field (see the class KDoc).
    @Test
    fun dialogConfirm_isDisabledWhenEpisodeCountIsBlank() {
        setContent(readyState(episodeCount = 5))

        openChangeEpisodeCountDialog(seasonNumber = 1)
        setEpisodeCountField("")

        composeRule.onNode(dialogConfirmMatcher()).assertIsNotEnabled()
    }

    // Device-only: types into the dialog's text field (see the class KDoc).
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

    // Device-only: types into the dialog's text field (see the class KDoc).
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

    // Device-only: types into the dialog's text field (see the class KDoc).
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

    // Device-only: types into the dialog's text field (see the class KDoc).
    @Test
    fun reEnteringTheSameCount_appliesImmediately_withNoConfirmation() {
        // The gate is `episodeCount < existingLength`. Equal is the edge a `<=` would get wrong,
        // asking "Remove 0 episodes?" about a change that removes nothing.
        val calls = mutableListOf<Pair<Int, Int>>()
        setContent(readyState(episodeCount = 5), onSetSeasonLength = { season, count -> calls += season to count })

        openChangeEpisodeCountDialog(seasonNumber = 1)
        setEpisodeCountField("5")
        composeRule.onNode(dialogConfirmMatcher()).performClick()

        assertEquals("an unchanged count applies at once", listOf(1 to 5), calls)
        composeRule
            .onNodeWithText(
                context.getString(R.string.tv_show_detail_shrink_season_confirm),
            ).assertDoesNotExist()
    }

    // Device-only: types into the dialog's text field (see the class KDoc).
    @Test
    fun shrinkConfirmation_namesHowManyRemovedEpisodesWereWatched() {
        // Five of five watched; shrinking to 3 removes episodes 4 and 5, both watched.
        val calls = mutableListOf<Pair<Int, Int>>()
        setContent(readyStateForBucket(watched = 5, total = 5), onSetSeasonLength = { s, n -> calls += s to n })

        openChangeEpisodeCountDialog(seasonNumber = 1)
        setEpisodeCountField("3")
        composeRule.onNode(dialogConfirmMatcher()).performClick()

        composeRule.onNodeWithText(quantity(R.plurals.tv_show_detail_shrink_season_title, 2)).assertIsDisplayed()
        composeRule.onNodeWithText(quantity(R.plurals.tv_show_detail_episodes_watched_warning, 2)).assertIsDisplayed()
        assertEquals("nothing applies until confirmed", emptyList<Pair<Int, Int>>(), calls)

        composeRule.onNodeWithText(context.getString(R.string.tv_show_detail_shrink_season_confirm)).performClick()
        assertEquals(listOf(1 to 3), calls)
    }

    // Device-only: types into the dialog's text field (see the class KDoc).
    @Test
    fun shrinkConfirmation_whenNoRemovedEpisodeWasWatched_saysOnlyThatItCannotBeUndone() {
        // Episodes 1-3 are watched and survive; 4 and 5 go, unwatched. The warning counts the
        // removed episodes, not the season's, so it takes the zero wording although the season has
        // watched episodes -- "0 of them are marked watched" reads like a bug.
        setContent(readyStateForBucket(watched = 3, total = 5))

        openChangeEpisodeCountDialog(seasonNumber = 1)
        setEpisodeCountField("3")
        composeRule.onNode(dialogConfirmMatcher()).performClick()

        composeRule.onNodeWithText(quantity(R.plurals.tv_show_detail_shrink_season_title, 2)).assertIsDisplayed()
        composeRule
            .onNodeWithText(
                context.getString(R.string.tv_show_detail_removal_undone_warning),
            ).assertIsDisplayed()
        composeRule.onNodeWithText(quantity(R.plurals.tv_show_detail_episodes_watched_warning, 3)).assertDoesNotExist()
        composeRule.onNodeWithText(quantity(R.plurals.tv_show_detail_episodes_watched_warning, 0)).assertDoesNotExist()
    }
}
