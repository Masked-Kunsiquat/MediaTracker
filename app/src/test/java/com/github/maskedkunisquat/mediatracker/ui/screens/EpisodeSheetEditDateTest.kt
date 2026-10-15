package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import com.github.maskedkunisquat.mediatracker.ui.components.WatchedDatePickerDialog
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.WatchLogEntity
import com.hub.media.features.tv.domain.WatchedWhen
import com.hub.media.features.tv.domain.watchedOnPickedDay
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

/**
 * Edit date on the episode sheet (#90): tapping a viewing's Edit opens [WatchedDatePickerDialog] on
 * that viewing's own day, and confirming reports that day. Device-only, because a dialog holding a
 * date input does not go idle under Robolectric (AGENTS.md §7). The host below mirrors the route:
 * the sheet content reports which viewing to edit, the host opens the picker for it.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
class EpisodeSheetEditDateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editDate_opensOnTheViewingsDay_andReportsIt() {
        val viewing =
            WatchLogEntity(
                id = "v1",
                mediaId = "m",
                episodeId = "e1",
                watchedAt = Instant.parse("2024-03-03T12:00:00Z"),
            )
        val picked = mutableListOf<WatchedWhen>()
        val dayMillis = localDateToUtcMidnightMillis(instantToLocalDateTime(viewing.watchedAt).toLocalDate())

        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                var editing by remember { mutableStateOf<WatchLogEntity?>(null) }
                EpisodeSheetContent(
                    episode =
                        EpisodeEntity(
                            id = "e1",
                            mediaId = "m",
                            seasonNumber = 1,
                            episodeNumber = 1,
                            title = "Pilot",
                        ),
                    viewings = listOf(viewing),
                    onTick = {},
                    onWatchedAgain = {},
                    onEditViewing = { editing = it },
                    onDeleteViewing = {},
                )
                editing?.let { log ->
                    WatchedDatePickerDialog(
                        initialPickMillis =
                            localDateToUtcMidnightMillis(
                                instantToLocalDateTime(log.watchedAt).toLocalDate(),
                            ),
                        confirmLabel = "Save",
                        onPicked = {
                            picked += it
                            editing = null
                        },
                        onDismiss = { editing = null },
                    )
                }
            }
        }

        composeRule.onAllNodesWithContentDescription("Edit date", substring = true)[0].performClick()
        composeRule.onNode(hasText("Save") and hasClickAction()).performClick()

        assertEquals(listOf(watchedOnPickedDay(dayMillis)), picked)
    }
}
