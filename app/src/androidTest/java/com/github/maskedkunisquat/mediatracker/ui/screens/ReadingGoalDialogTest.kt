package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.features.stats.data.GoalProgress
import com.hub.media.features.stats.data.GoalStatus
import com.hub.media.features.stats.data.StatsPeriod
import com.hub.media.ui.StatsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The reading-goal dialog (#219), reached the way a user reaches it: tap the goal card on the stats
 * screen. These live on a device rather than in the Robolectric lane's `StatsScreenTest` because the
 * dialog holds a text field, and under Robolectric Compose never goes idle once such a dialog opens
 * -- typing or not -- and the hang poisons every later test in the JVM (AGENTS.md section 7).
 */
class ReadingGoalDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val saved = mutableListOf<Int>()
    private var cleared = 0

    private fun string(id: Int) = context.getString(id)

    private fun openDialog(goal: GoalProgress? = null) {
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                StatsScreen(
                    uiState =
                        StatsUiState(
                            isLoading = false,
                            period = StatsPeriod.WEEK,
                            goal = goal,
                            goalYear = 2024,
                        ),
                    onSelectPeriod = {},
                    onSelectDomain = {},
                    onSetGoal = { saved += it },
                    onClearGoal = { cleared++ },
                    onNavigateBack = {},
                )
            }
        }
        composeRule
            .onNodeWithTag(TestTags.Stats.LIST)
            .performScrollToNode(hasTestTag(TestTags.Stats.GOAL_CARD))
        composeRule.onNodeWithTag(TestTags.Stats.GOAL_CARD).performClick()
    }

    private fun field() = composeRule.onNode(hasSetTextAction())

    private fun button(id: Int) = composeRule.onNode(hasText(string(id)) and hasClickAction())

    @Test
    fun typingAGoal_andSaving_setsIt() {
        openDialog()

        field().performTextInput("12")
        button(R.string.save_button).performClick()

        assertEquals(listOf(12), saved)
        // The dialog closes on save.
        field().assertDoesNotExist()
    }

    @Test
    fun zero_cannotBeSaved() {
        openDialog()

        field().performTextInput("0")

        button(R.string.save_button).assertIsNotEnabled()
        // Positive control: a real number enables it.
        field().performTextClearance()
        field().performTextInput("7")
        button(R.string.save_button).assertIsEnabled()
    }

    @Test
    fun anExistingGoal_isPrefilled_andCanBeRemoved() {
        openDialog(goal = GoalProgress(goal = 12, finished = 5, expected = 5, status = GoalStatus.OnPace))

        composeRule.onNode(hasSetTextAction() and hasText("12")).assertExists()
        button(R.string.stats_goal_remove).performClick()

        assertEquals(1, cleared)
        assertEquals(emptyList<Int>(), saved)
    }

    @Test
    fun withNoGoal_thereIsNothingToRemove() {
        openDialog()

        // Positive control: the dialog is open.
        field().assertExists()
        composeRule.onNodeWithText(string(R.string.stats_goal_remove)).assertDoesNotExist()
    }

    @Test
    fun cancel_closesWithoutChangingAnything() {
        openDialog(goal = GoalProgress(goal = 12, finished = 5, expected = 5, status = GoalStatus.OnPace))

        field().performTextClearance()
        field().performTextInput("30")
        button(R.string.cancel_button).performClick()

        field().assertDoesNotExist()
        assertEquals(emptyList<Int>(), saved)
        assertEquals(0, cleared)
    }
}
