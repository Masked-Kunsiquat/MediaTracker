package com.github.maskedkunisquat.mediatracker.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.ui.components.RatingStars
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The rating stars (#230): half and whole taps, clearing, and what TalkBack reads and can do. */
@RunWith(RobolectricTestRunner::class)
class RatingStarsTest {
    @get:Rule
    val rule = createComposeRule()

    private var rated: Int? = -1

    private fun show(
        halves: Int?,
        editable: Boolean = true,
    ) {
        rule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                RatingStars(
                    halves = halves,
                    starSize = 40.dp,
                    onRate = if (editable) ({ rated = it }) else null,
                    modifier = Modifier.testTag("stars"),
                )
            }
        }
    }

    /** Taps the stars at [tenths] of their width: 0.5 is the left half of the first star. */
    private fun tapAt(tenths: Float) {
        rule.onNodeWithTag("stars").performTouchInput { click(Offset(width * tenths / 10f, height / 2f)) }
    }

    @Test
    fun leftHalfOfTheFourthStar_ratesThreeAndAHalf() {
        show(null)

        tapAt(6.5f)

        assertEquals(7, rated)
    }

    @Test
    fun rightHalfOfTheFifthStar_ratesFive() {
        show(null)

        tapAt(9.5f)

        assertEquals(10, rated)
    }

    @Test
    fun tappingTheCurrentValue_clearsIt() {
        show(9)

        tapAt(8.5f)

        assertEquals(null, rated)
    }

    @Test
    fun talkBack_readsTheValue_andStepsItThroughProgress() {
        show(9)

        rule
            .onNodeWithTag("stars")
            .assert(hasContentDescription("Your rating, 4.5 of 5"))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }

        assertEquals(10, rated)
    }

    @Test
    fun readOnly_saysNotRated_andCannotBeChanged() {
        show(null, editable = false)

        rule.onNodeWithTag("stars").assert(hasContentDescription("Not rated"))
        val config = rule.onNodeWithTag("stars").fetchSemanticsNode().config
        assertFalse(config.contains(SemanticsActions.SetProgress))
        tapAt(9.5f)
        assertEquals(-1, rated)
    }
}
