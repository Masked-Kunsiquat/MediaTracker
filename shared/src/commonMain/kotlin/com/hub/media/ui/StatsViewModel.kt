package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.dao.PeriodStatsRow
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.observeStatsPeriod
import com.hub.media.features.settings.data.observeWeekStartDay
import com.hub.media.features.settings.data.setStatsPeriod
import com.hub.media.features.stats.data.StatsPeriod
import com.hub.media.features.stats.data.StatsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
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
            combine(
                statsRepository.observePeriodStats(period, timeZone, clock, weekStart),
                statsRepository.observeReadingStreak(timeZone, clock),
            ) { stats, streak ->
                StatsUiState(isLoading = false, period = period, stats = stats.toPeriod(), currentStreakDays = streak)
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
}

private fun PeriodStatsRow.toPeriod() =
    StatsUiState.Period(
        timeReadSeconds = timeReadSeconds,
        sessionCount = sessionCount,
        pagesRead = pagesRead,
        booksFinished = booksFinished,
    )
