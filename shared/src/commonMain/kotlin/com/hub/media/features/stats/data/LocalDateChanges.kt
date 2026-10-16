package com.hub.media.features.stats.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Today's date in [timeZone], then the new date each time local midnight passes (#219 review), so a
 * screen left open overnight -- or across New Year -- moves on by itself.
 *
 * It sleeps until the next local midnight and then re-reads [clock] rather than trusting the sleep:
 * a phone in Doze wakes late, and a timer can fire a moment early, so the date is always the clock's,
 * never "the previous date plus one". A wake-up that finds the same date publishes nothing.
 */
public fun localDateChanges(
    clock: Clock,
    timeZone: TimeZone,
): Flow<LocalDate> =
    flow {
        while (true) {
            val now = clock.now()
            val today = now.toLocalDateTime(timeZone).date
            emit(today)
            val nextMidnight = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone)
            // At least a second, so a clock sitting exactly on midnight cannot spin.
            delay((nextMidnight - now).coerceAtLeast(1.seconds))
        }
    }.distinctUntilChanged()
