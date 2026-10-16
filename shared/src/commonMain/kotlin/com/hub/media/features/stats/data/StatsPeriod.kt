package com.hub.media.features.stats.data

import com.hub.media.features.settings.data.WeekStartDay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The periods the Stats screen's picker offers (#91), in picker order. Every stats view the theme
 * adds later (#219, #217, #221) reads the same periods, so they are defined once, here.
 */
public enum class StatsPeriod {
    WEEK,
    MONTH,
    LAST_30_DAYS,
    YEAR,
    ALL_TIME,
}

/**
 * The half-open `[from, to)` range [this] period covers right now, at local-day boundaries in
 * [timeZone] -- or `null` for [StatsPeriod.ALL_TIME], which is unbounded.
 *
 * - [StatsPeriod.WEEK] and [StatsPeriod.MONTH] are the calendar periods the screen always had
 *   ([StatsRepository.thisWeekBounds] honours [weekStartDay]).
 * - [StatsPeriod.LAST_30_DAYS] is today and the 29 days before it: a rolling window that includes
 *   today, so a session logged a minute ago counts, and that does not dip at the start of a month
 *   the way "this month" does.
 * - [StatsPeriod.YEAR] is the local calendar year. Local, not UTC: at 23:30 on 31 December in a
 *   zone behind UTC, the clock has already rolled over in UTC, but the session still belongs to
 *   this year.
 */
public fun StatsPeriod.bounds(
    timeZone: TimeZone,
    clock: Clock,
    weekStartDay: WeekStartDay,
): Pair<Instant, Instant>? {
    val today = clock.now().toLocalDateTime(timeZone).date
    return when (this) {
        StatsPeriod.WEEK -> StatsRepository.thisWeekBounds(timeZone, clock, weekStartDay)
        StatsPeriod.MONTH -> StatsRepository.thisMonthBounds(timeZone, clock)
        StatsPeriod.LAST_30_DAYS ->
            today.minus(29, DateTimeUnit.DAY).atStartOfDayIn(timeZone) to
                today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone)
        StatsPeriod.YEAR ->
            LocalDate(today.year, 1, 1).atStartOfDayIn(timeZone) to
                LocalDate(today.year + 1, 1, 1).atStartOfDayIn(timeZone)
        StatsPeriod.ALL_TIME -> null
    }
}
