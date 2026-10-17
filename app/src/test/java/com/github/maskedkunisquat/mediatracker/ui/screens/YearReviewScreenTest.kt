package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.features.stats.data.DayPart
import com.hub.media.features.stats.data.LongestBook
import com.hub.media.features.stats.data.Rhythm
import com.hub.media.features.stats.data.YearReview
import com.hub.media.features.stats.data.YearTotals
import com.hub.media.ui.YearReviewUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The year in review's story (#221), driven statelessly. */
@RunWith(RobolectricTestRunner::class)
class YearReviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val picked = mutableListOf<Int>()
    private var closed = 0

    private fun string(id: Int) = context.getString(id)

    private val totals = YearTotals(23, 41, 312, 360_000, 1_836_000, 6)

    private fun review(
        totals: YearTotals = this.totals,
        longestBook: LongestBook? = null,
    ) = YearReview(2026, totals, longestBook, null, null, null, null, null, null)

    private fun setContent(
        review: YearReview = review(),
        years: List<Int> = listOf(2026, 2025),
        thisYear: Int = 2026,
    ) {
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                YearReviewScreen(
                    uiState = YearReviewUiState(isLoading = false, years = years, year = review.year, review = review),
                    thisYear = thisYear,
                    onSelectYear = { picked += it },
                    onClose = { closed++ },
                )
            }
        }
    }

    private fun page(kind: String) = composeRule.onNodeWithTag(TestTags.YearReview.page(kind))

    /**
     * Pages forward the way TalkBack does: the "Next page" action of the page on screen -- the node
     * TalkBack focuses, since the pager itself has nothing to read (#221 review).
     */
    private fun next() {
        val current =
            composeRule
                .onAllNodes(SemanticsMatcher("a page") { it.config.contains(SemanticsActions.CustomActions) })
                .fetchSemanticsNodes()
                .first { node -> node.boundsInRoot.left >= 0f && node.boundsInRoot.left < 1f }
        val actions = current.config[SemanticsActions.CustomActions]
        val next = actions.first { it.label == string(R.string.year_review_next) }
        composeRule.runOnIdle { next.action() }
        composeRule.waitForIdle()
    }

    @Test
    fun theStory_opensOnTheYear_titledSoFarForThisYear() {
        setContent()

        page("YEAR").assertExists()
        composeRule.onNodeWithText(context.getString(R.string.year_review_title_so_far, 2026)).assertExists()
    }

    @Test
    fun pagesWithNothingToShow_areSkipped() {
        setContent(review(longestBook = null))

        next()
        page("TOTALS").assertExists()
        next()
        // No longest book, author, session, goal, film, binge or rhythm: straight to the summary.
        page("SUMMARY").assertExists()
    }

    @Test
    fun aHighlight_isShownWhenThereIsOne() {
        setContent(review(longestBook = LongestBook("The Way of Kings", 1_007)))

        next()
        next()
        page("LONGEST_BOOK").assertExists()
        composeRule.onNodeWithText("The Way of Kings").assertExists()
    }

    @Test
    fun tappingTheRightThird_goesOn_andTheLeftThird_goesBack() {
        setContent()

        composeRule
            .onNodeWithTag(
                TestTags.YearReview.PAGER,
            ).performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        // Displayed, not merely composed: the pager can keep a neighbour alive off screen, so
        // "exists" would pass with a dead tap (#221 review).
        page("TOTALS").assertIsDisplayed()
        composeRule
            .onNodeWithTag(
                TestTags.YearReview.PAGER,
            ).performTouchInput { click(Offset(width * 0.1f, height / 2f)) }
        page("YEAR").assertIsDisplayed()
        page("TOTALS").assertIsNotDisplayed()
    }

    /** The actions sit on the page TalkBack focuses, not on the pager it never lands on (#221 review). */
    @Test
    fun talkBack_canPageForwardAndBack() {
        setContent()

        page("YEAR")
            .assert(
                SemanticsMatcher("has Next and Previous actions") { node ->
                    val labels =
                        node.config
                            .getOrElseNullable(
                                SemanticsActions.CustomActions,
                            ) { null }
                            .orEmpty()
                            .map { it.label }
                    string(R.string.year_review_next) in labels && string(R.string.year_review_previous) in labels
                },
            )
    }

    @Test
    fun close_closes() {
        setContent()

        composeRule.onNodeWithTag(TestTags.YearReview.CLOSE).performClick()

        assertEquals(1, closed)
    }

    @Test
    fun theYearPicker_listsTheYears_andPicksOne() {
        setContent()

        composeRule.onNode(hasText("2025") and hasClickAction()).performClick()

        assertEquals(listOf(2025), picked)
    }

    @Test
    fun aQuietYear_saysSo() {
        setContent(review(totals = YearTotals(0, 0, 0, 0, 0, 0)).copy(quiet = true))

        next()
        next()
        page("SUMMARY").assertExists()
        composeRule.onNodeWithText(string(R.string.year_review_quiet)).assertExists()
    }

    @Test
    fun theRhythm_saysAtNight_notInTheNights() {
        val rhythm =
            Rhythm(
                busiestMonth = 1,
                hours =
                    List(24) {
                        if (it ==
                            23
                        ) {
                            3_600L
                        } else {
                            0L
                        }
                    },
                busiestPart = DayPart.NIGHT,
                reading = true,
            )
        setContent(review().copy(rhythm = rhythm))

        next()
        next()
        page("RHYTHM").assertExists()
        composeRule.onNodeWithText(string(R.string.year_review_read_night)).assertExists()
    }

    @Test
    fun aQuietPastYear_doesNotSayYet() {
        setContent(review(totals = YearTotals(0, 0, 0, 0, 0, 0)).copy(year = 2025, quiet = true), thisYear = 2026)

        next()
        next()
        composeRule.onNodeWithText(string(R.string.year_review_quiet_past)).assertExists()
    }

    @Test
    fun theTotals_sayHowManyReadingSessionsHadNoTime() {
        setContent(review(totals = totals.copy(untimedSessions = 3)))

        next()
        page("TOTALS").assertExists()
        composeRule.onNodeWithText("plus 3 reading sessions without a time").assertExists()
    }
}
