package com.hub.media.features.stats.data

import com.hub.media.core.database.dao.TimedSessionRow
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.splitAuthors
import com.hub.media.features.settings.data.WeekStartDay
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The four parts of the day "when you read" is split into (#219); see [dayPartOf] for the hours. */
public enum class DayPart { MORNING, AFTERNOON, EVENING, NIGHT }

/** One of the top authors: the spelling shown, and the time read across all its spellings. */
public data class AuthorTime(
    val name: String,
    val seconds: Long,
)

/** Time read in one [format]. */
public data class FormatTime(
    val format: BookFormat,
    val seconds: Long,
)

/**
 * When you read, who and in which format (#219), all from the same timed sessions, so every total
 * here is the same number: `dayParts.sum() == weekdaySeconds.sum() == formats' sum == totalSeconds`.
 *
 * @property dayParts Seconds per [DayPart], in its declaration order.
 * @property weekdays The seven days, starting on the week-start setting; [weekdaySeconds] follows
 *   the same order. Empty only in the default value used while the screen is loading.
 * @property topAuthors At most [TOP_AUTHORS], most time first, ties by name. A co-author gets the
 *   whole session's time.
 * @property formats Every format with time, most first, ties in [BookFormat]'s order.
 */
public data class ReadingBreakdown(
    val dayParts: List<Long> = List(DayPart.entries.size) { 0L },
    val weekdays: List<DayOfWeek> = emptyList(),
    val weekdaySeconds: List<Long> = List(7) { 0L },
    val topAuthors: List<AuthorTime> = emptyList(),
    val formats: List<FormatTime> = emptyList(),
    val totalSeconds: Long = 0,
)

/** How many authors the top-authors card lists. */
public const val TOP_AUTHORS: Int = 5

/**
 * The part of the day a local [hour] (0..23) falls in: Morning 05:00–11:59, Afternoon 12:00–16:59,
 * Evening 17:00–20:59, Night 21:00–04:59. Night wraps past midnight.
 */
public fun dayPartOf(hour: Int): DayPart =
    when (hour) {
        in 5..11 -> DayPart.MORNING
        in 12..16 -> DayPart.AFTERNOON
        in 17..20 -> DayPart.EVENING
        else -> DayPart.NIGHT
    }

/**
 * Builds the three breakdowns from [rows] (#219). A session counts toward the local hour and day its
 * start falls on in [timeZone], even if it runs past midnight or into the next part of the day.
 *
 * Authors are grouped by [authorKey], so "J.R.R. Tolkien" and "J. R. R. Tolkien" are one person,
 * shown under whichever spelling has the most time (ties: alphabetically first). A book with no
 * author on record is left out of the authors, and a name with no letters or digits is not an
 * author at all; both still count in the parts of the day, weekdays and formats.
 */
public fun readingBreakdown(
    rows: List<TimedSessionRow>,
    timeZone: TimeZone,
    weekStart: WeekStartDay,
): ReadingBreakdown {
    val weekdays = List(7) { DayOfWeek((weekStart.isoDayNumber - 1 + it) % 7 + 1) }
    val dayParts = LongArray(DayPart.entries.size)
    val weekdaySeconds = LongArray(7)
    val formatSeconds = mutableMapOf<BookFormat, Long>()
    // key -> spelling -> seconds
    val authorSeconds = mutableMapOf<String, MutableMap<String, Long>>()

    for (row in rows) {
        val local = row.timestampStart.toLocalDateTime(timeZone)
        dayParts[dayPartOf(local.hour).ordinal] += row.durationSeconds
        weekdaySeconds[weekdays.indexOf(local.dayOfWeek)] += row.durationSeconds
        formatSeconds[row.format] = (formatSeconds[row.format] ?: 0L) + row.durationSeconds
        for (name in splitAuthors(row.authors)) {
            val key = authorKey(name)
            if (key.isEmpty()) continue
            val spellings = authorSeconds.getOrPut(key) { mutableMapOf() }
            spellings[name] = (spellings[name] ?: 0L) + row.durationSeconds
        }
    }

    val topAuthors =
        authorSeconds.values
            .map { spellings ->
                val shown =
                    spellings.entries
                        .sortedWith(compareBy({ -it.value }, { it.key }))
                        .first()
                        .key
                AuthorTime(shown, spellings.values.sum())
            }.sortedWith(compareBy({ -it.seconds }, { it.name }))
            .take(TOP_AUTHORS)
    val formats =
        formatSeconds
            .map { (format, seconds) -> FormatTime(format, seconds) }
            .sortedWith(compareBy({ -it.seconds }, { it.format.ordinal }))

    return ReadingBreakdown(
        dayParts = dayParts.toList(),
        weekdays = weekdays,
        weekdaySeconds = weekdaySeconds.toList(),
        topAuthors = topAuthors,
        formats = formats,
        totalSeconds = rows.sumOf { it.durationSeconds },
    )
}
