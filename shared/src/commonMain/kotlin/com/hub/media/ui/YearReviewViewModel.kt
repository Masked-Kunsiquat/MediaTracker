package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.dao.FinishedBookRow
import com.hub.media.core.database.dao.PeriodStatsRow
import com.hub.media.core.database.dao.TimedSessionRow
import com.hub.media.core.database.dao.ViewingRow
import com.hub.media.core.database.entities.MediaType
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.observeReadingGoal
import com.hub.media.features.settings.data.observeWeekStartDay
import com.hub.media.features.stats.data.StatsRepository
import com.hub.media.features.stats.data.YearReview
import com.hub.media.features.stats.data.favorites
import com.hub.media.features.stats.data.yearBounds
import com.hub.media.features.stats.data.yearReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The year in review's state (#221).
 *
 * @property years Every year from the first logged activity to this one, newest first; just this
 *   year when nothing is logged.
 * @property year The year being shown.
 * @property review That year's pages, or `null` while it loads.
 */
public data class YearReviewUiState(
    val isLoading: Boolean = true,
    val years: List<Int> = emptyList(),
    val year: Int = 0,
    val review: YearReview? = null,
)

/**
 * Drives the year in review (#221): one year at a time, built by [yearReview] from the stats
 * engine's own reads over that year's local dates -- the same queries as the Stats screen, never a
 * second copy of them. The review is rebuilt off the main thread, and only when its inputs change.
 *
 * @param initialYear The year to open on; `null` is this year.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class YearReviewViewModel(
    private val statsRepository: StatsRepository,
    private val settingsRepository: SettingsRepository,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    private val clock: Clock = Clock.System,
    initialYear: Int? = null,
) : ViewModel() {
    private val thisYear = clock.now().toLocalDateTime(timeZone).year
    private val selected = MutableStateFlow(initialYear ?: thisYear)

    private val years =
        statsRepository.observeFirstActivity().let { first ->
            combine(first, selected) { firstActivity, _ ->
                val from = firstActivity?.toLocalDateTime(timeZone)?.year?.coerceAtMost(thisYear) ?: thisYear
                (thisYear downTo from).toList()
            }
        }

    public val uiState: StateFlow<YearReviewUiState> =
        combine(selected, years, settingsRepository.observeWeekStartDay()) { year, years, weekStart ->
            Triple(year, years, weekStart)
        }.flatMapLatest { (year, years, weekStart) ->
            val bounds = yearBounds(year, timeZone)
            val reads =
                combine(
                    statsRepository.observePeriodStatsIn(bounds),
                    statsRepository.observeTimedSessionsIn(bounds),
                    statsRepository.observeViewingsIn(bounds, MediaType.MOVIE),
                    statsRepository.observeViewingsIn(bounds, MediaType.TV_SHOW),
                    statsRepository.observeFinishedBooks(bounds),
                    ::YearReads,
                )
            // The year's favorites (#232): each medium's best by that year's ratings only.
            val favoritesInYear =
                combine(
                    statsRepository.observeRatedItemsIn(bounds, MediaType.BOOK),
                    statsRepository.observeRatedItemsIn(bounds, MediaType.MOVIE),
                    statsRepository.observeRatedItemsIn(bounds, MediaType.TV_SHOW),
                    ::favorites,
                )
            combine(reads, settingsRepository.observeReadingGoal(year), favoritesInYear) { r, goal, favs ->
                YearReviewUiState(
                    isLoading = false,
                    years = years,
                    year = year,
                    review =
                        yearReview(
                            year = year,
                            timeZone = timeZone,
                            weekStart = weekStart,
                            periodStats = r.periodStats,
                            sessions = r.sessions,
                            films = r.films,
                            episodes = r.episodes,
                            finishedBooks = r.finishedBooks,
                            goal = goal,
                            favorites = favs,
                        ),
                )
            }.flowOn(Dispatchers.Default)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5.seconds),
            initialValue = YearReviewUiState(),
        )

    /** Shows [year]'s review. */
    public fun selectYear(year: Int) {
        selected.value = year
    }
}

/** One year's reads, together, so they reach [yearReview] typed rather than as a list. */
private data class YearReads(
    val periodStats: PeriodStatsRow,
    val sessions: List<TimedSessionRow>,
    val films: List<ViewingRow>,
    val episodes: List<ViewingRow>,
    val finishedBooks: List<FinishedBookRow>,
)
