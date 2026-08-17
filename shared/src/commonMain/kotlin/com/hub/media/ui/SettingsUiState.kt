package com.hub.media.ui

import com.hub.media.core.util.LogLevel
import com.hub.media.features.settings.data.DEFAULT_LOG_VERBOSITY
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
 * @property logVerbosity The current log-verbosity preference (ROADMAP Task 15 Phase B2),
 *   defaulting to [DEFAULT_LOG_VERBOSITY] so the very first composition already shows the right
 *   value rather than a placeholder -- the same guarantee [weekStartDay] gives. Note this is the
 *   level shown in Settings; the level the *process* is currently running at can differ until the
 *   preference is applied, and differs deliberately when it has never been set (see
 *   [com.hub.media.features.settings.data.observeLogVerbosityOrNull]).
 * @property googleBooksApiKeySet Whether a user-supplied Google Books API key is currently stored --
 *   **a boolean, deliberately not the key itself.** The key is a credential and this screen has no
 *   use for its value: a saved key is reported as saved, never echoed back into a field, and the
 *   only write path is the user typing a new one into a control that holds its own local state until
 *   Save. Carrying the value here would put a credential into an object held by a ViewModel,
 *   recomputed on every settings change, and trivially captured by any future "log the UI state"
 *   debugging line -- for no capability the screen actually needs. `false` is the right first-
 *   composition default: it renders the no-key state, which is also what a fresh install has.
 */
public data class SettingsUiState(
    val weekStartDay: WeekStartDay = WeekStartDay.MONDAY,
    val logVerbosity: LogLevel = DEFAULT_LOG_VERBOSITY,
    val googleBooksApiKeySet: Boolean = false,
)
