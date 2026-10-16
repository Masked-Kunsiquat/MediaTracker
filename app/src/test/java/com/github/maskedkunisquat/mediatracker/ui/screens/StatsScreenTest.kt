package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
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
import com.hub.media.features.stats.data.StatsPeriod
import com.hub.media.ui.StatsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
                StatsScreen(uiState = state, onSelectPeriod = { picked += it }, onNavigateBack = {})
            }
        }
    }

    private fun loaded(
        period: StatsPeriod = StatsPeriod.WEEK,
        stats: StatsUiState.Period = StatsUiState.Period(3600L, 4, 120, 1),
    ) = StatsUiState(isLoading = false, period = period, stats = stats, currentStreakDays = 3)

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
                StatsScreen(uiState = loaded(period = period), onSelectPeriod = {}, onNavigateBack = {})
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
}
