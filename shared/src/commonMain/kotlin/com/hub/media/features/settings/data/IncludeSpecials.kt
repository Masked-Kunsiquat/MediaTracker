package com.hub.media.features.settings.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Whether specials (season 0) count toward a show's progress and completion (#88).
 *
 * Defaults to `true`, which is what the app did before this setting existed, so a user who never
 * opens Settings sees no change. Turned off, a show whose regular seasons are all watched reads as
 * finished even with specials outstanding -- see
 * [com.hub.media.core.database.dao.TVProgressCounts.counted] for the one place the rule is applied.
 *
 * ### One preference, not two
 * This is deliberately the *only* specials preference. #122 asks whether the TMDB backfill should
 * create season 0, and the answer is meant to read this same flag: "count specials" and "fetch
 * specials" are one user preference seen from two sides, and two settings could disagree.
 *
 * Nothing is stored per show: completion is derived on every read, so flipping this is a pure
 * recompute with no data rewritten.
 */
private const val KEY_INCLUDE_SPECIALS = "include_specials"

/**
 * Reactive current include-specials preference. `true` when never set, or when the stored value is
 * malformed -- [SettingsRepository.observeBoolean]'s rule, resolved to the default here.
 */
public fun SettingsRepository.observeIncludeSpecials(): Flow<Boolean> =
    observeBoolean(KEY_INCLUDE_SPECIALS).map { it ?: true }

/** One-shot fetch of the include-specials preference; see [observeIncludeSpecials] for the default. */
public suspend fun SettingsRepository.getIncludeSpecials(): Boolean = getBoolean(KEY_INCLUDE_SPECIALS) ?: true

/** Persists the include-specials preference under [KEY_INCLUDE_SPECIALS]. */
public suspend fun SettingsRepository.setIncludeSpecials(value: Boolean) {
    setBoolean(KEY_INCLUDE_SPECIALS, value)
}
