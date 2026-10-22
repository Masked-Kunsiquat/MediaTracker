package com.hub.media.features.stats.data

import com.hub.media.core.database.dao.FinishedBookRow
import com.hub.media.core.database.dao.PeriodStatsRow
import com.hub.media.core.database.dao.TimedSessionRow
import com.hub.media.core.database.dao.ViewingRow
import com.hub.media.features.settings.data.ReadingGoal
import com.hub.media.features.settings.data.WeekStartDay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime

/**
 * The year's totals (#221).
 *
 * @property readSeconds Known reading time.
 * @property watchSeconds Known watching time: viewings whose film or episode has a runtime.
 *   [unknownRuntime] counts the viewings left out -- never added as 0.
 * @property untimedSessions Reading sessions logged without a duration: left out of [readSeconds]
 *   and counted here instead, the same way (#228 review).
 */
public data class YearTotals(
    val booksFinished: Int,
    val films: Int,
    val episodes: Int,
    val readSeconds: Long,
    val watchSeconds: Long,
    val unknownRuntime: Int,
    val untimedSessions: Int = 0,
)

/** The finished book with the most pages. */
public data class LongestBook(
    val title: String,
    val pages: Int,
)

/** The longest timed reading session, and its book. */
public data class LongestSession(
    val title: String,
    val seconds: Long,
)

/** That year's reading goal against the books finished. */
public data class GoalResult(
    val goal: Int,
    val finished: Int,
    val reached: Boolean,
)

/** The film watched most often, at least twice. */
public data class RewatchedFilm(
    val title: String,
    val viewings: Int,
)

/**
 * The local day with the most episodes, at least two, and the show with the most episodes that day.
 * The date travels as numbers ([dayOfWeekIso] is 1 = Monday): `:app` sees no kotlinx-datetime types.
 */
public data class BingeDay(
    val year: Int,
    val month: Int,
    val day: Int,
    val dayOfWeekIso: Int,
    val episodes: Int,
    val topShow: String,
)

/**
 * When in the year and the day (#221).
 *
 * @property busiestMonth 1..12, by reading time plus known watching time per local month.
 * @property hours The clock: reading seconds per hour when the year has reading time ([reading]),
 *   otherwise timed viewings per hour. Reading time and viewing counts do not add up honestly, so one
 *   is shown, never a mix. All zero when nothing has a real time of day.
 * @property busiestPart The busiest part of the day on that clock, or `null` when it is empty.
 */
public data class Rhythm(
    val busiestMonth: Int,
    val hours: List<Long>,
    val busiestPart: DayPart?,
    val reading: Boolean,
)

/**
 * One year across books, films and shows (#221). Each highlight is `null` when the year has nothing
 * for it, and the story skips that page; [totals] is always there, zeros and all.
 *
 * @property quiet Nothing at all was logged in the year: no reading session (timed or not), no
 *   viewing and no finish. A year of untimed reading is not quiet, though its totals show no time.
 * @property favorites The year's top-rated book, film and show (#232); `null` in a quiet year or one
 *   with nothing rated.
 */
public data class YearReview(
    val year: Int,
    val totals: YearTotals,
    val longestBook: LongestBook?,
    val topAuthor: AuthorTime?,
    val longestSession: LongestSession?,
    val goal: GoalResult?,
    val rewatchedFilm: RewatchedFilm?,
    val bingeDay: BingeDay?,
    val rhythm: Rhythm?,
    val quiet: Boolean = false,
    val favorites: Favorites? = null,
)

/**
 * Builds [year]'s review from that year's reads (#221): the period stats, the timed reading
 * sessions, the film and episode viewings, the books finished and that year's [goal]. Days, months
 * and hours are local to [timeZone]; picked dates ([isPickedDate]) count by day and month but stay
 * off the clock.
 */
public fun yearReview(
    year: Int,
    timeZone: TimeZone,
    weekStart: WeekStartDay,
    periodStats: PeriodStatsRow,
    sessions: List<TimedSessionRow>,
    films: List<ViewingRow>,
    episodes: List<ViewingRow>,
    finishedBooks: List<FinishedBookRow>,
    goal: ReadingGoal?,
    favorites: Favorites? = null,
): YearReview {
    val viewings = films + episodes
    val readSeconds = sessions.sumOf { it.durationSeconds }
    val totals =
        YearTotals(
            booksFinished = periodStats.booksFinished,
            films = films.size,
            episodes = episodes.size,
            readSeconds = readSeconds,
            watchSeconds = viewings.sumOf { (it.runtimeMinutes ?: 0) * 60L },
            unknownRuntime = viewings.count { it.runtimeMinutes == null },
            untimedSessions = periodStats.sessionCount - periodStats.timedSessionCount,
        )
    val reading = readingBreakdown(sessions, timeZone, weekStart)
    val quiet = periodStats.sessionCount == 0 && viewings.isEmpty() && periodStats.booksFinished == 0

    return YearReview(
        year = year,
        totals = totals,
        longestBook =
            finishedBooks
                // A page count of 0 is a missing count, not "0 pages" (#221 review).
                .filter { (it.totalPages ?: 0) > 0 }
                .sortedWith(compareBy({ -it.totalPages!! }, { it.finishedAt }))
                .firstOrNull()
                ?.let { LongestBook(it.title, it.totalPages!!) },
        topAuthor = reading.topAuthors.firstOrNull(),
        longestSession =
            sessions
                .sortedWith(compareBy({ -it.durationSeconds }, { it.timestampStart }))
                .firstOrNull()
                ?.let { LongestSession(it.title, it.durationSeconds) },
        goal = goal?.let { GoalResult(it.books, periodStats.firstFinishes, periodStats.firstFinishes >= it.books) },
        rewatchedFilm =
            films
                .groupBy { it.mediaId }
                .values
                .map { RewatchedFilm(it.first().title, it.size) }
                .filter { it.viewings >= 2 }
                .sortedWith(compareBy({ -it.viewings }, { it.title }))
                .firstOrNull(),
        bingeDay = bingeDay(episodes, timeZone),
        rhythm = rhythm(sessions, viewings, reading, readSeconds, timeZone),
        quiet = quiet,
        favorites = favorites.takeUnless { quiet },
    )
}

private fun bingeDay(
    episodes: List<ViewingRow>,
    timeZone: TimeZone,
): BingeDay? {
    val byDay = episodes.groupBy { it.watchedAt.toLocalDateTime(timeZone).date }
    val (date, onDay) =
        byDay.entries
            .sortedWith(compareBy({ -it.value.size }, { it.key }))
            .firstOrNull()
            ?.takeIf { it.value.size >= 2 }
            ?.toPair() ?: return null
    val topShow =
        onDay
            .groupBy { it.title }
            .entries
            .sortedWith(compareBy({ -it.value.size }, { it.key }))
            .first()
            .key
    return BingeDay(date.year, date.month.ordinal + 1, date.day, date.dayOfWeek.isoDayNumber, onDay.size, topShow)
}

private fun rhythm(
    sessions: List<TimedSessionRow>,
    viewings: List<ViewingRow>,
    reading: ReadingBreakdown,
    readSeconds: Long,
    timeZone: TimeZone,
): Rhythm? {
    val monthSeconds = LongArray(12)
    for (s in sessions) {
        monthSeconds[
            s.timestampStart
                .toLocalDateTime(timeZone)
                .month.ordinal,
        ] += s.durationSeconds
    }
    for (v in viewings) {
        v.runtimeMinutes?.let {
            monthSeconds[
                v.watchedAt
                    .toLocalDateTime(timeZone)
                    .month.ordinal,
            ] += it * 60L
        }
    }
    if (monthSeconds.all { it == 0L }) return null
    val busiestMonth = monthSeconds.indices.maxBy { monthSeconds[it] } + 1

    val hours: List<Long>
    val parts: List<Long>
    if (readSeconds > 0) {
        hours = reading.hours
        parts = reading.dayParts
    } else {
        val counts = LongArray(24)
        for (v in viewings) {
            if (!isPickedDate(v.watchedAt, timeZone)) counts[v.watchedAt.toLocalDateTime(timeZone).hour]++
        }
        hours = counts.toList()
        parts = DayPart.entries.map { part -> hours.indices.filter { dayPartOf(it) == part }.sumOf { hours[it] } }
    }
    val busiestPart = if (parts.all { it == 0L }) null else DayPart.entries[parts.indices.maxBy { parts[it] }]
    return Rhythm(busiestMonth, hours, busiestPart, reading = readSeconds > 0)
}
