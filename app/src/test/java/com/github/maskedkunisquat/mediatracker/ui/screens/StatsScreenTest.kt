package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.features.stats.data.GoalProgress
import com.hub.media.features.stats.data.GoalStatus
import com.hub.media.features.stats.data.StatsPeriod
import com.hub.media.ui.StatsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The stats screen's period picker and single card (#91), driven statelessly. */
@RunWith(RobolectricTestRunner::class)
class StatsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val picked = mutableListOf<StatsPeriod>()

    private fun string(id: Int) = context.getString(id)

    private fun setContent(state: StatsUiState) {
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                StatsScreen(
                    uiState = state,
                    onSelectPeriod = { picked += it },
                    onSetGoal = {},
                    onClearGoal = {},
                    onNavigateBack = {},
                )
            }
        }
    }

    private fun loaded(
        period: StatsPeriod = StatsPeriod.WEEK,
        stats: StatsUiState.Period = StatsUiState.Period(3600L, 4, 120, 1),
        goal: GoalProgress? = null,
        finishesByMonth: List<Int>? = null,
    ) = StatsUiState(
        isLoading = false,
        period = period,
        stats = stats,
        currentStreakDays = 3,
        goal = goal,
        goalYear = 2024,
        finishesByMonth = finishesByMonth,
    )

    private fun scrollTo(text: String) {
        composeRule.onNodeWithTag(TestTags.Stats.LIST).performScrollToNode(hasText(text))
    }

    private fun chip(id: Int) = composeRule.onNode(hasText(string(id)) and hasClickAction())

    @Test
    fun everyChip_selectsItsPeriod() {
        setContent(loaded())
        val chips =
            listOf(
                R.string.stats_period_week to StatsPeriod.WEEK,
                R.string.stats_period_month to StatsPeriod.MONTH,
                R.string.stats_period_30_days to StatsPeriod.LAST_30_DAYS,
                R.string.stats_period_year to StatsPeriod.YEAR,
                R.string.stats_period_all to StatsPeriod.ALL_TIME,
            )

        for ((label, _) in chips) {
            composeRule.onNodeWithTag(TestTags.Stats.PERIODS).performScrollToNode(hasText(string(label)))
            chip(label).performClick()
        }

        assertEquals(chips.map { it.second }, picked)
    }

    @Test
    fun theSelectedChip_reportsSelected() {
        setContent(loaded(period = StatsPeriod.MONTH))

        chip(R.string.stats_period_month).assertIsSelected()
    }

    @Test
    fun theCardTitle_followsThePeriod() {
        val titles =
            mapOf(
                StatsPeriod.WEEK to R.string.stats_this_week_title,
                StatsPeriod.MONTH to R.string.stats_this_month_title,
                StatsPeriod.LAST_30_DAYS to R.string.stats_last_30_days_title,
                StatsPeriod.YEAR to R.string.stats_this_year_title,
                StatsPeriod.ALL_TIME to R.string.stats_all_time_title,
            )
        var period by mutableStateOf(StatsPeriod.WEEK)
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                StatsScreen(
                    uiState = loaded(period = period),
                    onSelectPeriod = {},
                    onSetGoal = {},
                    onClearGoal = {},
                    onNavigateBack = {},
                )
            }
        }

        for ((p, title) in titles) {
            composeRule.runOnIdle { period = p }
            // The card's title, not the chip: "All time" is both a chip label and a title.
            composeRule.onNode(hasText(string(title)) and !hasClickAction()).assertExists()
        }
    }

    @Test
    fun anUnknownTime_isShownAsUnknown_notZero() {
        setContent(loaded(stats = StatsUiState.Period(null, 2, null, 0)))

        composeRule
            .onNodeWithText(context.getString(R.string.stats_time_read_label, string(R.string.stats_unknown_value)))
            .assertExists()
    }

    @Test
    fun theLifetimeCardIsGone_allTimeTakesItsPlace() {
        setContent(loaded())

        // Positive control: the streak card is drawn.
        composeRule
            .onNodeWithText(
                context.resources.getQuantityString(R.plurals.stats_streak_days, 3, 3),
            ).assertExists()
        composeRule.onNodeWithText("Books finished (lifetime)").assertDoesNotExist()
    }

    @Test
    fun whileLoading_noChipsYet() {
        setContent(StatsUiState(isLoading = true))

        composeRule.onNodeWithTag(TestTags.Stats.PERIODS).assertDoesNotExist()
    }

    @Test
    fun theChipRow_isOneChoice_forTalkBack() {
        setContent(loaded())

        composeRule
            .onNodeWithTag(TestTags.Stats.PERIODS)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
    }

    /**
     * The phone this was hand-checked on (SM-G975U1) cuts the last chip off at the edge at normal
     * font size, so a remembered All time opened with its own chip half hidden. A narrow screen
     * reproduces that here. NATIVE graphics, because Robolectric's legacy pipeline measures text far
     * too narrow to overflow anything (AGENTS.md section 7) -- without it every chip fits and this
     * test passes with the bug in place, which is how it first behaved.
     */
    @Test
    @Config(qualifiers = "w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aRememberedFarPeriod_opensWithItsChipFullyInView() {
        setContent(loaded(period = StatsPeriod.ALL_TIME))

        // Unclipped: a node's ordinary bounds are clipped to the visible part of the row.
        val row = composeRule.onNodeWithTag(TestTags.Stats.PERIODS).getUnclippedBoundsInRoot()
        val all = chip(R.string.stats_period_all).getUnclippedBoundsInRoot()
        assertTrue(
            "All time chip $all is not fully inside the row $row",
            all.left >= row.left && all.right <= row.right,
        )
    }

    // --- #219: speed and session lines, the goal card, the month chart -------------------------

    @Test
    fun theSpeedAndSessionLines_showTheirValues() {
        setContent(
            loaded(
                stats =
                    StatsUiState.Period(
                        timeReadSeconds = 7_200,
                        sessionCount = 3,
                        pagesRead = 90,
                        booksFinished = 0,
                        averageSessionSeconds = 2_400,
                        longestSessionSeconds = 4_500,
                        pagesPerHour = 45,
                    ),
            ),
        )

        val speed = context.getString(R.string.stats_speed_label, context.getString(R.string.stats_pages_per_hour, 45))
        composeRule.onNodeWithText(speed).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.stats_average_session_label, "40m")).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.stats_longest_session_label, "1h 15m")).assertExists()
    }

    @Test
    fun unknownSpeedAndSessions_areShownAsUnknown_notZero() {
        setContent(loaded(stats = StatsUiState.Period(null, 2, null, 0)))
        val unknown = string(R.string.stats_unknown_value)

        composeRule.onNodeWithText(context.getString(R.string.stats_speed_label, unknown)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.stats_average_session_label, unknown)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.stats_longest_session_label, unknown)).assertExists()
    }

    @Test
    fun withNoGoal_theCardOffersOneForThisYear_andCanBeTapped() {
        setContent(loaded())
        val offer = context.getString(R.string.stats_goal_set_prompt, 2024)

        scrollTo(offer)
        composeRule.onNodeWithTag(TestTags.Stats.GOAL_CARD).assert(hasClickAction())
        composeRule.onNodeWithText(offer).assertExists()
    }

    @Test
    fun aGoalOnPace_showsProgressAndPace() {
        setContent(loaded(goal = GoalProgress(goal = 12, finished = 5, expected = 5, status = GoalStatus.OnPace)))
        val progress = context.resources.getQuantityString(R.plurals.stats_goal_progress, 12, 5, 12)

        scrollTo(progress)
        composeRule.onNodeWithText(progress).assertExists()
        composeRule.onNodeWithText(string(R.string.stats_goal_on_pace)).assertExists()
        composeRule.onNodeWithTag(TestTags.Stats.GOAL_CARD).assert(hasClickAction())
    }

    @Test
    fun aGoalBehind_saysByHowMany() {
        setContent(loaded(goal = GoalProgress(goal = 12, finished = 2, expected = 5, status = GoalStatus.Behind(4))))
        val behind = context.resources.getQuantityString(R.plurals.stats_goal_behind, 4, 4)

        scrollTo(behind)
        composeRule.onNodeWithText(behind).assertExists()
    }

    @Test
    fun aGoalReached_saysSo() {
        setContent(loaded(goal = GoalProgress(goal = 3, finished = 4, expected = 1, status = GoalStatus.Reached)))
        val reached = string(R.string.stats_goal_reached)

        scrollTo(reached)
        composeRule.onNodeWithText(reached).assertExists()
    }

    @Test
    fun theMonthChart_isDrawnOnTheYearView_andListsTheMonthsAloud() {
        val months =
            List(12) {
                if (it == 5) {
                    2
                } else if (it == 0) {
                    1
                } else {
                    0
                }
            }
        setContent(loaded(period = StatsPeriod.YEAR, finishesByMonth = months))

        composeRule.onNodeWithTag(TestTags.Stats.LIST).performScrollToNode(hasTestTag(TestTags.Stats.MONTH_CHART))
        val description =
            composeRule
                .onNodeWithTag(TestTags.Stats.MONTH_CHART)
                .fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription]
                .joinToString()
        assertTrue(description, "January" in description && "June" in description)
        assertTrue(description, "March" !in description)
    }

    @Test
    fun anEmptyYear_saysNoBooksFinishedYet() {
        setContent(loaded(period = StatsPeriod.YEAR, finishesByMonth = List(12) { 0 }))

        composeRule.onNodeWithTag(TestTags.Stats.LIST).performScrollToNode(hasTestTag(TestTags.Stats.MONTH_CHART))
        composeRule
            .onNodeWithTag(TestTags.Stats.MONTH_CHART)
            .assert(hasContentDescription(string(R.string.stats_month_chart_empty)))
    }

    @Test
    fun noMonthChart_offTheYearView() {
        setContent(loaded(period = StatsPeriod.WEEK))

        // Positive control: the screen is drawn.
        composeRule.onNodeWithTag(TestTags.Stats.PERIODS).assertExists()
        composeRule.onNodeWithTag(TestTags.Stats.MONTH_CHART).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.stats_month_chart_title)).assertDoesNotExist()
    }
}
