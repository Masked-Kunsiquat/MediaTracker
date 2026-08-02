package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.WeekStartDay
import com.hub.media.features.settings.data.observeWeekStartDay
import com.hub.media.features.settings.data.setWeekStartDay
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the Settings screen (ROADMAP Task 7 Phase B).
 *
 * [uiState] wraps [SettingsRepository.observeWeekStartDay] into a single hot [StateFlow], following
 * [LibraryViewModel]/[StatsViewModel]'s `stateIn`/`WhileSubscribed` convention — currently just one
 * combined-in field, but structured as a `map` over the repository's own reactive accessor(s)
 * rather than a one-off property so a future setting is added the same way this one was: a new
 * `observeXxx()` accessor on [SettingsRepository] (or a sibling wrapper file, mirroring how
 * `WeekStartDay.kt` wraps [SettingsRepository]'s generic `observeString`), folded into this same
 * `uiState` via [kotlinx.coroutines.flow.combine] once there is more than one, and a new action
 * method alongside [setWeekStartDay] below.
 *
 * ### Reactivity
 * Because [uiState] is built directly from [SettingsRepository.observeWeekStartDay] (itself
 * reactive over the underlying Room `Flow`), a change made by [setWeekStartDay] — or by any other
 * writer of the same key — is reflected here immediately, with no re-subscribe/re-construction
 * needed. `StatsViewModel` relies on this same reactivity to re-bucket its "this week" period the
 * moment the preference changes (see its KDoc).
 *
 * @param settingsRepository Source of the reactive week-start-day preference and the target of
 *   [setWeekStartDay]'s writes.
 */
public class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    public val uiState: StateFlow<SettingsUiState> = settingsRepository.observeWeekStartDay()
        .map { weekStartDay -> SettingsUiState(weekStartDay = weekStartDay) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5.seconds),
            initialValue = SettingsUiState(),
        )

    /**
     * Persists [value] as the new week-start-day preference. Fire-and-forget: [uiState] reflects
     * the change reactively once the write completes (see class KDoc "Reactivity"), so no result
     * needs to be threaded back to the caller here — mirroring [LibraryViewModel.deleteBook]'s same
     * fire-and-forget shape for a write whose only visible effect is a reactive re-emission.
     */
    public fun setWeekStartDay(value: WeekStartDay) {
        viewModelScope.launch {
            settingsRepository.setWeekStartDay(value)
        }
    }
}
