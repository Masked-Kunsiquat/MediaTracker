package com.hub.media.features.settings.data

import com.hub.media.features.stats.data.StatsPeriod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Stats screen's last-picked period (#91), remembered so the screen reopens where it was left.
 *
 * A view preference -- which tab was open -- not configuration, so it is deliberately absent from
 * `EXPORTABLE_SETTING_KEYS`: importing an archive export should not move someone's Stats screen. A
 * whole-database `.sqlite` backup is a copy of everything, `app_settings` included, so a restore
 * from one does bring it back -- which is what a full restore should do.
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
