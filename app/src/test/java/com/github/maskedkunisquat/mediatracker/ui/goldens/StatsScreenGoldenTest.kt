package com.github.maskedkunisquat.mediatracker.ui.goldens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.screens.StatsScreen
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.features.stats.data.AuthorTime
import com.hub.media.features.stats.data.FormatTime
import com.hub.media.features.stats.data.GoalProgress
import com.hub.media.features.stats.data.GoalStatus
import com.hub.media.features.stats.data.ReadingBreakdown
import com.hub.media.features.stats.data.StatsPeriod
import com.hub.media.ui.StatsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline golden for stats — the screen the occlusion lane cannot say anything about.
 *
 * Its only interactive nodes inside the scroller -- the period chips and the reading-goal card --
 * can never reach the navigation bar, so the navigation bar rule was written for it and then deleted
 * as a guaranteed pass (see `Occlusion.kt`'s exemption list). What #99 actually risks here is a last card clipped by the bar, which is visual by
 * definition. This golden is the assertion that lane could not make, and the reason #102 exists
 * alongside #96 rather than instead of it.
 *
 * The fixture carries a **`null` `timeReadSeconds` on the month period** deliberately.
 * `StatsRepository`'s contract distinguishes "unknown" from "zero" — a session with no recorded
 * duration must not become a zero that corrupts a total — and the screen renders that distinction
 * as different text. A golden that only ever showed populated numbers would not notice the day
 * "unknown" started rendering as "0m", which is the silent-wrong-value shape this repository has
 * already shipped once, in #78's comma-decimal price.
 *
 * Paired with a text assertion rather than a tag (#102 rule 1): this screen carries none, and a
 * semantic matcher is the better guard per AGENTS.md section 7.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsScreenGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stats() {
        composeRule.captureGolden(
            name = "stats",
            alsoAssert = { assertTextIsShown("This month", "Streak", "Books finished") },
        ) { Fixture() }
    }

    /**
     * The Year view (#219): the month chart, every period-card line known, and a goal on pace. The
     * chart is drawn on a Canvas, so this is the only lane that sees its bars at all.
     */
    @Test
    fun statsYear() {
        composeRule.captureGolden(
            name = "stats-year",
            alsoAssert = { assertTextIsShown("This year", "Books finished by month", "Speed") },
        ) { YearFixture() }
    }

    /**
     * The three part 2 cards (#219), populated: two bar charts, a long author name cut short with
     * its time still on the row, and a format under one percent shown as "<1%". They sit below the
     * fold, so the paired assertion scrolls the real screen to the last of them before checking the
     * text; `captureGolden` captures after its assertions, so the image is what was asserted.
     */
    @Test
    fun statsBreakdown() {
        composeRule.captureGolden(
            name = "stats-breakdown",
            alsoAssert = {
                onNodeWithTag(TestTags.Stats.LIST).performScrollToNode(hasTestTag(TestTags.Stats.FORMATS_CARD))
                assertTextIsShown("When you read", "Top authors", "Formats", "<1%")
            },
        ) { BreakdownFixture() }
    }

    @Composable
    private fun BreakdownFixture() {
        val breakdown =
            ReadingBreakdown(
                dayParts = listOf(9_000L, 2_400L, 14_400L, 4_200L),
                weekdays = List(7) { it + 1 },
                weekdaySeconds = listOf(3_000L, 0L, 4_800L, 2_100L, 0L, 9_600L, 10_500L),
                topAuthors =
                    listOf(
                        AuthorTime("Sir Terence David John Pratchett, Discworld chronicler", 12_600),
                        AuthorTime("Ursula K. Le Guin", 8_400),
                        AuthorTime("Ann Leckie", 4_500),
                    ),
                formats =
                    listOf(
                        FormatTime(BookFormat.PAPERBACK, 18_000),
                        FormatTime(BookFormat.EBOOK, 11_970),
                        FormatTime(BookFormat.AUDIOBOOK, 30),
                    ),
                totalSeconds = 30_000,
            )
        StatsScreen(
            uiState =
                StatsUiState(
                    isLoading = false,
                    period = StatsPeriod.MONTH,
                    stats =
                        StatsUiState.Period(
                            timeReadSeconds = 30_000,
                            sessionCount = 14,
                            pagesRead = 610,
                            booksFinished = 2,
                        ),
                    currentStreakDays = 3,
                    goalYear = 2026,
                    breakdown = breakdown,
                ),
            onSelectPeriod = {},
            onSetGoal = {},
            onClearGoal = {},
            onNavigateBack = {},
        )
    }

    @Composable
    private fun YearFixture() {
        StatsScreen(
            uiState =
                StatsUiState(
                    isLoading = false,
                    period = StatsPeriod.YEAR,
                    stats =
                        StatsUiState.Period(
                            timeReadSeconds = 82_080,
                            sessionCount = 61,
                            pagesRead = 2_140,
                            booksFinished = 9,
                            averageSessionSeconds = 1_345,
                            longestSessionSeconds = 7_260,
                            pagesPerHour = 94,
                        ),
                    currentStreakDays = 4,
                    goal = GoalProgress(goal = 20, finished = 9, expected = 9, status = GoalStatus.OnPace),
                    goalYear = 2026,
                    finishesByMonth = listOf(1, 2, 0, 1, 3, 0, 2, 0, 0, 0, 0, 0),
                    // The same 22h 48m as the card: the app reads both from the same sessions, so a
                    // fixture where they disagree would picture a bug the app cannot have.
                    breakdown =
                        ReadingBreakdown(
                            dayParts = listOf(20_520L, 8_208L, 41_040L, 12_312L),
                            weekdays = List(7) { it + 1 },
                            weekdaySeconds = listOf(8_208L, 4_104L, 12_312L, 8_208L, 4_104L, 20_520L, 24_624L),
                            topAuthors = listOf(AuthorTime("Ursula K. Le Guin", 41_040)),
                            formats = listOf(FormatTime(BookFormat.PAPERBACK, 82_080)),
                            totalSeconds = 82_080,
                        ),
                ),
            onSelectPeriod = {},
            onSetGoal = {},
            onClearGoal = {},
            onNavigateBack = {},
        )
    }

    @Composable
    private fun Fixture() {
        StatsScreen(
            uiState =
                StatsUiState(
                    isLoading = false,
                    period = StatsPeriod.MONTH,
                    // timeReadSeconds left null: "unknown", not "zero" -- see this class's KDoc.
                    stats =
                        StatsUiState.Period(
                            timeReadSeconds = null,
                            sessionCount = 23,
                            pagesRead = 902,
                            booksFinished = 4,
                        ),
                    currentStreakDays = 12,
                    // No goal: the card's offer to set one.
                    goalYear = 2026,
                ),
            onSelectPeriod = {},
            onSetGoal = {},
            onClearGoal = {},
            onNavigateBack = {},
        )
    }
}
