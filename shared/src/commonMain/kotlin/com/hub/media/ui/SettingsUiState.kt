package com.hub.media.ui

import com.hub.media.features.settings.data.WeekStartDay

/**
 * UI state for the Settings screen (ROADMAP Task 7 Phase B — the first occupant of a screen the
 * ROADMAP explicitly expects more settings to join later).
 *
 * Deliberately a flat, single-level data class rather than a sealed `Loading`/`Ready` hierarchy
 * like [EditBookUiState]/[BookDetailUiState]: every field here has a well-defined default that is
 * never itself "unknown" (see [WeekStartDay]'s KDoc — [SettingsViewModel.uiState] never has to wait
 * on a slow network call or represent a not-found case, unlike those two screens), so there is no
 * meaningful loading/error state to distinguish. Future settings added to this screen are expected
 * to add a field here the same way, keeping this class as the one extensible home for "current
 * value of every setting the screen shows" rather than each setting inventing its own state shape.
 *
 * @property weekStartDay The current week-start-day preference, reactively sourced from
 *   [com.hub.media.features.settings.data.SettingsRepository] via
 *   [com.hub.media.features.settings.data.observeWeekStartDay] — defaults to
 *   [WeekStartDay.MONDAY] both here and in that accessor, so the very first composition (before the
 *   underlying [kotlinx.coroutines.flow.Flow] has emitted) already shows the correct default rather
 *   than a placeholder.
 */
public data class SettingsUiState(
    val weekStartDay: WeekStartDay = WeekStartDay.MONDAY,
)
