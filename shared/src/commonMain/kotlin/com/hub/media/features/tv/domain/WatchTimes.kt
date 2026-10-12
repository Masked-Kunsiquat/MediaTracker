package com.hub.media.features.tv.domain

import com.hub.media.core.database.entities.EpisodeEntity
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * When the user says they watched something (#131). See
 * `docs/superpowers/specs/2026-10-12-tick-with-date-design.md` for the rules each choice follows.
 */
public sealed interface WatchedWhen {
    public data object Now : WatchedWhen

    /** Each episode's own air date. Only offered when every affected episode has a past one. */
    public data object OnAirDate : WatchedWhen

    /** A day the user picked: stored as noon local, clamped to now. */
    public data class On(
        val date: LocalDate,
    ) : WatchedWhen
}

/**
 * The `watchedAt` to store, or `null` when [WatchedWhen.OnAirDate] cannot be honoured (no air date,
 * or one in the future).
 *
 * ### Noon local, clamped to [now]
 * A calendar day stored at midnight shifts to the neighbouring day as soon as it is read in another
 * zone; noon leaves twelve hours either side. Clamping keeps "today", picked before noon, from being
 * a future watch -- Up next sorts shows by `MAX(watchedAt)`, and Stats counts watch dates.
 *
 * ### TMDB air dates are read in UTC
 * `TmdbDates.toInstantOrNull` stores a `YYYY-MM-DD` air date as midnight UTC, so its calendar day is
 * the UTC one. Writing that instant as-is would show the previous day anywhere west of UTC -- the
 * very bug the noon rule exists to prevent.
 */
public fun resolveWatchedAt(
    watchedWhen: WatchedWhen,
    airDate: Instant?,
    now: Instant,
    zone: TimeZone,
): Instant? {
    val day =
        when (watchedWhen) {
            WatchedWhen.Now -> return now
            WatchedWhen.OnAirDate ->
                airDate?.takeIf { it <= now }?.toLocalDateTime(TimeZone.UTC)?.date ?: return null
            is WatchedWhen.On -> watchedWhen.date
        }
    return minOf(LocalDateTime(day, LocalTime(12, 0)).toInstant(zone), now)
}

/**
 * Aired means no air date (quick-filled) or one not after [now] -- the same rule
 * `EpisodeDao.observeUpNext` applies in SQL.
 */
public fun isAired(
    airDate: Instant?,
    now: Instant,
): Boolean = airDate == null || airDate <= now

/**
 * Regular seasons ascending, then specials: `EpisodeDao.observeUpNext`'s `ORDER BY`, so the tick
 * prompt and Up next agree on what comes "before" an episode.
 */
public val upNextOrder: Comparator<EpisodeEntity> =
    compareBy<EpisodeEntity>({ it.seasonNumber == 0 }, { it.seasonNumber }, { it.episodeNumber })

/**
 * The catch-up candidates when ticking [target] (#131): unwatched, aired episodes that sort before
 * it under [upNextOrder], with specials only while [includeSpecials] is on (#88).
 */
public fun earlierUnwatched(
    episodes: List<EpisodeEntity>,
    target: EpisodeEntity,
    includeSpecials: Boolean,
    now: Instant,
): List<EpisodeEntity> =
    episodes
        .filter { it.id != target.id && it.watchedAt == null && isAired(it.airDate, now) }
        .filter { includeSpecials || it.seasonNumber > 0 }
        .filter { upNextOrder.compare(it, target) < 0 }
        .sortedWith(upNextOrder)
