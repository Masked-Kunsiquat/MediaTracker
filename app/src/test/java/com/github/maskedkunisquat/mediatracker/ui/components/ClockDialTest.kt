package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [ClockDial] (#217): one wedge per hour, so anything but 24 values is a caller's mistake, loudly. */
@RunWith(RobolectricTestRunner::class)
class ClockDialTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun clockDial_needsTwentyFourHours() {
        assertThrows(IllegalArgumentException::class.java) {
            composeRule.setContent {
                MediaTrackerTheme(dynamicColor = false) {
                    ClockDial(hours = List(23) { 1L }, busiest = "Evenings", description = "x")
                }
            }
        }
    }

    @Test
    fun twentyFourHours_drawAsOneDescribedNode() {
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                ClockDial(hours = List(24) { it.toLong() }, busiest = "Evenings", description = "Evening: 2h 0m")
            }
        }

        composeRule.onNodeWithContentDescription("Evening: 2h 0m").assertExists()
    }
}
