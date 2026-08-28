package com.github.maskedkunisquat.mediatracker.ui.goldens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.screens.MovieDetailScreen
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.MovieDetailsEntity
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.features.media.data.MediaWithDetails
import com.hub.media.ui.MovieDetailUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Instant

/**
 * Baseline golden for film detail — the screen #99 deliberately did not change.
 *
 * That is the whole reason it is here. It has no scrolling container, so moving padding inside one
 * was not available: the inset stays real padding, and a future refactor that "makes it consistent
 * with the other detail screens" would put its last row under the navigation bar with no way to
 * scroll it clear. Nothing in the repository would fail. This golden is the record of a decision
 * that currently exists only as a comment in the file.
 *
 * Paired with a text assertion rather than a tag (#102 rule 1): this screen carries none.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MovieDetailScreenGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun filmDetail() {
        composeRule.captureGolden(
            name = "film-detail",
            alsoAssert = { assertTextIsShown("Stalker") },
        ) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        MovieDetailScreen(
            uiState =
                MovieDetailUiState.Ready(
                    movie =
                        MediaWithDetails.Movie(
                            item =
                                MediaItemEntity(
                                    id = "film-1",
                                    type = MediaType.MOVIE,
                                    title = "Stalker",
                                    releaseYear = 1979,
                                    purchasePrice = 14.99,
                                    createdAt = Instant.fromEpochMilliseconds(0),
                                    coverImageHash = null,
                                ),
                            details =
                                MovieDetailsEntity(
                                    mediaId = "film-1",
                                    runtimeMinutes = 162,
                                    status = WatchStatus.WATCHED,
                                    watchedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                                ),
                        ),
                ),
            onStatusChange = {},
            onDelete = {},
            onErrorShown = {},
            onNavigateBack = {},
            onNavigateToEditMovie = {},
        )
    }
}
