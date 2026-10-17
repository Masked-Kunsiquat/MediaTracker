package com.hub.media.features.stats.data

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Instant

/**
 * [year] as `[1 January, next 1 January)` in [timeZone] (#221): the local calendar year, so a session
 * at 23:30 on 31 December belongs to that year wherever UTC has already turned. The same rule as
 * [StatsPeriod.YEAR]'s bounds, for any year rather than only this one.
 */
public fun yearBounds(
    year: Int,
    timeZone: TimeZone,
): Pair<Instant, Instant> =
    LocalDate(year, 1, 1).atStartOfDayIn(timeZone) to LocalDate(year + 1, 1, 1).atStartOfDayIn(timeZone)
