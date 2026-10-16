package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.dao.PeriodStatsRow
import com.hub.media.features.settings.data.MAX_READING_GOAL
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.clearReadingGoal
import com.hub.media.features.settings.data.observeReadingGoal
import com.hub.media.features.settings.data.observeStatsPeriod
import com.hub.media.features.settings.data.observeWeekStartDay
import com.hub.media.features.settings.data.setReadingGoal
import com.hub.media.features.settings.data.setStatsPeriod
import com.hub.media.features.stats.data.StatsPeriod
import com.hub.media.features.stats.data.StatsRepository
import com.hub.media.features.stats.data.bounds
import com.hub.media.features.stats.data.goalProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the stats screen: one period at a time, the period remembered (#91).
 *
 * ### One snapshot per period
 * The chosen period's numbers come from [StatsRepository.observePeriodStats], one query, so a write
 * produces one state with all four numbers already in agreement. The screen used to `combine` four
 * independent flows per period, and one inserted session published every halfway mix on the way --
 * "0 sessions, 5 pages read" -- before settling. `aWrite_neverPublishesAHalfwayState` pins this.
 *
 * ### Reactive inputs, and when "today" is read
 * The remembered period and the week-start preference both feed [flatMapLatest], and both the
 * period's bounds and the streak are resolved against [clock] inside it. So "today" is re-read
 * whenever the user picks a period, changes the week start, or returns to the screen after it was
 * unsubscribed for more than five seconds (`WhileSubscribed`); a screen left open and untouched
 * across midnight keeps yesterday's "today" until one of those happens.
 *
 * The streak is a separate query (distinct days, not a sum), so a write can update the streak a
 * moment apart from the period card. That is not the internal inconsistency #91 is about, which is
 * the period's own four numbers disagreeing, and `aWrite_neverPublishesAHalfwayState` compares the
 * period's numbers only.
 *
 * @param statsRepository Source of the period snapshot and the streak.
 * @param settingsRepository Source of the remembered period and the week-start preference.
 * @param timeZone Time zone for local-day bucketing; tests inject a fixed one.
 * @param clock Wall-clock source for "today"; tests inject a fixed one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class StatsViewModel(
    private val statsRepository: StatsRepository,
    private val settingsRepository: SettingsRepository,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private val periodSnapshot =
        combine(
            settingsRepository.observeStatsPeriod(),
            settingsRepository.observeWeekStartDay(),
        ) { period, weekStart ->
            period to weekStart
        }.flatMapLatest { (period, weekStart) ->
            // The streak is re-derived here too, so it reads the same "today" as the period's bounds:
            // created once outside, it froze on the day the screen opened.
            // The goal and the months read the same "today" too (#219). The goal counts this year's
            // finishes whatever period is showing, so it needs the Year snapshot of its own; the
            // months are read only while the Year view is showing, the one place they are drawn.
            val today = clock.now().toLocalDateTime(timeZone).date
            val yearBounds = StatsPeriod.YEAR.bounds(timeZone, clock, weekStart)!!
            combine(
                statsRepository.observePeriodStats(period, timeZone, clock, weekStart),
                statsRepository.observeReadingStreak(timeZone, clock),
                settingsRepository.observeReadingGoal(),
                statsRepository.observePeriodStats(StatsPeriod.YEAR, timeZone, clock, weekStart),
                if (period == StatsPeriod.YEAR) {
                    statsRepository.observeFinishesByMonth(yearBounds, timeZone)
                } else {
                    flowOf(null)
                },
            ) { stats, streak, goal, yearStats, months ->
                StatsUiState(
                    isLoading = false,
                    period = period,
                    stats = stats.toPeriod(),
                    currentStreakDays = streak,
                    // A goal set for another year is not this year's goal: January starts fresh.
                    goal =
                        goal?.takeIf { it.year == today.year }?.let {
                            goalProgress(goal = it.books, finished = yearStats.booksFinished, today = today)
                        },
                    goalYear = today.year,
                    finishesByMonth = months,
                )
            }
        }

    public val uiState: StateFlow<StatsUiState> =
        periodSnapshot.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5.seconds),
            initialValue = StatsUiState(isLoading = true),
        )

    /** Shows [period], and remembers it for the next visit; the screen follows the stored value. */
    public fun selectPeriod(period: StatsPeriod) {
        viewModelScope.launch { settingsRepository.setStatsPeriod(period) }
    }

    /** Sets this year's reading goal to [books]; values outside 1..[MAX_READING_GOAL] are ignored. */
    public fun setGoal(books: Int) {
        if (books !in 1..MAX_READING_GOAL) return
        viewModelScope.launch {
            settingsRepository.setReadingGoal(clock.now().toLocalDateTime(timeZone).year, books)
        }
    }

    /** Removes the reading goal. */
    public fun clearGoal() {
        viewModelScope.launch { settingsRepository.clearReadingGoal() }
    }
}

/**
 * The screen's shape of one period. Average and speed are derived here from the row's sums, so they
 * come from the same single read as everything else (#219): unknown stays `null`, never 0.
 */
private fun PeriodStatsRow.toPeriod() =
    StatsUiState.Period(
        timeReadSeconds = timeReadSeconds,
        sessionCount = sessionCount,
        pagesRead = pagesRead,
        booksFinished = booksFinished,
        averageSessionSeconds = timeReadSeconds?.takeIf { timedSessionCount > 0 }?.let { it / timedSessionCount },
        longestSessionSeconds = longestSessionSeconds,
        pagesPerHour =
            if (speedSeconds != null && speedPages != null && speedSeconds > 0) {
                ((speedPages * 3_600.0) / speedSeconds).roundToInt()
            } else {
                null
            },
    )
