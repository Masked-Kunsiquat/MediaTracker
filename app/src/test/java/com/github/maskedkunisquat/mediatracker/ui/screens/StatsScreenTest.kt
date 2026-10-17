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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.dao.FinishOutcomes
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.MediaType
import com.hub.media.features.stats.data.AuthorTime
import com.hub.media.features.stats.data.FormatTime
import com.hub.media.features.stats.data.GoalProgress
import com.hub.media.features.stats.data.GoalStatus
import com.hub.media.features.stats.data.ReadingBreakdown
import com.hub.media.features.stats.data.StatsPeriod
import com.hub.media.features.stats.data.TitleTime
import com.hub.media.features.stats.data.ViewingBreakdown
import com.hub.media.ui.StatsUiState
import com.hub.media.ui.ViewingStats
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
    private val domains = mutableListOf<MediaType>()
    private var yearReviews = 0

    private fun string(id: Int) = context.getString(id)

    private fun setContent(state: StatsUiState) {
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                StatsScreen(
                    uiState = state,
                    onSelectPeriod = { picked += it },
                    onSelectDomain = { domains += it },
                    onOpenYearReview = { yearReviews++ },
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
                    onSelectDomain = {},
                    onOpenYearReview = {},
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
            .assert(hasContentDescription(string(R.string.stats_month_chart_empty), substring = true))
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
        // Consistent with dayParts, as real data always is: each part's time in one hour inside it.
        hours = List(24) { h -> mapOf(8 to 0, 14 to 1, 18 to 2, 22 to 3)[h]?.let { dayParts[it] } ?: 0L },
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

        inCard(TestTags.Stats.CHARTS_CARD, string(R.string.stats_charts_title)).assertExists()
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

        inCard(TestTags.Stats.CHARTS_CARD, none).assertExists()
        inCard(TestTags.Stats.AUTHORS_CARD, none).assertExists()
        inCard(TestTags.Stats.FORMATS_CARD, none).assertExists()
        composeRule.onNodeWithTag(TestTags.Stats.DAYPART_CHART).assertDoesNotExist()
        composeRule.onNodeWithTag(TestTags.Stats.WEEKDAY_CHART).assertDoesNotExist()
    }

    /**
     * The time must stay on the row, visibly, however long the name: bounds, not mere existence, since
     * a squeezed-out time Text is still in the semantics tree. NATIVE graphics, because Robolectric's
     * legacy text measures too narrow for a long name to overflow anything (AGENTS.md section 7).
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aLongAuthorName_keepsItsTimeOnTheRow() {
        val long = "Sir Terence David John Pratchett, with a name long enough to run off the edge"
        setContent(loaded(breakdown = breakdown(authors = listOf(AuthorTime(long, 7_200)))))

        inCard(TestTags.Stats.AUTHORS_CARD, long).assertExists()
        val card = composeRule.onNodeWithTag(TestTags.Stats.AUTHORS_CARD).getUnclippedBoundsInRoot()
        val time = inCard(TestTags.Stats.AUTHORS_CARD, "2h 0m").getUnclippedBoundsInRoot()
        assertTrue("time $time has no width", time.width > 0.dp)
        assertTrue("time $time runs past the card $card", time.right <= card.right)
    }

    /** Real data: every timed book has no author on record. The card says so instead of standing empty. */
    @Test
    fun timedReadingWithNoAuthorsOnRecord_saysSo() {
        setContent(loaded(breakdown = breakdown(authors = emptyList())))

        inCard(TestTags.Stats.AUTHORS_CARD, string(R.string.stats_no_authors)).assertExists()
    }

    /** Next to a sliver shown as "<1%", the big share must not claim all of it. */
    @Test
    fun aShareBesideASliver_isNeverAHundredPercent() {
        val formats = listOf(FormatTime(BookFormat.PAPERBACK, 9_960), FormatTime(BookFormat.EBOOK, 40))
        setContent(loaded(breakdown = breakdown(dayParts = listOf(10_000L, 0L, 0L, 0L), formats = formats)))

        inCard(TestTags.Stats.FORMATS_CARD, string(R.string.stats_percent_over_99)).assertExists()
        inCard(TestTags.Stats.FORMATS_CARD, "100%").assertDoesNotExist()
    }

    @Test
    fun aSingleFormat_isAHundredPercent() {
        setContent(loaded(breakdown = breakdown()))

        inCard(TestTags.Stats.FORMATS_CARD, "100%").assertExists()
    }

    @Test
    fun oneDnfInThreeHundred_isUnderOnePercent_notZero() {
        setContent(loaded(period = StatsPeriod.ALL_TIME, finishOutcomes = FinishOutcomes(finished = 299, dnf = 1)))
        val value =
            context.resources.getQuantityString(
                R.plurals.stats_dnf_value,
                300,
                1,
                300,
                string(R.string.stats_percent_under_one),
            )

        composeRule.onNodeWithText(context.getString(R.string.stats_dnf_label, value)).assertExists()
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
        // 1 of 4 is 25%: the percent and the total differ, so swapping them in the string fails.
        setContent(loaded(period = StatsPeriod.ALL_TIME, finishOutcomes = FinishOutcomes(finished = 3, dnf = 1)))
        val value = context.resources.getQuantityString(R.plurals.stats_dnf_value, 4, 1, 4, "25%")

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

    // --- #217: charts as tiles that open larger -------------------------------------------------

    private fun hourly(vararg busy: Pair<Int, Long>): List<Long> =
        List(24) { h ->
            busy.firstOrNull { it.first == h }?.second
                ?: 0L
        }

    private fun timed() =
        breakdown(dayParts = listOf(0L, 0L, 7_200L, 0L)).copy(hours = hourly(19 to 3_600L, 20 to 3_600L))

    private fun scrollToTile(tag: String) {
        composeRule.onNodeWithTag(TestTags.Stats.LIST).performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun theTimeOfDayTile_isAButton_captionedWithTheBusiestPart() {
        setContent(loaded(breakdown = timed()))

        scrollToTile(TestTags.Stats.DAYPART_CHART)
        composeRule.onNodeWithTag(TestTags.Stats.DAYPART_CHART).assert(hasClickAction())
        inCard(TestTags.Stats.CHARTS_CARD, string(R.string.stats_daypart_evenings)).assertExists()
    }

    @Test
    fun tappingTheTimeOfDayTile_showsItLarger_withItsNumbers_andCloseDismisses() {
        setContent(loaded(breakdown = timed()))
        scrollToTile(TestTags.Stats.DAYPART_CHART)

        composeRule.onNodeWithTag(TestTags.Stats.DAYPART_CHART).performClick()

        // The legend: the evening's two hours, in words, only in the larger view.
        composeRule.onNodeWithText(string(R.string.stats_daypart_evening)).assertExists()
        composeRule.onNodeWithText("2h 0m").assertExists()
        composeRule.onNode(hasText(string(R.string.stats_close)) and hasClickAction()).performClick()
        composeRule.onNodeWithText(string(R.string.stats_close)).assertDoesNotExist()
    }

    @Test
    fun theMonthTile_isOnlyOnTheYearView() {
        setContent(
            loaded(
                period = StatsPeriod.YEAR,
                breakdown = timed(),
                finishesByMonth =
                    List(12) {
                        if (it ==
                            1
                        ) {
                            2
                        } else {
                            0
                        }
                    },
            ),
        )

        scrollToTile(TestTags.Stats.MONTH_CHART)
        composeRule.onNodeWithTag(TestTags.Stats.MONTH_CHART).assert(hasClickAction())
        inCard(TestTags.Stats.CHARTS_CARD, string(R.string.stats_chart_by_month)).assertExists()
    }

    @Test
    fun theWeekdayTile_isCaptionedWithTheBusiestDay() {
        // The fixture's weekday seconds all fall on its first day, a Monday.
        setContent(loaded(breakdown = timed()))

        scrollToTile(TestTags.Stats.WEEKDAY_CHART)
        inCard(TestTags.Stats.CHARTS_CARD, "Monday").assertExists()
    }

    // --- #217: the Films and Shows tabs ----------------------------------------------------------

    private fun viewing(
        domain: MediaType,
        breakdown: ViewingBreakdown,
        period: StatsPeriod = StatsPeriod.MONTH,
        showsFinished: Int? = null,
    ) = StatsUiState(
        isLoading = false,
        period = period,
        domain = domain,
        goalYear = 2024,
        viewing = ViewingStats(breakdown, showsFinished),
    )

    private fun watched(
        viewings: Int = 9,
        rewatches: Int = 2,
        titles: Int = 4,
        knownSeconds: Long? = 51_600,
        unknownRuntime: Int = 1,
        hours: List<Int> = List(24) { if (it == 20) 9 else 0 },
        untimed: Int = 0,
        topTitles: List<TitleTime> = emptyList(),
    ) = ViewingBreakdown(
        viewings = viewings,
        rewatches = rewatches,
        titles = titles,
        knownSeconds = knownSeconds,
        unknownRuntime = unknownRuntime,
        hours = hours,
        dayParts = listOf(0, 0, hours.sum(), 0),
        untimed = untimed,
        weekdays = week,
        weekdayCounts = listOf(viewings, 0, 0, 0, 0, 0, 0),
        topTitles = topTitles,
    )

    private fun domainChip(id: Int) =
        composeRule.onNode(
            hasText(string(id)) and hasClickAction() and hasAnyAncestor(hasTestTag(TestTags.Stats.DOMAINS)),
        )

    @Test
    fun theDomainChips_pickATab_andShowWhichIsSelected() {
        setContent(viewing(MediaType.MOVIE, watched()))

        domainChip(R.string.stats_domain_films).assertIsSelected()
        domainChip(R.string.stats_domain_shows).performClick()
        domainChip(R.string.stats_domain_books).performClick()

        assertEquals(listOf(MediaType.TV_SHOW, MediaType.BOOK), domains)
        composeRule
            .onNodeWithTag(
                TestTags.Stats.DOMAINS,
            ).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
    }

    @Test
    fun films_countViewings_withRewatches() {
        setContent(viewing(MediaType.MOVIE, watched(viewings = 9, rewatches = 2)))

        composeRule
            .onNodeWithText(
                context.resources.getQuantityString(R.plurals.stats_films_watched_rewatches, 2, 9, 2),
            ).assertExists()
    }

    @Test
    fun films_withNoRewatches_justCountViewings() {
        setContent(viewing(MediaType.MOVIE, watched(viewings = 3, rewatches = 0)))

        composeRule.onNodeWithText(context.getString(R.string.stats_films_watched, 3)).assertExists()
    }

    @Test
    fun timeWatched_namesTheViewingsWithoutARuntime() {
        setContent(viewing(MediaType.MOVIE, watched(knownSeconds = 51_600, unknownRuntime = 1)))
        val value = context.getString(R.string.stats_time_with_unknown, "14h 20m", 1)

        composeRule.onNodeWithText(context.getString(R.string.stats_time_watched_label, value)).assertExists()
    }

    @Test
    fun timeWatched_withNoRuntimeKnown_isUnknown_notZero() {
        setContent(viewing(MediaType.MOVIE, watched(knownSeconds = null, unknownRuntime = 9)))

        composeRule
            .onNodeWithText(context.getString(R.string.stats_time_watched_label, string(R.string.stats_unknown_value)))
            .assertExists()
    }

    @Test
    fun shows_countEpisodesAcrossShows_andFinishedOnAllTime() {
        setContent(
            viewing(MediaType.TV_SHOW, watched(viewings = 34, titles = 4), StatsPeriod.ALL_TIME, showsFinished = 3),
        )

        composeRule
            .onNodeWithText(
                context.resources.getQuantityString(R.plurals.stats_episodes_watched, 4, 34, 4),
            ).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.stats_shows_finished_label, 3)).assertExists()
    }

    @Test
    fun showsFinished_isNotShownWhenUnknown() {
        setContent(viewing(MediaType.TV_SHOW, watched(), StatsPeriod.WEEK, showsFinished = null))

        // Positive control: the Shows card is drawn.
        composeRule
            .onNodeWithText(
                context.resources.getQuantityString(R.plurals.stats_episodes_watched, 4, 9, 4),
            ).assertExists()
        composeRule.onNode(hasText("Shows finished", substring = true)).assertDoesNotExist()
    }

    @Test
    fun everyViewingUntimed_hidesTheClock_andSaysSo() {
        setContent(viewing(MediaType.MOVIE, watched(hours = List(24) { 0 }, untimed = 9)))

        scrollToTile(TestTags.Stats.WEEKDAY_CHART)
        composeRule.onNodeWithTag(TestTags.Stats.DAYPART_CHART).assertDoesNotExist()
        inCard(
            TestTags.Stats.CHARTS_CARD,
            context.resources.getQuantityString(R.plurals.stats_untimed, 9, 9),
        ).assertExists()
    }

    @Test
    fun topShows_listTheirTime() {
        setContent(viewing(MediaType.TV_SHOW, watched(topTitles = listOf(TitleTime("Severance", 22_200)))))

        inCard(TestTags.Stats.TOP_SHOWS_CARD, "Severance").assertExists()
        inCard(TestTags.Stats.TOP_SHOWS_CARD, "6h 10m").assertExists()
    }

    @Test
    fun nothingWatched_saysSo() {
        setContent(viewing(MediaType.MOVIE, ViewingBreakdown(weekdays = week)))

        inCard(TestTags.Stats.CHARTS_CARD, string(R.string.stats_nothing_watched)).assertExists()
    }

    @Test
    fun theGoalAndStreak_stayOnTheBooksTab() {
        setContent(viewing(MediaType.MOVIE, watched()))

        // Positive control: the Films card is drawn.
        composeRule
            .onNodeWithText(
                context.resources.getQuantityString(R.plurals.stats_films_watched_rewatches, 2, 9, 2),
            ).assertExists()
        composeRule.onNodeWithTag(TestTags.Stats.GOAL_CARD).assertDoesNotExist()
        composeRule.onNode(hasText("streak", substring = true)).assertDoesNotExist()
    }

    /** A Films month tile reads films, not books (#217 review): "January: 3 films". */
    @Test
    fun theFilmsMonthTile_readsFilms_notBooks() {
        val months = List(12) { if (it == 0) 3 else 0 }
        setContent(viewing(MediaType.MOVIE, watched().copy(months = months), StatsPeriod.YEAR))

        scrollToTile(TestTags.Stats.MONTH_CHART)
        val description =
            composeRule
                .onNodeWithTag(TestTags.Stats.MONTH_CHART)
                .fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription]
                .joinToString()
        assertTrue(description, "3 films" in description)
        assertTrue(description, "book" !in description)
    }

    /** Shows were watched, but none has a runtime: the card says so, not "nothing watched" (#217 review). */
    @Test
    fun topShows_withNoRuntimeKnown_saySo_notNothingWatched() {
        setContent(viewing(MediaType.TV_SHOW, watched(knownSeconds = null, topTitles = emptyList())))

        inCard(TestTags.Stats.TOP_SHOWS_CARD, string(R.string.stats_no_runtimes)).assertExists()
        inCard(TestTags.Stats.TOP_SHOWS_CARD, string(R.string.stats_nothing_watched)).assertDoesNotExist()
    }

    /**
     * On a short screen -- landscape, or a large font -- the larger chart is taller than the window,
     * and Close must still be reachable by scrolling (#217, CodeRabbit). Without a scrolling body
     * `performScrollTo` finds nothing to scroll and fails.
     */
    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun theLargerChart_scrolls_soCloseIsAlwaysReachable() {
        setContent(loaded(breakdown = timed()))
        scrollToTile(TestTags.Stats.DAYPART_CHART)
        composeRule.onNodeWithTag(TestTags.Stats.DAYPART_CHART).performClick()

        composeRule.onNode(hasText(string(R.string.stats_close)) and hasClickAction()).performScrollTo().performClick()

        composeRule.onNodeWithText(string(R.string.stats_close)).assertDoesNotExist()
    }

    /** The year in review opens from a card on the Year view, on any tab (#221). */
    @Test
    fun theYearView_offersTheYearInReview() {
        setContent(loaded(period = StatsPeriod.YEAR))
        val card = context.getString(R.string.year_review_title_so_far, 2024)

        composeRule.onNodeWithTag(TestTags.Stats.LIST).performScrollToNode(hasTestTag(TestTags.Stats.YEAR_REVIEW_CARD))
        composeRule
            .onNodeWithTag(
                TestTags.Stats.YEAR_REVIEW_CARD,
            ).assert(hasText(card, substring = true))
            .performClick()

        assertEquals(1, yearReviews)
    }

    @Test
    fun otherPeriods_doNotOfferIt() {
        setContent(loaded(period = StatsPeriod.MONTH))

        // Positive control: the screen is drawn.
        composeRule.onNodeWithTag(TestTags.Stats.PERIODS).assertExists()
        composeRule.onNodeWithTag(TestTags.Stats.YEAR_REVIEW_CARD).assertDoesNotExist()
    }
}
