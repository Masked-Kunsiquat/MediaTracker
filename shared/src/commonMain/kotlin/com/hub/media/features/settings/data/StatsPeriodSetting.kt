package com.hub.media.features.settings.data

import com.hub.media.features.stats.data.StatsPeriod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Stats screen's last-picked period (#91), remembered so the screen reopens where it was left.
 *
 * A view preference -- which tab was open -- not configuration, so it is deliberately absent from
 * `EXPORTABLE_SETTING_KEYS`: restoring a backup should not move someone's Stats screen.
 */
private const val KEY_STATS_PERIOD = "stats_period"

/** The remembered period; [StatsPeriod.WEEK] when none is stored, or the stored value is unknown. */
public fun SettingsRepository.observeStatsPeriod(): Flow<StatsPeriod> =
    observeString(KEY_STATS_PERIOD).map { stored ->
        StatsPeriod.entries.firstOrNull { it.name == stored }
            ?: StatsPeriod.WEEK
    }

/** Remembers [value] as the Stats screen's period. */
public suspend fun SettingsRepository.setStatsPeriod(value: StatsPeriod) {
    setString(KEY_STATS_PERIOD, value.name)
}
