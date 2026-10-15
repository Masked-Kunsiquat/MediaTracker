package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.features.tv.domain.WatchedWhen
import com.hub.media.features.tv.domain.watchedOnPickedDay
import com.hub.media.ui.PendingTick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The date-picker half of [MarkWatchedDialog] (#131). Runs in the Robolectric lane since #208: Material's date picker
 * does go idle there (only a *text field* inside a dialog does not, AGENTS.md §7), and making the
 * picker ignore its initial date fails this test on the host. Which days are pickable is pure logic, tested in
 * `WatchTimesTest`; this proves the picker opens on the air date and confirms that day.
 */
@RunWith(RobolectricTestRunner::class)
class MarkWatchedDialogPickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** 2024-02-16 at midnight UTC -- how Material's DatePicker represents a day. */
    private val airDateMillis = 1_708_041_600_000L

    @Test
    fun pickADate_opensOnTheAirDate_andConfirmsThatDay() {
        val confirmed = mutableListOf<Pair<WatchedWhen, Boolean>>()
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                MarkWatchedDialog(
                    tick =
                        PendingTick(
                            title = "Please Remain Calm",
                            seasonNumber = 1,
                            episodeNumber = 4,
                            isSeason = false,
                            targetIds = listOf("t"),
                            earlierIds = emptyList(),
                            airDateAvailable = true,
                            airDateAvailableWithEarlier = true,
                            initialPickMillis = airDateMillis,
                        ),
                    onConfirm = { w, e -> confirmed += w to e },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNode(hasText("Pick a date…") and hasClickAction()).performClick()
        composeRule.onNode(hasText("Mark watched") and hasClickAction()).performClick()

        assertEquals(listOf(watchedOnPickedDay(airDateMillis) to false), confirmed)
    }
}
