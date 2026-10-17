package com.hub.media.features.stats.data

import com.hub.media.core.database.dao.ViewingRow
import com.hub.media.features.settings.data.WeekStartDay
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [viewingBreakdown] (#217): what the Films and Shows tabs show, from one read of the period's
 * viewings. A viewing stored at exactly noon is a picked date: its day is real, its time is not.
 */
class ViewingBreakdownTest {
    private val kolkata = TimeZone.of("Asia/Kolkata")

    // Monday 17 June 2024, in Kolkata.
    private fun june17(
        hour: Int,
        minute: Int = 0,
        second: Int = 0,
        nanos: Int = 0,
    ) = LocalDateTime(2024, 6, 17, hour, minute, second, nanos).toInstant(kolkata)

    private fun row(
        at: kotlin.time.Instant,
        mediaId: String = "f1",
        title: String = "Arrival",
        runtimeMinutes: Int? = 100,
        isRewatch: Boolean = false,
    ) = ViewingRow(at, mediaId, null, title, runtimeMinutes, isRewatch)

    private fun breakdown(
        vararg rows: ViewingRow,
        weekStart: WeekStartDay = WeekStartDay.MONDAY,
        withMonths: Boolean = false,
    ) = viewingBreakdown(rows.toList(), kolkata, weekStart, withMonths)

    @Test
    fun exactlyNoon_isAPickedDate_toTheMillisecond() {
        assertTrue(isPickedDate(june17(12), kolkata))
        assertFalse(isPickedDate(june17(12, nanos = 1_000_000), kolkata), "12:00:00.001 is a real time")
        assertFalse(isPickedDate(june17(12, second = 1), kolkata))
        assertFalse(isPickedDate(june17(11, 59, 59, 999_000_000), kolkata))
    }

    @Test
    fun aPickedDate_staysOutOfTheClock_butCountsByDayAndMonth() {
        val b = breakdown(row(june17(12)), row(june17(12, nanos = 1_000_000)), withMonths = true)

        assertEquals(1, b.hours.sum(), "only the real 12:00:00.001 viewing is on the clock")
        assertEquals(1, b.hours[12])
        assertEquals(1, b.dayParts.sum())
        assertEquals(1, b.untimed)
        assertEquals(2, b.weekdayCounts.sum(), "both count on their day")
        assertEquals(2, b.months!![5], "both count in June")
    }

    @Test
    fun unknownRuntimes_areCounted_notAddedAsZero() {
        val b = breakdown(row(june17(20), runtimeMinutes = 120), row(june17(21), runtimeMinutes = null))

        assertEquals(7_200L, b.knownSeconds)
        assertEquals(1, b.unknownRuntime)
    }

    @Test
    fun noKnownRuntimeAtAll_isUnknown_notZero() {
        val b = breakdown(row(june17(20), runtimeMinutes = null))

        assertEquals(null, b.knownSeconds)
        assertEquals(1, b.unknownRuntime)
    }

    @Test
    fun viewingsRewatchesAndTitles() {
        val b =
            breakdown(
                row(june17(19), mediaId = "f1"),
                row(june17(20), mediaId = "f1", isRewatch = true),
                row(june17(21), mediaId = "f2", title = "Dune"),
            )

        assertEquals(3, b.viewings)
        assertEquals(1, b.rewatches)
        assertEquals(2, b.titles)
    }

    @Test
    fun theWeekdaysStartOnTheWeekStart() {
        val b = breakdown(row(june17(20)), weekStart = WeekStartDay.SUNDAY)

        assertEquals(DayOfWeek.SUNDAY.isoDayNumber, b.weekdays.first())
        assertEquals(listOf(0, 1, 0, 0, 0, 0, 0), b.weekdayCounts, "Monday is second when Sunday starts")
    }

    @Test
    fun theMonths_areOnlyThereWhenAsked() {
        assertEquals(null, breakdown(row(june17(20))).months)
        assertEquals(12, breakdown(row(june17(20)), withMonths = true).months!!.size)
    }

    @Test
    fun theTopTitles_byKnownTime_fiveAtMost_tiesByTitle() {
        val b =
            breakdown(
                row(june17(1), mediaId = "a", title = "Andor", runtimeMinutes = 50),
                row(june17(2), mediaId = "b", title = "Bluey", runtimeMinutes = 10),
                row(june17(3), mediaId = "c", title = "Cheers", runtimeMinutes = 30),
                row(june17(4), mediaId = "d", title = "Dark", runtimeMinutes = 30),
                row(june17(5), mediaId = "e", title = "Extras", runtimeMinutes = 20),
                row(june17(6), mediaId = "f", title = "Frasier", runtimeMinutes = 40),
                row(june17(7), mediaId = "g", title = "Ghosts", runtimeMinutes = null),
            )

        assertEquals(listOf("Andor", "Frasier", "Cheers", "Dark", "Extras"), b.topTitles.map { it.title })
        assertEquals(TitleTime("Andor", 3_000), b.topTitles.first())
    }

    @Test
    fun nothingWatched_isEmpty_withTheWeekStillLaidOut() {
        val b = breakdown()

        assertEquals(0, b.viewings)
        assertEquals(null, b.knownSeconds)
        assertEquals(7, b.weekdays.size)
        assertEquals(List(24) { 0 }, b.hours)
    }
}
