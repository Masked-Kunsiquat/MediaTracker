package com.hub.media.ui

import com.hub.media.features.stats.data.GoalProgress
import com.hub.media.features.stats.data.StatsPeriod

/**
 * UI state for the stats screen: one period at a time (#91).
 *
 * @property isLoading True until the first snapshot arrives; every field has a sensible default,
 *   so one flag is enough for the screen to show a loading affordance.
 * @property period The period the picker has selected -- remembered across visits via
 *   [com.hub.media.features.settings.data.observeStatsPeriod].
 * @property stats [period]'s numbers, read as one consistent snapshot (see
 *   [com.hub.media.core.database.dao.StatsDao.observePeriodStats]). All time replaces the former
 *   separate "books finished (lifetime)" figure.
 * @property currentStreakDays Current consecutive-reading-day streak -- see
 *   [com.hub.media.features.stats.data.StatsRepository.observeReadingStreak] for the exact rule
 *   (today-without-a-session-yet does not break yesterday's streak). Independent of [period].
 * @property goal This year's reading goal and how it is doing, or `null` when none is set for this
 *   year (#219). Counts this year's finishes whatever [period] is showing.
 * @property goalYear The year a goal set now would apply to, for the card's "Set a goal for 2026".
 * @property finishesByMonth Books finished in each month of this year, January first, on the Year
 *   view only; `null` on every other period, which draws no chart (#219).
 */
public data class StatsUiState(
    val isLoading: Boolean = true,
    val period: StatsPeriod = StatsPeriod.WEEK,
    val stats: Period = Period(),
    val currentStreakDays: Int = 0,
    val goal: GoalProgress? = null,
    val goalYear: Int = 0,
    val finishesByMonth: List<Int>? = null,
) {
    /**
     * One period's aggregate numbers.
     *
     * @property timeReadSeconds Sum of known session durations, or `null` when no session in the
     *   period has one -- unknown, never zero (an unknown duration must not silently become a zero
     *   that corrupts a time-read total).
     * @property sessionCount Every session in the period, including those without a duration.
     * @property pagesRead Sum of known page deltas, or `null` when none is known.
     * @property booksFinished Books whose status is FINISHED with a finish date in the period; All
     *   time also counts finished books that have no finish date.
     * @property averageSessionSeconds Known time read over sessions with a known duration, or `null`.
     * @property longestSessionSeconds The longest known session, or `null` (#219).
     * @property pagesPerHour Reading speed over sessions with both a duration and pages, rounded,
     *   or `null` when no session has both (#219).
     */
    public data class Period(
        val timeReadSeconds: Long? = null,
        val sessionCount: Int = 0,
        val pagesRead: Int? = null,
        val booksFinished: Int = 0,
        val averageSessionSeconds: Long? = null,
        val longestSessionSeconds: Long? = null,
        val pagesPerHour: Int? = null,
    )
}
