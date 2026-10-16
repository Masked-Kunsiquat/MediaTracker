package com.hub.media.features.stats.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * [localDateChanges] (#219 review): the local date now, and again each time it changes, so a screen
 * left open across midnight -- or New Year -- moves on instead of keeping yesterday's "today".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalDateChangesTest {
    private val zone = TimeZone.of("America/New_York")
    private var clockNow: Instant = LocalDateTime(2024, 12, 31, 23, 59).toInstant(zone)
    private val clock =
        object : Clock {
            override fun now(): Instant = clockNow
        }

    @Test
    fun emitsToday_thenTheNewDateAfterLocalMidnight() =
        runTest {
            val dates = mutableListOf<LocalDate>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                localDateChanges(clock, zone).collect { dates += it }
            }
            assertEquals(listOf(LocalDate(2024, 12, 31)), dates)

            // Local midnight in New York, not UTC's: UTC turned over five hours ago.
            clockNow = LocalDateTime(2025, 1, 1, 0, 0, 30).toInstant(zone)
            advanceTimeBy(2.minutes)
            runCurrent()

            assertEquals(listOf(LocalDate(2024, 12, 31), LocalDate(2025, 1, 1)), dates)
        }

    @Test
    fun noNewDate_untilTheDateActuallyChanges() =
        runTest {
            clockNow = LocalDateTime(2024, 6, 19, 9, 0).toInstant(zone)
            val dates = mutableListOf<LocalDate>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                localDateChanges(clock, zone).collect { dates += it }
            }

            // Its wake-up at midnight finds the clock still in the same day (a late or early timer):
            // nothing new is published.
            advanceTimeBy(20.hours)
            runCurrent()

            assertEquals(listOf(LocalDate(2024, 6, 19)), dates)
        }
}
