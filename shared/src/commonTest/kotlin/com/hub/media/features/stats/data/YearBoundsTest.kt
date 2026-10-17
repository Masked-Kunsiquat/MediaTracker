package com.hub.media.features.stats.data

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

/** [yearBounds] (#221): a year is the local calendar year, not UTC's. */
class YearBoundsTest {
    @Test
    fun aYear_isLocalMidnightToLocalMidnight() {
        val newYork = TimeZone.of("America/New_York")

        assertEquals(
            LocalDateTime(2026, 1, 1, 0, 0).toInstant(newYork) to LocalDateTime(2027, 1, 1, 0, 0).toInstant(newYork),
            yearBounds(2026, newYork),
        )
    }
}
