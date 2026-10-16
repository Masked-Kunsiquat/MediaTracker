package com.hub.media.features.stats.data

import com.hub.media.core.database.dao.TimedSessionRow
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.features.settings.data.WeekStartDay
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [readingBreakdown] (#219): when you read, top authors and formats, all from the same timed
 * sessions, in the phone's zone, by time read.
 */
class ReadingBreakdownTest {
    private val utc = TimeZone.UTC

    private fun row(
        at: LocalDateTime,
        seconds: Long,
        format: BookFormat = BookFormat.PAPERBACK,
        authors: String? = "Ann Leckie",
        zone: TimeZone = utc,
    ) = TimedSessionRow(at.toInstant(zone), seconds, format, authors)

    private fun breakdown(
        vararg rows: TimedSessionRow,
        zone: TimeZone = utc,
        weekStart: WeekStartDay = WeekStartDay.MONDAY,
    ) = readingBreakdown(rows.toList(), zone, weekStart)

    // Monday 17 June 2024.
    private fun june17(
        hour: Int,
        minute: Int = 0,
    ) = LocalDateTime(2024, 6, 17, hour, minute)

    @Test
    fun theHourBoundaries() {
        val expected =
            mapOf(
                0 to DayPart.NIGHT,
                4 to DayPart.NIGHT,
                5 to DayPart.MORNING,
                11 to DayPart.MORNING,
                12 to DayPart.AFTERNOON,
                16 to DayPart.AFTERNOON,
                17 to DayPart.EVENING,
                20 to DayPart.EVENING,
                21 to DayPart.NIGHT,
                23 to DayPart.NIGHT,
            )
        for ((hour, part) in expected) assertEquals(part, dayPartOf(hour), "hour $hour")
    }

    @Test
    fun aSessionCountsInTheLocalZone_partOfDayAndWeekday() {
        val newYork = TimeZone.of("America/New_York")
        // 03:30 UTC on Monday is 23:30 on Sunday in New York.
        val b = breakdown(row(june17(3, 30), 600), zone = newYork)

        assertEquals(600L, b.dayParts[DayPart.NIGHT.ordinal])
        assertEquals(600L, b.weekdaySeconds[b.weekdays.indexOf(DayOfWeek.SUNDAY)])
        assertEquals(0L, b.weekdaySeconds[b.weekdays.indexOf(DayOfWeek.MONDAY)])
    }

    @Test
    fun theWeekdaysStartOnTheWeekStart() {
        assertEquals(DayOfWeek.MONDAY, breakdown(weekStart = WeekStartDay.MONDAY).weekdays.first())
        val sunday = breakdown(row(june17(10), 60), weekStart = WeekStartDay.SUNDAY)
        assertEquals(DayOfWeek.SUNDAY, sunday.weekdays.first())
        assertEquals(7, sunday.weekdays.distinct().size)
        // Monday is the second slot when the week starts on Sunday.
        assertEquals(listOf(0L, 60L, 0L, 0L, 0L, 0L, 0L), sunday.weekdaySeconds)
    }

    @Test
    fun spellingsOfOneAuthor_countAsOne_underTheMostReadSpelling() {
        val b =
            breakdown(
                row(june17(10), 600, authors = "J.R.R. Tolkien"),
                row(june17(11), 1_200, authors = "J. R. R. Tolkien"),
            )

        assertEquals(listOf(AuthorTime("J. R. R. Tolkien", 1_800)), b.topAuthors)
    }

    @Test
    fun eachCoAuthor_getsTheFullTime() {
        val b = breakdown(row(june17(10), 900, authors = "Terry Pratchett; Neil Gaiman"))

        assertEquals(
            listOf(AuthorTime("Neil Gaiman", 900), AuthorTime("Terry Pratchett", 900)),
            b.topAuthors,
        )
    }

    @Test
    fun aBookWithNoAuthor_isLeftOutOfAuthors_butCountsEverywhereElse() {
        val b = breakdown(row(june17(10), 300, format = BookFormat.EBOOK, authors = null))

        assertEquals(emptyList(), b.topAuthors)
        assertEquals(listOf(FormatTime(BookFormat.EBOOK, 300)), b.formats)
        assertEquals(300L, b.dayParts[DayPart.MORNING.ordinal])
    }

    @Test
    fun aPunctuationOnlyName_isNotAnAuthor() {
        val b = breakdown(row(june17(10), 300, authors = "—; Becky Chambers"))

        assertEquals(listOf(AuthorTime("Becky Chambers", 300)), b.topAuthors)
    }

    @Test
    fun onlyTheTopFive_tiesByName() {
        val b =
            breakdown(
                row(june17(10), 100, authors = "F"),
                row(june17(10), 500, authors = "A"),
                row(june17(10), 300, authors = "D"),
                row(june17(10), 300, authors = "C"),
                row(june17(10), 400, authors = "B"),
                row(june17(10), 50, authors = "E"),
            )

        assertEquals(listOf("A", "B", "C", "D", "F"), b.topAuthors.map { it.name })
    }

    @Test
    fun formats_mostTimeFirst_tiesInEnumOrder() {
        val b =
            breakdown(
                row(june17(10), 100, format = BookFormat.AUDIOBOOK),
                row(june17(10), 100, format = BookFormat.EBOOK),
                row(june17(10), 900, format = BookFormat.HARDCOVER),
            )

        assertEquals(
            listOf(BookFormat.HARDCOVER, BookFormat.EBOOK, BookFormat.AUDIOBOOK),
            b.formats.map { it.format },
        )
    }

    @Test
    fun theTotalsAgree_acrossAllThree() {
        val b =
            breakdown(
                row(june17(6), 600, format = BookFormat.EBOOK),
                row(june17(13), 1_200, format = BookFormat.PAPERBACK),
                row(LocalDateTime(2024, 6, 22, 22, 0), 300, format = BookFormat.EBOOK),
            )

        assertEquals(2_100L, b.totalSeconds)
        assertEquals(2_100L, b.dayParts.sum())
        assertEquals(2_100L, b.weekdaySeconds.sum())
        assertEquals(2_100L, b.formats.sumOf { it.seconds })
    }

    @Test
    fun noSessions_isAnEmptyBreakdown_withTheWeekStillLaidOut() {
        val b = breakdown()

        assertEquals(0L, b.totalSeconds)
        assertEquals(List(4) { 0L }, b.dayParts)
        assertEquals(List(7) { 0L }, b.weekdaySeconds)
        assertEquals(7, b.weekdays.size)
        assertEquals(emptyList(), b.topAuthors)
        assertEquals(emptyList(), b.formats)
    }
}
