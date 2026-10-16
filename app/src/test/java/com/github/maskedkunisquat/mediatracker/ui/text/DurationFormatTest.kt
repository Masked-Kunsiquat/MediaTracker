package com.github.maskedkunisquat.mediatracker.ui.text

import org.junit.Assert.assertEquals
import org.junit.Test

/** [formatDuration] (#219): "22h 48m", not the clock-like "22:48" Stats used to show. */
class DurationFormatTest {
    @Test
    fun underAMinute_isZeroMinutes() {
        assertEquals("0m", formatDuration(0))
        assertEquals("0m", formatDuration(59))
    }

    @Test
    fun underAnHour_isMinutesOnly() {
        assertEquals("45m", formatDuration(2_700))
    }

    @Test
    fun anHourOrMore_isHoursAndMinutes() {
        assertEquals("1h 0m", formatDuration(3_600))
        assertEquals("22h 48m", formatDuration(82_080))
    }
}
