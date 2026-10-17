package com.github.maskedkunisquat.mediatracker.ui.goldens

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.screens.YearReviewScreen
import com.hub.media.features.stats.data.BingeDay
import com.hub.media.features.stats.data.DayPart
import com.hub.media.features.stats.data.Rhythm
import com.hub.media.features.stats.data.YearReview
import com.hub.media.features.stats.data.YearTotals
import com.hub.media.ui.YearReviewUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The year in review's pages (#221): the totals, a highlight and the rhythm clock. Each golden pages
 * forward through the real pager -- the way TalkBack does, by the page's "Next page" action -- before the
 * capture, so the picture is of the story as reached, not of a page drawn on its own.
 *
 * The fixture agrees with itself (Graphban `m_ab00cbec8a`): the clock's hours add up to the totals'
 * reading time, the busiest part is the evening the hours say, and the binge's 7 episodes are among
 * the year's 312.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YearReviewGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    // 100 hours of reading, mostly evenings: 360 000 seconds across the clock.
    private val hours: List<Long> =
        listOf(
            3_600,
            0,
            0,
            0,
            0,
            0,
            7_200,
            14_400,
            10_800,
            3_600,
            0,
            0,
            7_200,
            10_800,
            7_200,
            3_600,
            3_600,
            14_400,
            25_200,
            61_200,
            82_800,
            54_000,
            28_800,
            21_600,
        )

    private val review =
        YearReview(
            year = 2026,
            totals =
                YearTotals(
                    booksFinished = 23,
                    films = 41,
                    episodes = 312,
                    readSeconds = 360_000,
                    watchSeconds = 1_836_000,
                    unknownRuntime = 6,
                ),
            longestBook = null,
            topAuthor = null,
            longestSession = null,
            goal = null,
            rewatchedFilm = null,
            bingeDay = BingeDay(2026, 3, 14, dayOfWeekIso = 6, episodes = 7, topShow = "Severance"),
            rhythm = Rhythm(busiestMonth = 3, hours = hours, busiestPart = DayPart.EVENING, reading = true),
        )

    @Composable
    private fun Fixture() {
        YearReviewScreen(
            uiState = YearReviewUiState(isLoading = false, years = listOf(2026, 2025), year = 2026, review = review),
            thisYear = 2026,
            onSelectYear = {},
            onClose = {},
        )
    }

    /** Pages forward [times], as TalkBack's "Next page" does. */
    private fun ComposeContentTestRule.forward(times: Int) {
        repeat(times) {
            // The page on screen carries the actions, since TalkBack focuses it rather than the pager.
            val actions =
                onAllNodes(SemanticsMatcher("a page") { it.config.contains(SemanticsActions.CustomActions) })
                    .fetchSemanticsNodes()
                    .first { node -> node.boundsInRoot.left >= 0f && node.boundsInRoot.left < 1f }
                    .config[SemanticsActions.CustomActions]
            runOnIdle { actions.first { it.label == "Next page" }.action() }
            waitForIdle()
        }
    }

    @Test
    fun totals() {
        composeRule.captureGolden(
            name = "year-review-totals",
            alsoAssert = {
                forward(1)
                assertTextIsShown(
                    "In all",
                    "books finished",
                    "610h 0m reading and watching",
                    "plus 6 viewings without a runtime",
                )
            },
        ) { Fixture() }
    }

    @Test
    fun binge() {
        composeRule.captureGolden(
            name = "year-review-binge",
            alsoAssert = {
                forward(2)
                assertTextIsShown("Your biggest binge", "Saturday 14 March", "mostly Severance")
            },
        ) { Fixture() }
    }

    @Test
    fun rhythm() {
        composeRule.captureGolden(
            name = "year-review-rhythm",
            theme = Theme.DARK,
            alsoAssert = {
                forward(3)
                assertTextIsShown("Your rhythm", "March", "You read most in the evenings")
            },
        ) { Fixture() }
    }
}
