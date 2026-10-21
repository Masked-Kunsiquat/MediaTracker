package com.github.maskedkunisquat.mediatracker.ui.goldens

import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.screens.MovieHistorySection
import com.hub.media.core.database.entities.WatchLogEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Instant

/** A Watched film's History section (#192): two viewings and Watched again. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlin.time.ExperimentalTime::class)
class MovieDetailHistoryGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun movieDetailHistory() {
        composeRule.captureGolden(
            name = "movie-detail-history",
            alsoAssert = { assertTextIsShown("History", "Jan 10, 2025", "Mar 3, 2024", "Watched again") },
        ) {
            MovieHistorySection(
                viewings =
                    listOf("2025-01-10", "2024-03-03").mapIndexed { i, day ->
                        WatchLogEntity(
                            id = "v$i",
                            mediaId = "m",
                            episodeId = null,
                            watchedAt = Instant.parse("${day}T12:00:00Z"),
                        )
                    },
                isWatched = true,
                onWatchedAgain = {},
                onEditViewing = {},
                onDeleteViewing = {},
                onRateViewing = { _, _ -> },
            )
        }
    }
}
