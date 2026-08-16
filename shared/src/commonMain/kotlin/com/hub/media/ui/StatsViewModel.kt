package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.WeekStartDay
import com.hub.media.features.settings.data.observeWeekStartDay
import com.hub.media.features.stats.data.StatsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Drives the stats screen (ROADMAP Task 5; the screen itself is Phase C).
 *
 * [uiState] combines seven [StatsRepository] flows — three range queries (time read, session
 * count, pages read) for each of "this week" and "this month", plus the current reading streak —
 * into a single hot [StateFlow], following [LibraryViewModel]/[BookDetailViewModel]'s
 * `stateIn`/`WhileSubscribed` convention.
 *
 * ### Period bounds: month is fixed at construction, week is reactive (ROADMAP Task 7 Phase B)
 * `[from, to)` bounds for "this month" ([StatsRepository.thisMonthBounds]) are computed a single
 * time when this ViewModel is constructed, not recomputed as real time crosses a day/month
 * boundary while the ViewModel is alive. A [StatsViewModel] instance that survives across midnight
 * (or across a month rollover) keeps reporting "this month" stats for whatever month was current at
 * construction time until it is recreated. This mirrors [StatsRepository.observeReadingStreak]'s
 * same accepted staleness (see its KDoc) and is an accepted simplification for now: screens are
 * expected to get a fresh ViewModel per navigation to the stats screen (Phase C), which is
 * frequent enough in practice that a long-lived instance spanning a full day is not the common
 * case. A future revisit could re-derive bounds on each calendar-day tick if that assumption stops
 * holding.
 *
 * "This week"'s bounds are different: [weekPeriodFlow] re-derives them via
 * [flatMapLatest][kotlinx.coroutines.flow.flatMapLatest] every time
 * [SettingsRepository.observeWeekStartDay] emits a new [WeekStartDay], calling
 * [StatsRepository.thisWeekBounds] on initial collection and on every preference change.
 * This was chosen over leaving the week bounds equally stale, because it was straightforward to do
 * with the existing `combine`-based shape (swap one `combine` input for a `flatMapLatest` chain)
 * and directly addresses the ROADMAP's call-out: a user who opens Settings and flips the
 * week-start-day preference while the Stats screen is already open (the same navigation session, no
 * ViewModel recreation) sees "this week" re-bucket immediately, rather than needing to leave and
 * re-enter the screen. Note that this means "this week"'s bounds are re-resolved alongside the
 * week-start-day change, so they will pick up a fresh "today" on each [observeWeekStartDay]
 * emission. The month bounds, by contrast, remain fixed to their construction-time "today".
 *
 * @param statsRepository Source of all reactive aggregate queries.
 * @param settingsRepository Source of the reactive week-start-day preference (ROADMAP Task 7 Phase
 *   B) driving [weekPeriodFlow].
 * @param timeZone Timezone used for calendar-day/week/month bucketing (see
 *   [StatsRepository]'s day-bucketing KDoc on why this must be explicit, never UTC-by-default).
 *   Defaults to the device's current timezone; tests inject a fixed zone for determinism.
 * @param clock Wall-clock time source used to resolve "today" once at construction. Defaults to
 *   [Clock.System]; tests inject a fixed [Clock] for deterministic period bounds and streaks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class StatsViewModel(
    private val statsRepository: StatsRepository,
    private val settingsRepository: SettingsRepository,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    clock: Clock = Clock.System,
) : ViewModel() {
    private val monthBounds: Pair<Instant, Instant> = StatsRepository.thisMonthBounds(timeZone, clock)

    private fun periodFlow(
        from: Instant,
        to: Instant,
    ): Flow<StatsUiState.Period> =
        combine(
            statsRepository.observeTimeReadInRange(from, to),
            statsRepository.observeSessionCountInRange(from, to),
            statsRepository.observePagesReadInRange(from, to),
            statsRepository.observeBooksFinishedInRange(from, to),
        ) { timeRead, sessionCount, pagesRead, booksFinished ->
            StatsUiState.Period(
                timeReadSeconds = timeRead,
                sessionCount = sessionCount,
                pagesRead = pagesRead,
                booksFinished = booksFinished,
            )
        }

    /**
     * "This week"'s period, re-derived (via [flatMapLatest][kotlinx.coroutines.flow.flatMapLatest])
     * every time the week-start-day preference changes — see class KDoc "Period bounds" section.
     */
    private val weekPeriodFlow: Flow<StatsUiState.Period> =
        settingsRepository.observeWeekStartDay().flatMapLatest { weekStartDay ->
            val (from, to) = StatsRepository.thisWeekBounds(timeZone, clock, weekStartDay)
            periodFlow(from, to)
        }

    public val uiState: StateFlow<StatsUiState> =
        combine(
            weekPeriodFlow,
            periodFlow(monthBounds.first, monthBounds.second),
            statsRepository.observeReadingStreak(timeZone, clock),
            statsRepository.observeBooksFinishedTotal(),
        ) { week, month, streak, lifetimeBooksFinished ->
            StatsUiState(
                isLoading = false,
                week = week,
                month = month,
                currentStreakDays = streak,
                lifetimeBooksFinished = lifetimeBooksFinished,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5.seconds),
            initialValue = StatsUiState(isLoading = true),
        )
}
