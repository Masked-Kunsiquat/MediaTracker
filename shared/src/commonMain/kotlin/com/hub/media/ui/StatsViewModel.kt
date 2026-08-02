package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.features.stats.data.StatsRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone

/**
 * Drives the stats screen (ROADMAP Task 5; the screen itself is Phase C).
 *
 * [uiState] combines seven [StatsRepository] flows — three range queries (time read, session
 * count, pages read) for each of "this week" and "this month", plus the current reading streak —
 * into a single hot [StateFlow], following [LibraryViewModel]/[BookDetailViewModel]'s
 * `stateIn`/`WhileSubscribed` convention.
 *
 * ### Period bounds are computed once, at construction
 * `[from, to)` bounds for "this week"/"this month" ([StatsRepository.thisWeekBounds]/
 * [StatsRepository.thisMonthBounds]) are computed a single time when this ViewModel is
 * constructed, not recomputed as real time crosses a day/week/month boundary while the ViewModel
 * is alive. A [StatsViewModel] instance that survives across midnight (or across a week/month
 * rollover) keeps reporting stats for whatever "this week"/"this month" was current at
 * construction time until it is recreated. This mirrors [StatsRepository.observeReadingStreak]'s
 * same accepted staleness (see its KDoc) and is an accepted simplification for now: screens are
 * expected to get a fresh ViewModel per navigation to the stats screen (Phase C), which is
 * frequent enough in practice that a long-lived instance spanning a full day is not the common
 * case. A future revisit could re-derive bounds on each calendar-day tick if that assumption stops
 * holding.
 *
 * @param statsRepository Source of all reactive aggregate queries.
 * @param timeZone Timezone used for calendar-day/week/month bucketing (see
 *   [StatsRepository]'s day-bucketing KDoc on why this must be explicit, never UTC-by-default).
 *   Defaults to the device's current timezone; tests inject a fixed zone for determinism.
 * @param clock Wall-clock time source used to resolve "today" once at construction. Defaults to
 *   [Clock.System]; tests inject a fixed [Clock] for deterministic period bounds and streaks.
 */
public class StatsViewModel(
    private val statsRepository: StatsRepository,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    clock: Clock = Clock.System,
) : ViewModel() {

    // Pair, not a destructured (a, b) property pair: Kotlin destructuring declarations are only
    // valid for local variables/lambda parameters, not class-body property declarations.
    private val weekBounds: Pair<Instant, Instant> = StatsRepository.thisWeekBounds(timeZone, clock)
    private val monthBounds: Pair<Instant, Instant> = StatsRepository.thisMonthBounds(timeZone, clock)

    private fun periodFlow(from: Instant, to: Instant): Flow<StatsUiState.Period> = combine(
        statsRepository.observeTimeReadInRange(from, to),
        statsRepository.observeSessionCountInRange(from, to),
        statsRepository.observePagesReadInRange(from, to),
    ) { timeRead, sessionCount, pagesRead ->
        StatsUiState.Period(timeReadSeconds = timeRead, sessionCount = sessionCount, pagesRead = pagesRead)
    }

    public val uiState: StateFlow<StatsUiState> = combine(
        periodFlow(weekBounds.first, weekBounds.second),
        periodFlow(monthBounds.first, monthBounds.second),
        statsRepository.observeReadingStreak(timeZone, clock),
    ) { week, month, streak ->
        StatsUiState(isLoading = false, week = week, month = month, currentStreakDays = streak)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5.seconds),
        initialValue = StatsUiState(isLoading = true),
    )
}
