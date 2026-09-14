package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.MovieDetailsEntity
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.features.media.data.MediaWithDetails
import com.hub.media.ui.MovieDetailUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Behavioural tests for the Movie Detail screen (ROADMAP Task 13 Phase B).
 *
 * Drives the stateless [MovieDetailScreen] with fabricated [MovieDetailUiState] and fake
 * callbacks. The null-runtime case gets its own test because [MovieDetailsEntity.runtimeMinutes]
 * treats `null` ("unknown") and `0` (a real, zero-minute claim) as different facts -- rendering
 * "0 min" for an unknown runtime would silently assert something untrue.
 */
@OptIn(ExperimentalTime::class)
class MovieDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun movie(
        id: String = "movie-1",
        title: String = "Interstellar",
        releaseYear: Int? = 2014,
        runtimeMinutes: Int? = 169,
        status: WatchStatus = WatchStatus.WATCHLIST,
        communityRating: Double? = null,
        synopsis: String? = null,
        watchedAt: Instant? = null,
    ) = MediaWithDetails.Movie(
        item =
            MediaItemEntity(
                id = id,
                type = MediaType.MOVIE,
                title = title,
                releaseYear = releaseYear,
                purchasePrice = null,
                createdAt = Instant.fromEpochMilliseconds(0),
                coverImageHash = null,
                communityRating = communityRating,
                synopsis = synopsis,
            ),
        details =
            MovieDetailsEntity(
                mediaId = id,
                runtimeMinutes = runtimeMinutes,
                status = status,
                watchedAt = watchedAt,
            ),
    )

    private fun setContent(
        uiState: MovieDetailUiState,
        onStatusChange: (WatchStatus) -> Unit = {},
        onDelete: () -> Unit = {},
        onErrorShown: () -> Unit = {},
        onNavigateBack: () -> Unit = {},
        onNavigateToEditMovie: () -> Unit = {},
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                MovieDetailScreen(
                    // Deliberately a path with nothing behind it. These tests assert text and
                    // controls, never artwork, and a real directory would make them depend on
                    // device storage for a picture none of them looks at.
                    coverStorageDir = NO_COVERS,
                    uiState = uiState,
                    onStatusChange = onStatusChange,
                    onDelete = onDelete,
                    onErrorShown = onErrorShown,
                    onNavigateBack = onNavigateBack,
                    onNavigateToEditMovie = onNavigateToEditMovie,
                )
            }
        }
    }

    // #141: the year moved into DetailHeader's "kind · year" line above the title (was its own
    // "Year: 2014" row from library_year_label), and runtime now renders as "Xh Ym"/"Ym" (was
    // "Runtime: 169 min" from movie_detail_runtime) -- both assertions below are new for that reason.
    @Test
    fun ready_rendersTitleReleaseYearAndRuntime() {
        setContent(MovieDetailUiState.Ready(movie(title = "Interstellar", releaseYear = 2014, runtimeMinutes = 169)))

        composeRule.onNodeWithText("Interstellar").assertIsDisplayed()

        val kindYearText =
            context.getString(
                R.string.detail_kind_year_format,
                context.getString(R.string.movie_detail_kind),
                2014,
            )
        composeRule.onNodeWithText(kindYearText).assertIsDisplayed()

        // 169 minutes = 2h 49m.
        val runtimeText = context.getString(R.string.movie_detail_runtime_hours_minutes, 2, 49)
        composeRule.onNodeWithText(runtimeText).assertIsDisplayed()
    }

    @Test
    fun runtimeMinutesNull_showsRuntimeUnknownText_notZeroMinutes() {
        setContent(MovieDetailUiState.Ready(movie(runtimeMinutes = null)))

        val unknownText = context.getString(R.string.movie_detail_runtime_unknown)
        composeRule.onNodeWithText(unknownText).assertIsDisplayed()

        val zeroMinutesText = context.getString(R.string.movie_detail_runtime, 0)
        composeRule.onNodeWithText(zeroMinutesText).assertDoesNotExist()
    }

    // #141: status is now one StatusDropdownChip rather than four stacked FilterChips, so the old
    // single performClick() on the target label is now two steps -- open the chip (which shows the
    // *current* status, "Watchlist"), then pick the target from the menu it opens.
    @Test
    fun tappingAStatusChip_invokesOnStatusChangeWithThatStatus() {
        var captured: WatchStatus? = null
        setContent(
            MovieDetailUiState.Ready(movie(status = WatchStatus.WATCHLIST)),
            onStatusChange = { captured = it },
        )

        val watchlistLabel = context.getString(R.string.watch_status_watchlist)
        composeRule.onNodeWithText(watchlistLabel).performClick()

        val watchedLabel = context.getString(R.string.watch_status_watched)
        composeRule.onNodeWithText(watchedLabel).performClick()

        assertEquals(WatchStatus.WATCHED, captured)
    }

    @Test
    fun statusChip_opensDropdownMenuShowingEveryOption() {
        setContent(MovieDetailUiState.Ready(movie(status = WatchStatus.WATCHLIST)))

        val watchingLabel = context.getString(R.string.watch_status_watching)
        // Menu closed: the chip shows only the current status ("Watchlist"), not the other options.
        composeRule.onNodeWithText(watchingLabel).assertDoesNotExist()

        val watchlistLabel = context.getString(R.string.watch_status_watchlist)
        composeRule.onNodeWithText(watchlistLabel).performClick()

        composeRule.onNodeWithText(watchingLabel).assertIsDisplayed()
    }

    @Test
    fun deleteAction_showsConfirmationDialog_andDoesNotDeleteYet() {
        var deletes = 0
        setContent(MovieDetailUiState.Ready(movie()), onDelete = { deletes++ })

        val deleteDesc = context.getString(R.string.movie_detail_delete)
        composeRule.onNodeWithContentDescription(deleteDesc).performClick()

        val confirmMessage = context.getString(R.string.movie_detail_delete_confirm)
        composeRule.onNodeWithText(confirmMessage).assertIsDisplayed()
        assertEquals("tapping delete must ask, not act", 0, deletes)
    }

    @Test
    fun confirmingTheDeleteDialog_invokesOnDelete() {
        var deletes = 0
        setContent(MovieDetailUiState.Ready(movie()), onDelete = { deletes++ })

        val deleteDesc = context.getString(R.string.movie_detail_delete)
        composeRule.onNodeWithContentDescription(deleteDesc).performClick()

        // The dialog's confirm button reuses R.string.movie_detail_delete ("Delete movie") as its
        // label; hasClickAction() disambiguates it from the dialog's (non-clickable) title Text
        // that carries the same string.
        composeRule.onNode(hasText(deleteDesc) and hasClickAction()).performClick()

        assertEquals(1, deletes)
    }

    @Test
    fun cancellingTheDeleteDialog_dismissesWithoutInvokingOnDelete() {
        var deletes = 0
        setContent(MovieDetailUiState.Ready(movie()), onDelete = { deletes++ })

        val deleteDesc = context.getString(R.string.movie_detail_delete)
        composeRule.onNodeWithContentDescription(deleteDesc).performClick()

        val cancelText = context.getString(R.string.cancel_button)
        composeRule.onNodeWithText(cancelText).performClick()

        assertEquals(0, deletes)
        val confirmMessage = context.getString(R.string.movie_detail_delete_confirm)
        composeRule.onNodeWithText(confirmMessage).assertDoesNotExist()
    }

    @Test
    fun notFound_invokesOnNavigateBackAndRendersNotFoundText() {
        var backCount = 0
        setContent(MovieDetailUiState.NotFound, onNavigateBack = { backCount++ })

        val notFoundText = context.getString(R.string.movie_detail_not_found)
        composeRule.onNodeWithText(notFoundText).assertIsDisplayed()
        assertEquals(1, backCount)
    }

    // --- #141: pinning the chrome/header/artwork behaviour a shared scaffold would absorb ---

    @Test
    fun backIcon_invokesOnNavigateBack() {
        var backCount = 0
        setContent(MovieDetailUiState.Ready(movie()), onNavigateBack = { backCount++ })

        composeRule.onNodeWithContentDescription(context.getString(R.string.navigate_back)).performClick()

        assertEquals(1, backCount)
    }

    @Test
    fun ready_showsEditAction_andInvokesOnNavigateToEditMovie() {
        var edits = 0
        setContent(MovieDetailUiState.Ready(movie()), onNavigateToEditMovie = { edits++ })

        val editDesc = context.getString(R.string.edit_movie_content_description)
        composeRule.onNodeWithContentDescription(editDesc).performClick()

        assertEquals(1, edits)
    }

    @Test
    fun loading_hidesEditAndDeleteActions() {
        setContent(MovieDetailUiState.Loading)

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.edit_movie_content_description))
            .assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.movie_detail_delete))
            .assertDoesNotExist()
    }

    @Test
    fun notFound_hidesEditAndDeleteActions() {
        setContent(MovieDetailUiState.NotFound)

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.edit_movie_content_description))
            .assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.movie_detail_delete))
            .assertDoesNotExist()
    }

    @Test
    fun loading_showsProgressIndicator() {
        setContent(MovieDetailUiState.Loading)

        composeRule.onNode(isProgressIndicator).assertExists()
    }

    @Test
    fun readyWithErrorMessage_showsSnackbarAndInvokesOnErrorShown() {
        var errorShownCount = 0
        setContent(
            MovieDetailUiState.Ready(movie(), errorMessage = "Could not update the movie"),
            onErrorShown = { errorShownCount++ },
        )

        composeRule.onNodeWithText("Could not update the movie").assertIsDisplayed()

        // onErrorShown() fires only once showSnackbar()'s suspend call returns -- see
        // EditMovieScreenTest.saveError_surfacesTheMessageAndInvokesOnErrorShown for the same wait.
        composeRule.waitUntil(timeoutMillis = 10_000) { errorShownCount == 1 }
        assertEquals(1, errorShownCount)
    }

    // --- #141: DetailHeader/DetailSynopsis behaviour adopted onto this screen ---

    @Test
    fun synopsisNull_rendersNothing() {
        setContent(MovieDetailUiState.Ready(movie(synopsis = null)))

        val moreLabel = context.getString(R.string.detail_synopsis_more)
        composeRule.onNodeWithText(moreLabel).assertDoesNotExist()
    }

    @Test
    fun longSynopsis_showsMoreButton_andExpandsOnClick() {
        setContent(MovieDetailUiState.Ready(movie(synopsis = LONG_SYNOPSIS)))

        val moreLabel = context.getString(R.string.detail_synopsis_more)
        composeRule.onNodeWithText(moreLabel).assertIsDisplayed()
        composeRule.onNodeWithText(moreLabel).performClick()

        val lessLabel = context.getString(R.string.detail_synopsis_less)
        composeRule.onNodeWithText(lessLabel).assertIsDisplayed()
    }

    @Test
    fun communityRatingNull_rendersNoRatingRow() {
        setContent(MovieDetailUiState.Ready(movie(communityRating = null)))

        composeRule.onNodeWithContentDescription("Rated", substring = true).assertDoesNotExist()
    }

    @Test
    fun communityRatingPresent_showsRatingContentDescription() {
        setContent(MovieDetailUiState.Ready(movie(communityRating = 7.9)))

        val description = context.getString(R.string.detail_rating_content_description, "7.9", 10)
        composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
    }

    @Test
    fun watchedWithDate_showsWatchedNote() {
        val watchedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        setContent(MovieDetailUiState.Ready(movie(status = WatchStatus.WATCHED, watchedAt = watchedAt)))

        val localDate =
            java.time.Instant
                .ofEpochMilli(watchedAt.toEpochMilliseconds())
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
        val formattedDate =
            java.time.format.DateTimeFormatter
                .ofPattern("MMM d, yyyy")
                .format(localDate)
        val expectedNote = context.getString(R.string.movie_detail_watched_note, formattedDate)

        composeRule.onNodeWithText(expectedNote).assertIsDisplayed()
    }

    @Test
    fun notWatched_showsNoWatchedNote() {
        val watchedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        // A stale watchedAt (e.g. re-added to the watchlist after being watched) must not read as
        // a note -- the status is what gates it, not the date's mere presence.
        setContent(MovieDetailUiState.Ready(movie(status = WatchStatus.WATCHLIST, watchedAt = watchedAt)))

        val watchedPrefix = context.getString(R.string.movie_detail_watched_note, "").trim()
        composeRule.onNodeWithText(watchedPrefix, substring = true).assertDoesNotExist()
    }

    @Test
    fun titleIsMarkedAsHeading() {
        setContent(MovieDetailUiState.Ready(movie(title = "Interstellar")))

        composeRule.onNode(isHeading and hasText("Interstellar")).assertExists()
    }

    @Test
    fun coverImageHashNull_rendersNoPosterPlaceholder() {
        // A film has no separate "no poster" placeholder box the way CoverImage's caller in
        // BookDetailsTab does -- MovieDetailScreen's `?.let` skips CoverImage entirely for a null
        // hash (see its KDoc), so neither the loaded image nor CoverImage's own emoji placeholder
        // is ever composed. The emoji Text node is the only semantics hook CoverImage exposes for
        // "was a cover slot drawn at all" -- there is no contentDescription/testTag on it.
        setContent(MovieDetailUiState.Ready(movie()))

        composeRule.onNodeWithText(MOVIE_COVER_PLACEHOLDER_EMOJI).assertDoesNotExist()
    }

    private companion object {
        /**
         * A cover directory that does not exist, matching `MovieDetailScreenGoldenTest`'s fixture.
         *
         * Every test in this class asserts text, controls or navigation; none asserts artwork. A
         * real directory would tie them to device storage for a picture nobody looks at, and
         * `CoverImage` already renders its placeholder when a hash resolves to nothing.
         */
        const val NO_COVERS = "no-covers-in-this-fixture"

        /** [MediaType.MOVIE]'s placeholder glyph in `CoverImage`'s `CoverPlaceholder`. */
        const val MOVIE_COVER_PLACEHOLDER_EMOJI = "🎬"

        /**
         * Long enough to overflow [DetailSynopsis][com.github.maskedkunisquat.mediatracker.ui.components.DetailSynopsis]'s
         * 4-line clamp on a real device at any reasonable font scale -- a short sentence would pass
         * locally and then flake the moment the line wraps differently.
         */
        const val LONG_SYNOPSIS =
            "A team of explorers travel through a wormhole in space in an attempt to ensure " +
                "humanity's survival. With Earth becoming increasingly uninhabitable, a former " +
                "NASA pilot leads a crew through interstellar space to search for a new home " +
                "among the stars, racing against time and the limits of relativity itself, as " +
                "the mission's true cost becomes clear to everyone left behind on a dying world."
    }
}

/**
 * Matches a node carrying [SemanticsProperties.ProgressBarRangeInfo] -- the semantics
 * `CircularProgressIndicator` attaches regardless of whether it is determinate. There is no
 * `contentDescription`/`testTag` on the screen's loading indicator to hook instead.
 */
private val isProgressIndicator =
    SemanticsMatcher("has ProgressBarRangeInfo") {
        it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null
    }

/** Matches a node carrying [SemanticsProperties.Heading], as [DetailHeader] marks its title. */
private val isHeading =
    SemanticsMatcher("is a heading") {
        it.config.getOrNull(SemanticsProperties.Heading) != null
    }
