package com.hub.media.features.stats.data

import com.hub.media.features.settings.data.WeekStartDay
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * [StatsPeriod.bounds] (#91): where each period starts and ends, in the user's local time zone.
 * A non-UTC zone throughout, because a period computed in UTC is wrong exactly at the edges that
 * matter -- a session at 23:30 local on 31 December belongs to that year.
 */
class StatsPeriodBoundsTest {
    private val zone = TimeZone.of("America/New_York")

    private fun local(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
    ): Instant = LocalDateTime(year, month, day, hour, minute).toInstant(zone)

    private fun clockAt(instant: Instant) =
        object : Clock {
            override fun now(): Instant = instant
        }

    private val june19 = clockAt(local(2024, 6, 19, 15, 0))

    private fun StatsPeriod.at(clock: Clock = june19) = bounds(zone, clock, WeekStartDay.MONDAY)

    @Test
    fun week_andMonth_matchTheExistingCalendarBounds() {
        assertEquals(StatsRepository.thisWeekBounds(zone, june19, WeekStartDay.MONDAY), StatsPeriod.WEEK.at())
        assertEquals(StatsRepository.thisMonthBounds(zone, june19), StatsPeriod.MONTH.at())
    }

    @Test
    fun week_followsTheWeekStartDay() {
        val sunday = StatsPeriod.WEEK.bounds(zone, june19, WeekStartDay.SUNDAY)
        assertEquals(StatsRepository.thisWeekBounds(zone, june19, WeekStartDay.SUNDAY), sunday)
    }

    @Test
    fun last30Days_isTodayAndThe29DaysBeforeIt() {
        assertEquals(local(2024, 5, 21) to local(2024, 6, 20), StatsPeriod.LAST_30_DAYS.at())
    }

    @Test
    fun last30Days_edges_firstMidnightIn_minuteBeforeOut() {
        val (from, to) = StatsPeriod.LAST_30_DAYS.at()!!
        val firstMoment = local(2024, 5, 21, 0, 0)
        val minuteBefore = local(2024, 5, 20, 23, 59)
        assertTrue(firstMoment >= from && firstMoment < to)
        assertTrue(minuteBefore < from)
    }

    @Test
    fun year_isTheLocalCalendarYear() {
        assertEquals(local(2024, 1, 1) to local(2025, 1, 1), StatsPeriod.YEAR.at())
    }

    @Test
    fun year_atLocalNewYearsEveNight_stillThisYear_evenThoughUtcHasRolledOver() {
        val lateOnTheLastDay = local(2024, 12, 31, 23, 30)
        val (from, to) = StatsPeriod.YEAR.at(clockAt(lateOnTheLastDay))!!
        assertEquals(local(2024, 1, 1), from)
        assertEquals(local(2025, 1, 1), to)
        assertTrue(lateOnTheLastDay >= from && lateOnTheLastDay < to)
    }

    @Test
    fun allTime_isUnbounded() {
        assertNull(StatsPeriod.ALL_TIME.at())
    }

    @Test
    fun onlyWeek_dependsOnTheWeekStartDay() {
        for (period in listOf(StatsPeriod.MONTH, StatsPeriod.LAST_30_DAYS, StatsPeriod.YEAR, StatsPeriod.ALL_TIME)) {
            assertEquals(
                period.bounds(zone, june19, WeekStartDay.MONDAY),
                period.bounds(zone, june19, WeekStartDay.SUNDAY),
                "$period must not move with the week start",
            )
        }
        // Positive control: WEEK does move.
        assertTrue(
            StatsPeriod.WEEK.bounds(zone, june19, WeekStartDay.MONDAY) !=
                StatsPeriod.WEEK.bounds(zone, june19, WeekStartDay.SUNDAY),
        )
    }
}
