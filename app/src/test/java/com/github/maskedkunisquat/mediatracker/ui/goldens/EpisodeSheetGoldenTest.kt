package com.github.maskedkunisquat.mediatracker.ui.goldens

import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.screens.EpisodeSheetContent
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.WatchLogEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Instant

/**
 * The episode sheet's content (#90) with three viewings. The content composable rather than the
 * `ModalBottomSheet` window, which a golden would not capture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlin.time.ExperimentalTime::class)
class EpisodeSheetGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun episodeSheet() {
        composeRule.captureGolden(
            name = "episode-sheet",
            alsoAssert = {
                assertTextIsShown(
                    "Please Remain Calm",
                    "Viewings",
                    "Feb 20, 2026",
                    "Mar 3, 2024",
                    "Watched again",
                )
            },
        ) {
            EpisodeSheetContent(
                episode =
                    EpisodeEntity(
                        id = "e1",
                        mediaId = "m",
                        seasonNumber = 1,
                        episodeNumber = 2,
                        title = "Please Remain Calm",
                        airDate = Instant.parse("2019-05-13T00:00:00Z"),
                    ),
                viewings =
                    listOf("2026-02-20", "2025-01-10", "2024-03-03").mapIndexed { i, day ->
                        WatchLogEntity(
                            id = "v$i",
                            mediaId = "m",
                            episodeId = "e1",
                            watchedAt = Instant.parse("${day}T12:00:00Z"),
                        )
                    },
                onTick = {},
                onWatchedAgain = {},
                onEditViewing = {},
                onDeleteViewing = {},
            )
        }
    }
}
