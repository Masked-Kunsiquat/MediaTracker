package com.hub.media.ui

import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.features.tv.domain.WatchedWhen
import com.hub.media.features.tv.domain.earlierUnwatched
import com.hub.media.features.tv.domain.isAired
import com.hub.media.features.tv.domain.resolveWatchedAt
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * What the "Mark … watched?" dialog is about to write (#131). Shared by the show screen and Up next.
 *
 * The ids are **captured when the prompt opens** and never re-read from the live list on confirm,
 * so the write is exactly what the dialog described.
 *
 * @property title The episode's title, or `null` for a quick-filled one (the UI says "Episode N").
 * @property targetIds One explicitly ticked episode, or a season's markable episodes.
 * @property earlierIds Catch-up candidates; always empty for a season.
 * @property airDateAvailable Whether "On air date" can be honoured for [targetIds] alone.
 * @property airDateAvailableWithEarlier The same, with the catch-up included.
 * @property initialPickMillis The target's air date as midnight-UTC epoch millis -- exactly what
 *   Material's `DatePicker` expects -- so the picker opens on it; `null` opens on today.
 */
public data class PendingTick(
    val title: String?,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val isSeason: Boolean,
    val targetIds: List<String>,
    val earlierIds: List<String>,
    val airDateAvailable: Boolean,
    val airDateAvailableWithEarlier: Boolean,
    val initialPickMillis: Long?,
)

/**
 * A just-cleared watch state, offered back as Undo (#131). Only the latest is live: a newer untick
 * replaces it, and its snackbar is dismissed.
 *
 * @property previous `episodeId -> watchedAt` exactly as cleared; Undo restores each only if that
 *   episode is still unwatched.
 * @property label The episode's title (or `null` for "Episode N"); for a season, `null` with
 *   [seasonNumber] set.
 */
public data class UntickEvent(
    val id: Long,
    val previous: Map<String, Instant>,
    val label: String?,
    val episodeNumber: Int,
    val seasonNumber: Int,
    val isSeason: Boolean,
)

private fun hasPastAirDate(
    e: EpisodeEntity,
    now: Instant,
): Boolean = resolveWatchedAt(WatchedWhen.OnAirDate, e.airDate, now, TimeZone.UTC) != null

/**
 * The prompt for ticking [target] by hand. Catch-up is offered only when the target itself is aired
 * and counted (a special while specials are excluded gets none), per the spec's "unaired or
 * excluded target" rule.
 */
internal fun buildPendingTick(
    target: EpisodeEntity,
    episodes: List<EpisodeEntity>,
    includeSpecials: Boolean,
    now: Instant,
): PendingTick {
    val counted = isAired(target.airDate, now) && (includeSpecials || target.seasonNumber > 0)
    val earlier = if (counted) earlierUnwatched(episodes, target, includeSpecials, now) else emptyList()
    val targetDated = hasPastAirDate(target, now)
    return PendingTick(
        title = target.title,
        seasonNumber = target.seasonNumber,
        episodeNumber = target.episodeNumber,
        isSeason = false,
        targetIds = listOf(target.id),
        earlierIds = earlier.map { it.id },
        airDateAvailable = targetDated,
        airDateAvailableWithEarlier = targetDated && earlier.all { hasPastAirDate(it, now) },
        initialPickMillis = target.airDate?.toEpochMilliseconds(),
    )
}

/** The prompt for marking a season: its unwatched, aired episodes, or `null` when there are none. */
internal fun buildSeasonTick(
    seasonNumber: Int,
    seasonEpisodes: List<EpisodeEntity>,
    now: Instant,
): PendingTick? {
    val markable = markableIn(seasonEpisodes, now)
    if (markable.isEmpty()) return null
    val dated = markable.all { hasPastAirDate(it, now) }
    return PendingTick(
        title = null,
        seasonNumber = seasonNumber,
        episodeNumber = 0,
        isSeason = true,
        targetIds = markable.map { it.id },
        earlierIds = emptyList(),
        airDateAvailable = dated,
        airDateAvailableWithEarlier = dated,
        initialPickMillis = null,
    )
}

/** A season's unwatched, aired episodes -- what "Mark season watched" would write. */
internal fun markableIn(
    seasonEpisodes: List<EpisodeEntity>,
    now: Instant,
): List<EpisodeEntity> = seasonEpisodes.filter { it.watchedAt == null && isAired(it.airDate, now) }
