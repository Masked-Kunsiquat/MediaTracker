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
import com.hub.media.features.stats.data.localDateChanges
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
 * The remembered period, the week-start preference and the local date ([localDateChanges]) all feed
 * [flatMapLatest], and everything inside it is resolved against one reading of [clock]. So "today"
 * is re-read whenever the user picks a period, changes the week start, returns to the screen after
 * it was unsubscribed for more than five seconds (`WhileSubscribed`), or local midnight passes --
 * a screen left open across New Year moves to the new year's goal by itself (#219 review).
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
            // Only a trigger: the date it carries is re-read below, with everything else.
            localDateChanges(clock, timeZone),
        ) { period, weekStart, _ ->
            period to weekStart
        }.flatMapLatest { (period, weekStart) ->
            // "Now" is read once per restart and every query below is given that same instant, so
            // the period's bounds, the streak, the goal's year and the months can never straddle a
            // midnight between them. The streak is re-derived here too: created once outside, it
            // froze on the day the screen opened.
            // The goal counts this year's finishes whatever period is showing, so it needs the Year
            // snapshot of its own (#219); the months are read only while the Year view is showing,
            // the one place they are drawn.
            val now = clock.now()
            val at =
                object : Clock {
                    override fun now() = now
                }
            val today = now.toLocalDateTime(timeZone).date
            val yearBounds = StatsPeriod.YEAR.bounds(timeZone, at, weekStart)!!
            combine(
                statsRepository.observePeriodStats(period, timeZone, at, weekStart),
                statsRepository.observeReadingStreak(timeZone, at),
                settingsRepository.observeReadingGoal(),
                statsRepository.observePeriodStats(StatsPeriod.YEAR, timeZone, at, weekStart),
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
