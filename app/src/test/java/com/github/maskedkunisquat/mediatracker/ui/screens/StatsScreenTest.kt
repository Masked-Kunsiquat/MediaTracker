package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
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
import com.hub.media.core.database.dao.FinishOutcomes
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.features.stats.data.AuthorTime
import com.hub.media.features.stats.data.FormatTime
import com.hub.media.features.stats.data.GoalProgress
import com.hub.media.features.stats.data.GoalStatus
import com.hub.media.features.stats.data.ReadingBreakdown
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
        breakdown: ReadingBreakdown = ReadingBreakdown(),
        finishOutcomes: FinishOutcomes? = null,
    ) = StatsUiState(
        isLoading = false,
        period = period,
        stats = stats,
        currentStreakDays = 3,
        goal = goal,
        goalYear = 2024,
        finishesByMonth = finishesByMonth,
        breakdown = breakdown,
        finishOutcomes = finishOutcomes,
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

    // --- Part 2 (#219): when you read, top authors, formats, did not finish ----------------------

    private val week = List(7) { it + 1 } // ISO day numbers, Monday first

    private fun breakdown(
        dayParts: List<Long> = listOf(3_600L, 0L, 1_800L, 0L),
        authors: List<AuthorTime> = listOf(AuthorTime("Ursula K. Le Guin", 3_600)),
        formats: List<FormatTime> = listOf(FormatTime(BookFormat.PAPERBACK, 5_400)),
    ) = ReadingBreakdown(
        dayParts = dayParts,
        weekdays = week,
        weekdaySeconds = listOf(dayParts.sum(), 0L, 0L, 0L, 0L, 0L, 0L),
        topAuthors = authors,
        formats = formats,
        totalSeconds = dayParts.sum(),
    )

    private fun inCard(
        cardTag: String,
        text: String,
    ): SemanticsNodeInteraction {
        composeRule.onNodeWithTag(TestTags.Stats.LIST).performScrollToNode(hasTestTag(cardTag))
        return composeRule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(cardTag)))
    }

    @Test
    fun aPopulatedBreakdown_fillsTheThreeCards() {
        setContent(loaded(breakdown = breakdown()))

        inCard(TestTags.Stats.WHEN_CARD, string(R.string.stats_when_title)).assertExists()
        composeRule.onNodeWithTag(TestTags.Stats.DAYPART_CHART).assertExists()
        composeRule.onNodeWithTag(TestTags.Stats.WEEKDAY_CHART).assertExists()
        inCard(TestTags.Stats.AUTHORS_CARD, "Ursula K. Le Guin").assertExists()
        inCard(TestTags.Stats.AUTHORS_CARD, "1h 0m").assertExists()
        inCard(TestTags.Stats.FORMATS_CARD, string(R.string.book_format_paperback)).assertExists()
        inCard(TestTags.Stats.FORMATS_CARD, "100%").assertExists()
    }

    @Test
    fun noTimedReading_eachCardSaysSo_andDrawsNoChart() {
        setContent(loaded(breakdown = ReadingBreakdown(weekdays = week)))
        val none = string(R.string.stats_no_timed_reading)

        inCard(TestTags.Stats.WHEN_CARD, none).assertExists()
        inCard(TestTags.Stats.AUTHORS_CARD, none).assertExists()
        inCard(TestTags.Stats.FORMATS_CARD, none).assertExists()
        composeRule.onNodeWithTag(TestTags.Stats.DAYPART_CHART).assertDoesNotExist()
        composeRule.onNodeWithTag(TestTags.Stats.WEEKDAY_CHART).assertDoesNotExist()
    }

    @Test
    fun aLongAuthorName_keepsItsTimeOnTheRow() {
        val long = "Sir Terence David John Pratchett, with a name long enough to run off the edge"
        setContent(loaded(breakdown = breakdown(authors = listOf(AuthorTime(long, 7_200)))))

        inCard(TestTags.Stats.AUTHORS_CARD, long).assertExists()
        inCard(TestTags.Stats.AUTHORS_CARD, "2h 0m").assertExists()
    }

    @Test
    fun aSliverOfTime_isUnderOnePercent_notZero() {
        val formats = listOf(FormatTime(BookFormat.PAPERBACK, 9_990), FormatTime(BookFormat.EBOOK, 10))
        setContent(loaded(breakdown = breakdown(dayParts = listOf(10_000L, 0L, 0L, 0L), formats = formats)))

        inCard(TestTags.Stats.FORMATS_CARD, string(R.string.stats_percent_under_one)).assertExists()
        inCard(TestTags.Stats.FORMATS_CARD, "0%").assertDoesNotExist()
    }

    @Test
    fun didNotFinish_showsOnAllTime() {
        setContent(loaded(period = StatsPeriod.ALL_TIME, finishOutcomes = FinishOutcomes(finished = 12, dnf = 2)))
        val value = context.resources.getQuantityString(R.plurals.stats_dnf_value, 14, 2, 14, 14)

        composeRule.onNodeWithText(context.getString(R.string.stats_dnf_label, value)).assertExists()
    }

    @Test
    fun didNotFinish_withNothingToCount_isUnknown() {
        setContent(loaded(period = StatsPeriod.ALL_TIME, finishOutcomes = FinishOutcomes(0, 0)))

        composeRule
            .onNodeWithText(context.getString(R.string.stats_dnf_label, string(R.string.stats_unknown_value)))
            .assertExists()
    }

    @Test
    fun didNotFinish_isNotShownOffAllTime() {
        setContent(loaded(period = StatsPeriod.WEEK, finishOutcomes = null))

        // Positive control: the period card is drawn.
        composeRule.onNode(hasText(string(R.string.stats_this_week_title)) and !hasClickAction()).assertExists()
        composeRule.onNode(hasText("Did not finish", substring = true)).assertDoesNotExist()
    }

    @Test
    fun thePartOfDayChart_namesOnlyThePartsWithTime() {
        setContent(loaded(breakdown = breakdown(dayParts = listOf(3_600L, 0L, 0L, 0L))))

        composeRule.onNodeWithTag(TestTags.Stats.LIST).performScrollToNode(hasTestTag(TestTags.Stats.DAYPART_CHART))
        val description =
            composeRule
                .onNodeWithTag(TestTags.Stats.DAYPART_CHART)
                .fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription]
                .joinToString()
        assertTrue(description, string(R.string.stats_daypart_morning) in description)
        assertTrue(description, string(R.string.stats_daypart_afternoon) !in description)
    }
}
