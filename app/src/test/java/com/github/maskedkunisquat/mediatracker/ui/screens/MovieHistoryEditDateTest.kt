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
 * Edit date in a film's History (#192): tapping a viewing's Edit opens [WatchedDatePickerDialog] on
 * that viewing's own day, and confirming reports that day. Runs in the Robolectric lane since #208: Material's date picker
 * does go idle there (only a *text field* inside a dialog does not, AGENTS.md §7), and making the
 * picker ignore its initial date fails this test on the host.
 * The host mirrors the route.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
class MovieHistoryEditDateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editDate_opensOnTheViewingsDay_andReportsIt() {
        val viewing =
            WatchLogEntity(
                id = "v1",
                mediaId = "m",
                episodeId = null,
                watchedAt = Instant.parse("2024-03-03T12:00:00Z"),
            )
        val picked = mutableListOf<WatchedWhen>()
        val dayMillis = localDateToUtcMidnightMillis(instantToLocalDateTime(viewing.watchedAt).toLocalDate())

        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                var editing by remember { mutableStateOf<WatchLogEntity?>(null) }
                MovieHistorySection(
                    viewings = listOf(viewing),
                    isWatched = true,
                    onWatchedAgain = {},
                    onEditViewing = { editing = it },
                    onDeleteViewing = {},
                    onRateViewing = { _, _ -> },
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
