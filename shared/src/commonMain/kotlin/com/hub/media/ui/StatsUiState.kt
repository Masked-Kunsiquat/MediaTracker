package com.hub.media.ui

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
 */
public data class StatsUiState(
    val isLoading: Boolean = true,
    val period: StatsPeriod = StatsPeriod.WEEK,
    val stats: Period = Period(),
    val currentStreakDays: Int = 0,
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
     */
    public data class Period(
        val timeReadSeconds: Long? = null,
        val sessionCount: Int = 0,
        val pagesRead: Int? = null,
        val booksFinished: Int = 0,
    )
}
