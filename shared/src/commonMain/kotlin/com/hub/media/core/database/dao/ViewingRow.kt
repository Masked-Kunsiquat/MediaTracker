package com.hub.media.core.database.dao

import kotlin.time.Instant

/**
 * One viewing for the Films and Shows tabs (#217): a `watch_logs` row with its title, the runtime of
 * the film or episode watched, and whether it was a rewatch.
 *
 * @property episodeId The episode, or `null` for a film.
 * @property runtimeMinutes The film's or the episode's runtime, or `null` when unknown -- never 0 as
 *   a stand-in. A viewing's own duration is never recorded, so this is the only source of time.
 * @property isRewatch An earlier viewing of the same film, or the same episode, exists -- at any
 *   time, including before the period being read.
 * @property coverImageHash The title's stored cover (#229), for the year in review; `null` when it has none.
 */
data class ViewingRow(
    val watchedAt: Instant,
    val mediaId: String,
    val episodeId: String?,
    val title: String,
    val runtimeMinutes: Int?,
    val isRewatch: Boolean,
    val coverImageHash: String? = null,
)
