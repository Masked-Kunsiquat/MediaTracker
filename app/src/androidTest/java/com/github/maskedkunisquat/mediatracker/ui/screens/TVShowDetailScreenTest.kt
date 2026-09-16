package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
        releaseYear: Int? = 2019,
        synopsis: String? = null,
        status: WatchStatus = WatchStatus.WATCHLIST,
        airingStatus: AiringStatus? = null,
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
        details = TVDetailsEntity(mediaId = id, totalSeasons = 1, status = status, airingStatus = airingStatus),
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
        )
    }

    /**
     * A [TVShowDetailUiState.Ready] built for the read-only status chip's derived-bucket tests
     * (#141 step 2): independent control over stored status, watched/total episodes and airing
     * status -- the three inputs [com.hub.media.ui.LibraryStatusFilter.ofShow] combines.
     */
    private fun readyStateForBucket(
        watched: Int,
        total: Int,
        status: WatchStatus = WatchStatus.WATCHLIST,
        airingStatus: AiringStatus? = null,
    ): TVShowDetailUiState.Ready {
        val episodes =
            (1..total).map { n ->
                episode(seasonNumber = 1, episodeNumber = n).copy(
                    watchedAt = if (n <= watched) Instant.fromEpochMilliseconds(1_700_000_000_000L) else null,
                )
            }
        return TVShowDetailUiState.Ready(
            show = show(status = status, airingStatus = airingStatus),
            seasons = listOf(SeasonGroup(seasonNumber = 1, episodes = episodes, watchedCount = watched)),
            watchedEpisodes = watched,
            totalEpisodes = total,
            isAbandoned = status == WatchStatus.ABANDONED,
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
                                onNavigateBack = onNavigateBack,
                                onRefreshMetadata = onRefreshMetadata,
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

    /**
     * #163: the test above passed at font scale 1.0 and failed at 1.1, so it guarded only on devices
     * that happened to use a larger font. Pinning the scale makes the guard device-independent; 2.0
     * is the largest system setting.
     */
    @Test
    fun seasonMenuButton_isDisplayed_atLargeFontScaleOnANarrowRow() {
        setContent(readyState(episodeCount = 10), narrowWidth = true, fontScale = 2.0f)

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

    // --- #141: pinning the chrome/header/status behaviour a shared scaffold would absorb ---

    @Test
    fun ready_showsTitleInHeader() {
        // #141 step 2: the title moved out of the (now empty) top bar into DetailHeader, the same
        // move MovieDetailScreen made in step 1.
        setContent(readyState(episodeCount = 3))

        composeRule.onNodeWithText("Chernobyl").assertIsDisplayed()
    }

    @Test
    fun backIcon_invokesOnNavigateBack() {
        var backCount = 0
        setContent(readyState(episodeCount = 3), onNavigateBack = { backCount++ })

        composeRule.onNodeWithContentDescription(context.getString(R.string.navigate_back)).performClick()

        assertEquals(1, backCount)
    }

    @Test
    fun refreshAction_presentWhenCanRefreshMetadata_andInvokesOnRefreshMetadata() {
        // TV has no Edit action at all (see TVShowDetailScreen's topBar actions) -- refresh is its
        // Ready-gated action, and unlike delete (below) it is additionally gated on
        // uiState.canRefreshMetadata, per the "nothing to refresh against" comment at its call site.
        var refreshes = 0
        setContent(readyState(episodeCount = 3, canRefreshMetadata = true), onRefreshMetadata = { refreshes++ })

        val refreshDesc = context.getString(R.string.tv_show_detail_refresh_metadata)
        composeRule.onNodeWithContentDescription(refreshDesc).performClick()

        assertEquals(1, refreshes)
    }

    @Test
    fun refreshAction_absentWhenCannotRefreshMetadata() {
        setContent(readyState(episodeCount = 3, canRefreshMetadata = false))

        val refreshDesc = context.getString(R.string.tv_show_detail_refresh_metadata)
        composeRule.onNodeWithContentDescription(refreshDesc).assertDoesNotExist()
    }

    @Test
    fun deleteAction_isShownEvenDuringLoading_unlikeMovieAndBook() {
        // Deliberate divergence pinned here for the unification: TVShowDetailScreen's delete
        // IconButton sits outside the `uiState is Ready` guard that gates refresh (and that gates
        // both actions on MovieDetailScreen/BookDetailScreen) -- see the "Outside that guard: every
        // show can be deleted, including one typed in by hand" comment at its call site.
        setContent(TVShowDetailUiState.Loading)

        val deleteDesc = context.getString(R.string.tv_show_detail_delete)
        composeRule.onNodeWithContentDescription(deleteDesc).assertIsDisplayed()
    }

    @Test
    fun deleteAction_showsConfirmationDialog_andDoesNotDeleteYet() {
        var deletes = 0
        setContent(readyState(episodeCount = 3), onDelete = { deletes++ })

        val deleteDesc = context.getString(R.string.tv_show_detail_delete)
        composeRule.onNodeWithContentDescription(deleteDesc).performClick()

        val confirmMessage = context.getString(R.string.tv_show_detail_delete_confirm)
        composeRule.onNodeWithText(confirmMessage).assertIsDisplayed()
        assertEquals("tapping delete must ask, not act", 0, deletes)
    }

    @Test
    fun confirmingTheDeleteDialog_invokesOnDelete() {
        var deletes = 0
        setContent(readyState(episodeCount = 3), onDelete = { deletes++ })

        val deleteDesc = context.getString(R.string.tv_show_detail_delete)
        composeRule.onNodeWithContentDescription(deleteDesc).performClick()

        // The dialog's confirm button reuses R.string.tv_show_detail_delete ("Delete show") as its
        // label, same trick MovieDetailScreenTest uses to disambiguate it from the dialog's title.
        composeRule.onNode(hasText(deleteDesc) and hasClickAction()).performClick()

        assertEquals(1, deletes)
    }

    @Test
    fun cancellingTheDeleteDialog_dismissesWithoutInvokingOnDelete() {
        var deletes = 0
        setContent(readyState(episodeCount = 3), onDelete = { deletes++ })

        val deleteDesc = context.getString(R.string.tv_show_detail_delete)
        composeRule.onNodeWithContentDescription(deleteDesc).performClick()

        val cancelText = context.getString(R.string.cancel_button)
        composeRule.onNodeWithText(cancelText).performClick()

        assertEquals(0, deletes)
        val confirmMessage = context.getString(R.string.tv_show_detail_delete_confirm)
        composeRule.onNodeWithText(confirmMessage).assertDoesNotExist()
    }

    @Test
    fun loading_showsProgressIndicator() {
        setContent(TVShowDetailUiState.Loading)

        composeRule.onNode(isProgressIndicator).assertExists()
    }

    @Test
    fun notFound_invokesOnNavigateBackAndRendersNotFoundText() {
        var backCount = 0
        setContent(TVShowDetailUiState.NotFound, onNavigateBack = { backCount++ })

        val notFoundText = context.getString(R.string.tv_show_detail_not_found)
        composeRule.onNodeWithText(notFoundText).assertIsDisplayed()
        assertEquals(1, backCount)
    }

    @Test
    fun readyWithErrorMessage_showsSnackbarAndInvokesOnErrorShown() {
        var errorShownCount = 0
        setContent(
            readyState(episodeCount = 3, errorMessage = "Could not refresh the show"),
            onErrorShown = { errorShownCount++ },
        )

        composeRule.onNodeWithText("Could not refresh the show").assertIsDisplayed()

        // onErrorShown() fires only once showSnackbar()'s suspend call returns -- see
        // EditMovieScreenTest.saveError_surfacesTheMessageAndInvokesOnErrorShown for the same wait.
        composeRule.waitUntil(timeoutMillis = 10_000) { errorShownCount == 1 }
        assertEquals(1, errorShownCount)
    }

    @Test
    fun ready_rendersKindAndYearInHeader() {
        // #141 step 2: the year moved off its own "Year: 2019" row (library_year_label) into
        // DetailHeader's "kind · year" line, mirroring film's step-1 change. The progress text that
        // used to sit beside it moved to DetailProgressCard -- see
        // progressCard_rendersWatchedOverTotalValue below.
        setContent(readyState(episodeCount = 4, releaseYear = 2019))

        val kindYearText =
            context.getString(
                R.string.detail_kind_year_format,
                context.getString(R.string.tv_show_detail_kind),
                2019,
            )
        composeRule.onNodeWithText(kindYearText).assertIsDisplayed()
    }

    @Test
    fun releaseYearNull_rendersKindAloneInHeader() {
        // Mirrors Movie's null-year handling: DetailHeader renders the bare kind ("TV show")
        // rather than a "kind · year" line with a missing year.
        setContent(readyState(episodeCount = 4, releaseYear = null))

        composeRule.onNodeWithText(context.getString(R.string.tv_show_detail_kind)).assertIsDisplayed()
        composeRule.onNodeWithText("Year:", substring = true).assertDoesNotExist()
    }

    @Test
    fun progressCard_rendersWatchedOverTotalValue() {
        setContent(readyState(episodeCount = 4))

        val progressText = context.resources.getQuantityString(R.plurals.tv_show_detail_progress, 4, 0, 4)
        composeRule.onNodeWithText(progressText).assertIsDisplayed()
    }

    @Test
    fun tappingAbandonAction_invokesOnAbandonedChangeWithTrue() {
        // TV's status control is now a read-only chip (DetailStatus.ReadOnly) with a side action --
        // "Abandon"/"Resume" -- rather than the removed full-width toggle button.
        var captured: Boolean? = null
        setContent(readyState(episodeCount = 3, isAbandoned = false), onAbandonedChange = { captured = it })

        val abandonLabel = context.getString(R.string.detail_show_status_abandon_action)
        composeRule.onNodeWithText(abandonLabel).performClick()

        assertEquals(true, captured)
    }

    @Test
    fun tappingResumeAction_invokesOnAbandonedChangeWithFalse() {
        var captured: Boolean? = null
        setContent(readyState(episodeCount = 3, isAbandoned = true), onAbandonedChange = { captured = it })

        val resumeLabel = context.getString(R.string.detail_show_status_resume_action)
        composeRule.onNodeWithText(resumeLabel).performClick()

        assertEquals(false, captured)
    }

    // --- #141 step 2: the read-only status chip's derived label, per LibraryStatusFilter.ofShow ---

    @Test
    fun statusChip_showsNotStarted_whenNoEpisodesAreWatched() {
        setContent(readyStateForBucket(watched = 0, total = 5))

        composeRule.onNodeWithText(context.getString(R.string.library_filter_not_started)).assertIsDisplayed()
    }

    @Test
    fun statusChip_showsWatching_whenPartlyWatched() {
        setContent(readyStateForBucket(watched = 2, total = 5))

        composeRule.onNodeWithText(context.getString(R.string.watch_status_watching)).assertIsDisplayed()
    }

    @Test
    fun statusChip_showsWatched_whenFullyWatchedAndEnded() {
        setContent(readyStateForBucket(watched = 5, total = 5, airingStatus = AiringStatus.ENDED))

        composeRule.onNodeWithText(context.getString(R.string.watch_status_watched)).assertIsDisplayed()
    }

    @Test
    fun statusChip_showsWatching_whenFullyWatchedButStillContinuing() {
        // LibraryStatusFilter.ofShow's KDoc: a fully-watched show still in production is
        // IN_PROGRESS, not FINISHED -- there is more of it, and the viewer is only caught up.
        setContent(readyStateForBucket(watched = 5, total = 5, airingStatus = AiringStatus.CONTINUING))

        composeRule.onNodeWithText(context.getString(R.string.watch_status_watching)).assertIsDisplayed()
    }

    @Test
    fun statusChip_showsAbandoned_whenStoredStatusIsAbandoned() {
        setContent(readyStateForBucket(watched = 0, total = 5, status = WatchStatus.ABANDONED))

        composeRule.onNodeWithText(context.getString(R.string.library_filter_abandoned)).assertIsDisplayed()
    }

    // --- #141 step 2: DetailSynopsis and DetailFacts adopted onto this screen ---

    @Test
    fun synopsisPresent_isDisplayed() {
        setContent(readyState(episodeCount = 3, synopsis = "A miniseries about the Chernobyl disaster."))

        composeRule.onNodeWithText("A miniseries about the Chernobyl disaster.").assertIsDisplayed()
    }

    @Test
    fun synopsisNull_rendersNothing() {
        setContent(readyState(episodeCount = 3, synopsis = null))

        val moreLabel = context.getString(R.string.detail_synopsis_more)
        composeRule.onNodeWithText(moreLabel).assertDoesNotExist()
    }

    @Test
    fun factsWithUnknownAiringData_omitFirstAiredAndAiring() {
        // The fixture sets neither firstAirDate nor airingStatus, so those two facts are dropped;
        // Seasons/Episodes are derived counts and always present.
        setContent(readyState(episodeCount = 4))

        composeRule
            .onAllNodesWithText(context.getString(R.string.tv_show_detail_fact_first_aired))
            .assertCountEquals(0)
        composeRule
            .onAllNodesWithText(context.getString(R.string.tv_show_detail_fact_airing))
            .assertCountEquals(0)
        composeRule
            .onNodeWithText(context.getString(R.string.tv_show_detail_fact_episodes))
            .assertIsDisplayed()
    }

    @Test
    fun coverImageHashNull_rendersNoPosterPlaceholder() {
        // Same guard as Movie's: TVShowDetailScreen's `?.let` skips CoverImage entirely for a null
        // hash, so its emoji placeholder (the only semantics hook it exposes) never composes.
        setContent(readyState(episodeCount = 3))

        composeRule.onNodeWithText(TV_COVER_PLACEHOLDER_EMOJI).assertDoesNotExist()
    }

    private companion object {
        /** See [MovieDetailScreenTest]: this class asserts episodes and controls, never artwork. */
        const val NO_COVERS = "no-covers-in-this-fixture"

        /** [MediaType.TV_SHOW]'s placeholder glyph in `CoverImage`'s `CoverPlaceholder`. */
        const val TV_COVER_PLACEHOLDER_EMOJI = "📺"
    }
}

/**
 * Matches a node carrying [SemanticsProperties.ProgressBarRangeInfo] -- see
 * `MovieDetailScreenTest`'s copy of this matcher for why it exists rather than a
 * contentDescription/testTag lookup.
 */
private val isProgressIndicator =
    SemanticsMatcher("has ProgressBarRangeInfo") {
        it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null
    }
