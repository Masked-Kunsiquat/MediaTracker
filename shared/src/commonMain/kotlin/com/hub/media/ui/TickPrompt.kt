package com.hub.media.ui

import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.WatchLogEntity
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
 * A just-cleared watch state, offered back as Undo (#131). There is only ever one: an untick made
 * while another's Undo is still on offer is **merged** into it ([mergeUntick]), so a second quick
 * untick never costs the first its way back.
 *
 * @property previous The viewings exactly as removed (#90); Undo restores an episode's viewings
 *   only if it still exists, is unwatched and has none.
 * @property label The episode's title (or `null` for "Episode N"); for a season, `null` with
 *   [seasonNumber] set.
 */
public data class UntickEvent(
    val id: Long,
    val previous: List<WatchLogEntity>,
    val label: String?,
    val episodeNumber: Int,
    val seasonNumber: Int,
    val isSeason: Boolean,
    /** True once two or more unticks share this Undo; the UI then says "Unmarked N episodes". */
    val merged: Boolean = false,
)

/**
 * Folds [next] into a still-pending [pending] Undo, so both sets of dates come back together. A
 * re-tick in between would make the earlier episode's restore a no-op anyway, so concatenating the
 * two lists is exact.
 */
internal fun mergeUntick(
    pending: UntickEvent?,
    next: UntickEvent,
): UntickEvent = if (pending == null) next else next.copy(previous = pending.previous + next.previous, merged = true)

private fun hasPastAirDate(
    e: EpisodeEntity,
    now: Instant,
    zone: TimeZone,
): Boolean = resolveWatchedAt(WatchedWhen.OnAirDate, e.airDate, now, zone) != null

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
    zone: TimeZone = TimeZone.currentSystemDefault(),
): PendingTick {
    val counted = isAired(target.airDate, now) && (includeSpecials || target.seasonNumber > 0)
    val earlier = if (counted) earlierUnwatched(episodes, target, includeSpecials, now) else emptyList()
    val targetDated = hasPastAirDate(target, now, zone)
    return PendingTick(
        title = target.title,
        seasonNumber = target.seasonNumber,
        episodeNumber = target.episodeNumber,
        isSeason = false,
        targetIds = listOf(target.id),
        earlierIds = earlier.map { it.id },
        airDateAvailable = targetDated,
        airDateAvailableWithEarlier = targetDated && earlier.all { hasPastAirDate(it, now, zone) },
        // Only a day the picker can actually select: preselecting a future, disabled day would
        // let "Mark watched" confirm it and silently store now instead.
        initialPickMillis = target.airDate?.takeIf { targetDated }?.toEpochMilliseconds(),
    )
}

/** The prompt for marking a season: its unwatched, aired episodes, or `null` when there are none. */
internal fun buildSeasonTick(
    seasonNumber: Int,
    seasonEpisodes: List<EpisodeEntity>,
    now: Instant,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): PendingTick? {
    val markable = markableIn(seasonEpisodes, now)
    if (markable.isEmpty()) return null
    val dated = markable.all { hasPastAirDate(it, now, zone) }
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

/** The episode sheet's subject (#90): the episode and its viewings, newest first. */
public data class OpenEpisode(
    val episode: EpisodeEntity,
    val viewings: List<WatchLogEntity>,
)

/**
 * Just-deleted viewings, offered back as one Undo (#90). A delete made while another's Undo is on
 * offer is merged into it, as an untick is ([mergeUntick]), so a second quick delete never costs
 * the first its way back.
 */
public data class ViewingUndo(
    val id: Long,
    val logs: List<WatchLogEntity>,
)
