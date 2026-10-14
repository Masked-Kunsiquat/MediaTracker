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
            // The air *day* (read in UTC, as stored) must not be after today *locally*. Comparing
            // instants instead would offer an episode dated tomorrow on the evening before it,
            // west of UTC -- and the clamp would then store the day before its air date.
            WatchedWhen.OnAirDate ->
                airDate
                    ?.toLocalDateTime(TimeZone.UTC)
                    ?.date
                    ?.takeIf { it <= now.toLocalDateTime(zone).date } ?: return null
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

/**
 * [WatchedWhen.On] for a day chosen in Material's `DatePicker`, which reports the selection as
 * **UTC-midnight** epoch millis -- so the calendar day is read in UTC, never in the device zone.
 * Lives here so the app module needs no date library of its own.
 */
public fun watchedOnPickedDay(utcMidnightMillis: Long): WatchedWhen =
    WatchedWhen.On(Instant.fromEpochMilliseconds(utcMidnightMillis).toLocalDateTime(TimeZone.UTC).date)

/**
 * The inverse of [watchedOnPickedDay]: a picked day as UTC-midnight millis, or `null` for any other
 * answer. Lets a form hold its "when?" answer in `rememberSaveable` (#192), which cannot hold a
 * [WatchedWhen].
 */
public fun WatchedWhen.pickedDayMillis(): Long? =
    (this as? WatchedWhen.On)?.date?.let {
        LocalDateTime(it, LocalTime(0, 0)).toInstant(TimeZone.UTC).toEpochMilliseconds()
    }

/**
 * Whether the picker may offer the day at [utcMidnightMillis]: not after today in [zone]. A watch
 * cannot be in the future (#131).
 */
public fun isPickableDay(
    utcMidnightMillis: Long,
    now: Instant =
        kotlin.time.Clock.System
            .now(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Boolean =
    Instant.fromEpochMilliseconds(utcMidnightMillis).toLocalDateTime(TimeZone.UTC).date <=
        now.toLocalDateTime(zone).date

/** The latest year the picker may show -- this year in [zone]. */
public fun pickableUpToYear(
    now: Instant =
        kotlin.time.Clock.System
            .now(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Int = now.toLocalDateTime(zone).year
