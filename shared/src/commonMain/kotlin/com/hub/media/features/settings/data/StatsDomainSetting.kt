package com.hub.media.features.settings.data

import com.hub.media.core.database.entities.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Stats screen's last-picked tab -- Books, Films or Shows (#217) -- remembered so the hub's Stats
 * row opens where the user left off. A library's stats button opens its own tab instead, and picking
 * it is remembered the same way.
 *
 * A view preference, like the remembered period, so it is deliberately absent from
 * `EXPORTABLE_SETTING_KEYS`: importing an archive should not move someone's Stats screen.
 */
private const val KEY_STATS_DOMAIN = "stats_domain"

/** The remembered tab; [MediaType.BOOK] when none is stored, or the stored value is unknown. */
public fun SettingsRepository.observeStatsDomain(): Flow<MediaType> =
    observeString(KEY_STATS_DOMAIN).map { stored ->
        MediaType.entries.firstOrNull { it.name == stored } ?: MediaType.BOOK
    }

/** Remembers [value] as the Stats screen's tab. */
public suspend fun SettingsRepository.setStatsDomain(value: MediaType) {
    setString(KEY_STATS_DOMAIN, value.name)
}
