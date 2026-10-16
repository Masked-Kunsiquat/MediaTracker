package com.hub.media.features.stats.data

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [goalProgress] (#219): how a yearly reading goal is doing. "Expected so far" is the goal spread
 * evenly over the year's days; on pace means at least the whole books expected by today.
 */
class GoalPaceTest {
    @Test
    fun halfwayThroughTheYear_withHalfDone_isOnPace() {
        // 2025-07-02 is day 183 of 365: 12 * 183 / 365 = 6.02, so 6 books are expected.
        val p = goalProgress(goal = 12, finished = 6, today = LocalDate(2025, 7, 2))

        assertEquals(6, p.expected)
        assertEquals(GoalStatus.OnPace, p.status)
    }

    @Test
    fun behind_saysByHowMany() {
        // 12 * 183 / 365 = 6.02, so 6 are expected: with 4 finished, 2 more put you on pace.
        val p = goalProgress(goal = 12, finished = 4, today = LocalDate(2025, 7, 2))

        assertEquals(GoalStatus.Behind(2), p.status)
    }

    /**
     * "N behind" must mean "N more books and you're on pace". Counting against the rounded-UP share
     * said "2 behind" here, and then one book later said "on pace" -- seen on the phone (#219 review).
     */
    @Test
    fun oneBookBehind_isOneBookFromOnPace() {
        val behind = goalProgress(goal = 12, finished = 5, today = LocalDate(2025, 7, 2))
        val oneMore = goalProgress(goal = 12, finished = 6, today = LocalDate(2025, 7, 2))

        assertEquals(GoalStatus.Behind(1), behind.status)
        assertEquals(GoalStatus.OnPace, oneMore.status)
    }

    @Test
    fun reachingTheGoal_isReached_evenEarlyInTheYear() {
        val p = goalProgress(goal = 3, finished = 3, today = LocalDate(2025, 2, 1))

        assertEquals(GoalStatus.Reached, p.status)
    }

    @Test
    fun firstDayOfTheYear_withNothingFinished_isOnPace() {
        // Day 1 of 365: 12 * 1 / 365 = 0.03 -> 0 expected.
        val p = goalProgress(goal = 12, finished = 0, today = LocalDate(2025, 1, 1))

        assertEquals(0, p.expected)
        assertEquals(GoalStatus.OnPace, p.status)
    }

    @Test
    fun lastDayOfTheYear_expectsTheWholeGoal() {
        val p = goalProgress(goal = 12, finished = 11, today = LocalDate(2025, 12, 31))

        assertEquals(12, p.expected)
        assertEquals(GoalStatus.Behind(1), p.status)
    }

    @Test
    fun aLeapYear_hasThreeHundredSixtySixDays() {
        // 2024 is a leap year: 2024-12-30 is day 365 of 366, so 366 * 365 / 366 = 365, not the
        // whole goal of 366 a 365-day year would already expect.
        val p = goalProgress(goal = 366, finished = 365, today = LocalDate(2024, 12, 30))

        assertEquals(365, p.expected)
        assertEquals(GoalStatus.OnPace, p.status)
    }
}
