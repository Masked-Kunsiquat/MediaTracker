package com.github.maskedkunisquat.mediatracker.ui.screens

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
            ),
        details =
            MovieDetailsEntity(
                mediaId = id,
                runtimeMinutes = runtimeMinutes,
                status = status,
                watchedAt = null,
            ),
    )

    private fun setContent(
        uiState: MovieDetailUiState,
        onStatusChange: (WatchStatus) -> Unit = {},
        onDelete: () -> Unit = {},
        onErrorShown: () -> Unit = {},
        onNavigateBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                MovieDetailScreen(
                    uiState = uiState,
                    onStatusChange = onStatusChange,
                    onDelete = onDelete,
                    onErrorShown = onErrorShown,
                    onNavigateBack = onNavigateBack,
                )
            }
        }
    }

    @Test
    fun ready_rendersTitleReleaseYearAndRuntime() {
        setContent(MovieDetailUiState.Ready(movie(title = "Interstellar", releaseYear = 2014, runtimeMinutes = 169)))

        composeRule.onNodeWithText("Interstellar").assertIsDisplayed()

        val yearText = context.getString(R.string.library_year_label, 2014)
        composeRule.onNodeWithText(yearText).assertIsDisplayed()

        val runtimeText = context.getString(R.string.movie_detail_runtime, 169)
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

    @Test
    fun tappingAStatusChip_invokesOnStatusChangeWithThatStatus() {
        var captured: WatchStatus? = null
        setContent(
            MovieDetailUiState.Ready(movie(status = WatchStatus.WATCHLIST)),
            onStatusChange = { captured = it },
        )

        val watchedLabel = context.getString(R.string.watch_status_watched)
        composeRule.onNodeWithText(watchedLabel).performClick()

        assertEquals(WatchStatus.WATCHED, captured)
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
}
