package com.hub.media.features.stats.data

import com.hub.media.core.database.dao.FinishedBookRow
import com.hub.media.core.database.dao.PeriodStatsRow
import com.hub.media.core.database.dao.TimedSessionRow
import com.hub.media.core.database.dao.ViewingRow
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.features.settings.data.ReadingGoal
import com.hub.media.features.settings.data.WeekStartDay
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * [yearReview] (#221): every page of the year in review from one year's reads, one test per rule. All
 * times in New York, so a rule that slipped into UTC would move a day or a month.
 */
class YearReviewTest {
    private val zone = TimeZone.of("America/New_York")

    private fun at(
        month: Int,
        day: Int,
        hour: Int = 20,
        minute: Int = 0,
    ): Instant = LocalDateTime(2026, month, day, hour, minute).toInstant(zone)

    private fun stats(
        booksFinished: Int = 0,
        timeRead: Long? = null,
        sessions: Int = 0,
        timedSessions: Int = 0,
        firstFinishes: Int = booksFinished,
    ) = PeriodStatsRow(timeRead, sessions, null, booksFinished, timedSessions, null, null, null, firstFinishes)

    private fun session(
        at: Instant,
        seconds: Long,
        title: String = "Dune",
        authors: String? = "Frank Herbert",
    ) = TimedSessionRow(at, seconds, BookFormat.PAPERBACK, authors, mediaId = title, title = title)

    private fun film(
        at: Instant,
        title: String,
        runtime: Int? = 120,
    ) = ViewingRow(at, title, null, title, runtime, isRewatch = false)

    private fun episode(
        at: Instant,
        show: String,
        runtime: Int? = 50,
    ) = ViewingRow(at, show, "$show-${at.epochSeconds}", show, runtime, isRewatch = false)

    private fun book(
        title: String,
        pages: Int?,
        finishedAt: Instant,
    ) = FinishedBookRow(title, title, pages, null, finishedAt)

    private fun review(
        periodStats: PeriodStatsRow = stats(),
        sessions: List<TimedSessionRow> = emptyList(),
        films: List<ViewingRow> = emptyList(),
        episodes: List<ViewingRow> = emptyList(),
        books: List<FinishedBookRow> = emptyList(),
        goal: ReadingGoal? = null,
    ) = yearReview(2026, zone, WeekStartDay.MONDAY, periodStats, sessions, films, episodes, books, goal)

    @Test
    fun aQuietYear_hasZeroTotals_andNoHighlights() {
        val r = review()

        assertEquals(YearTotals(0, 0, 0, 0, 0, 0), r.totals)
        assertNull(r.longestBook)
        assertNull(r.topAuthor)
        assertNull(r.longestSession)
        assertNull(r.goal)
        assertNull(r.rewatchedFilm)
        assertNull(r.bingeDay)
        assertNull(r.rhythm)
    }

    @Test
    fun theTotals_addReadingToKnownWatching_andCountTheUnknown() {
        val r =
            review(
                periodStats = stats(booksFinished = 3, timeRead = 7_200),
                sessions = listOf(session(at(1, 5), 7_200)),
                films = listOf(film(at(2, 1), "Arrival", 116), film(at(2, 2), "Mystery", null)),
                episodes = listOf(episode(at(3, 1), "Severance", 50)),
            )

        assertEquals(YearTotals(3, 2, 1, 7_200, 116 * 60L + 50 * 60L, 1), r.totals)
    }

    @Test
    fun theLongestBook_byPages_tiesToTheEarliestFinished_ignoringUnknownPages() {
        val r =
            review(
                books =
                    listOf(
                        book("Later", 900, at(6, 1)),
                        book("Earlier", 900, at(2, 1)),
                        book("Short", 120, at(1, 1)),
                        book("Unknown", null, at(1, 2)),
                    ),
            )

        assertEquals(LongestBook("Earlier", 900), r.longestBook)
    }

    @Test
    fun theTopAuthor_isTheMostReadByTime() {
        val r =
            review(
                sessions =
                    listOf(
                        session(at(1, 1), 3_600, title = "Dune", authors = "Frank Herbert"),
                        session(at(1, 2), 9_000, title = "Piranesi", authors = "Susanna Clarke"),
                    ),
            )

        assertEquals(AuthorTime("Susanna Clarke", 9_000), r.topAuthor)
    }

    @Test
    fun theLongestSession_namesItsBook_tiesToTheEarliest() {
        val r =
            review(
                sessions =
                    listOf(
                        session(at(5, 1), 10_800, title = "Later"),
                        session(at(2, 1), 10_800, title = "Earlier"),
                        session(at(1, 1), 600, title = "Short"),
                    ),
            )

        assertEquals(LongestSession("Earlier", 10_800), r.longestSession)
    }

    @Test
    fun theGoal_isReachedOrNot_andAbsentWithoutOne() {
        assertEquals(
            GoalResult(20, 23, reached = true),
            review(stats(booksFinished = 23), goal = ReadingGoal(2026, 20)).goal,
        )
        assertEquals(
            GoalResult(20, 12, reached = false),
            review(stats(booksFinished = 12), goal = ReadingGoal(2026, 20)).goal,
        )
        assertNull(review(stats(booksFinished = 12)).goal)
    }

    @Test
    fun theMostRewatchedFilm_needsTwoViewings_tiesByTitle() {
        assertNull(review(films = listOf(film(at(1, 1), "Arrival"), film(at(1, 2), "Dune"))).rewatchedFilm)

        val r =
            review(
                films =
                    listOf(
                        film(at(1, 1), "Zodiac"),
                        film(at(1, 2), "Zodiac"),
                        film(at(1, 3), "Arrival"),
                        film(at(1, 4), "Arrival"),
                        film(at(1, 5), "Dune"),
                    ),
            )
        assertEquals(RewatchedFilm("Arrival", 2), r.rewatchedFilm)
    }

    @Test
    fun theBiggestBinge_isByLocalDay_acrossMidnight() {
        // 23:30 and 00:30 local are two days, though in UTC both fall on 15 March.
        val r =
            review(
                episodes =
                    listOf(
                        episode(at(3, 14, 21), "Severance"),
                        episode(at(3, 14, 22), "Severance"),
                        episode(at(3, 14, 23, 30), "Andor"),
                        episode(at(3, 15, 0, 30), "Severance"),
                        episode(at(3, 15, 1, 30), "Severance"),
                    ),
            )

        val binge = r.bingeDay!!
        assertEquals(Triple(2026, 3, 14), Triple(binge.year, binge.month, binge.day))
        assertEquals(3, binge.episodes)
        assertEquals("Severance", binge.topShow)
        assertEquals(DayOfWeek.SATURDAY.isoDayNumber, binge.dayOfWeekIso)
    }

    @Test
    fun oneEpisodeADay_isNotABinge() {
        assertNull(review(episodes = listOf(episode(at(1, 1), "Severance"), episode(at(1, 2), "Severance"))).bingeDay)
    }

    @Test
    fun theBusiestMonth_isByCombinedReadingAndWatchingTime() {
        val r =
            review(
                periodStats = stats(timeRead = 3_600),
                sessions = listOf(session(at(1, 10), 3_600)),
                // February: two films, 4 hours in all -- more than January's hour of reading.
                films = listOf(film(at(2, 1), "Arrival", 120), film(at(2, 2), "Dune", 120)),
            )

        assertEquals(2, r.rhythm!!.busiestMonth)
    }

    @Test
    fun theClock_isReadingsWhenThereIsReading_elseWatching() {
        val reading =
            review(
                periodStats = stats(timeRead = 3_600),
                sessions = listOf(session(at(1, 10, 7), 3_600)),
                films = listOf(film(at(2, 1, 21), "Arrival")),
            ).rhythm!!
        assertEquals(true, reading.reading)
        assertEquals(3_600L, reading.hours[7])
        assertEquals(DayPart.MORNING, reading.busiestPart)

        val watching = review(films = listOf(film(at(2, 1, 21), "Arrival"))).rhythm!!
        assertEquals(false, watching.reading)
        assertEquals(1L, watching.hours[21])
        assertEquals(DayPart.NIGHT, watching.busiestPart)
    }

    /** A book stored with 0 pages is not "Your longest book: 0 pages" (#221 review). */
    @Test
    fun aBookWithZeroPages_isNotTheLongest() {
        assertNull(review(books = listOf(book("Blank", 0, at(1, 1)))).longestBook)
    }

    /** Quiet means nothing logged at all: untimed reading sessions are not a quiet year (#221 review). */
    @Test
    fun untimedReading_isNotAQuietYear() {
        assertEquals(true, review().quiet)
        assertEquals(false, review(periodStats = stats(sessions = 4)).quiet)
        assertEquals(false, review(films = listOf(film(at(1, 1), "Arrival", null))).quiet)
    }

    /**
     * Sessions logged without a time are counted, not dropped: the totals say how many sat outside
     * the reading time, as they do for viewings without a runtime (#228 review).
     */
    @Test
    fun untimedSessions_areCounted_notAddedAsTime() {
        val r = review(periodStats = stats(timeRead = 600, sessions = 5, timedSessions = 2))

        assertEquals(3, r.totals.untimedSessions)
        assertEquals(
            0,
            review(periodStats = stats(timeRead = 600, sessions = 2, timedSessions = 2)).totals.untimedSessions,
        )
    }

    /** The goal counts first finishes; a re-read still counts as a book finished (#230). */
    @Test
    fun theGoal_countsFirstFinishes_notRereads() {
        val r = review(periodStats = stats(booksFinished = 3, firstFinishes = 2), goal = ReadingGoal(2026, 2))

        assertEquals(GoalResult(goal = 2, finished = 2, reached = true), r.goal)
        assertEquals(3, r.totals.booksFinished)
    }
}
