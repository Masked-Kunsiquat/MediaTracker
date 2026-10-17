package com.hub.media.features.stats.data

import com.hub.media.core.database.dao.ViewingRow
import com.hub.media.features.settings.data.WeekStartDay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** One of the top shows (or films): its title and the time watched where runtimes are known. */
public data class TitleTime(
    val title: String,
    val seconds: Long,
)

/**
 * What the Films and Shows tabs show for a period (#217), all from one read of its viewings, so the
 * numbers on a tab always agree with each other.
 *
 * @property viewings Every viewing, rewatches included; [rewatches] of them were rewatches.
 * @property titles Different films or shows watched.
 * @property knownSeconds Time watched over viewings whose film or episode has a runtime, or `null`
 *   when none does -- unknown, never 0. [unknownRuntime] counts the viewings left out of it.
 * @property hours Viewings per local hour, midnight first, for the clock dial; [dayParts] is the
 *   same grouped by [dayPartOf]. Both leave out picked dates ([isPickedDate]), which [untimed] counts.
 * @property weekdays ISO day numbers from the week start; [weekdayCounts] counts every viewing,
 *   picked dates included, because their day is real.
 * @property months Viewings per local month, January first, when asked for (the Year view).
 * @property topTitles At most [TOP_TITLES] titles by known time, most first, ties by title.
 */
public data class ViewingBreakdown(
    val viewings: Int = 0,
    val rewatches: Int = 0,
    val titles: Int = 0,
    val knownSeconds: Long? = null,
    val unknownRuntime: Int = 0,
    val hours: List<Int> = List(24) { 0 },
    val dayParts: List<Int> = List(DayPart.entries.size) { 0 },
    val untimed: Int = 0,
    val weekdays: List<Int> = emptyList(),
    val weekdayCounts: List<Int> = List(7) { 0 },
    val months: List<Int>? = null,
    val topTitles: List<TitleTime> = emptyList(),
)

/** How many titles the top-shows card lists. */
public const val TOP_TITLES: Int = 5

/**
 * Whether [at] is a picked date rather than a real time (#217): exactly 12:00:00.000 local. "Pick a
 * date" and a date correction both store the picked day at noon (`WatchedDatePickerDialog`), so the
 * day is real and the time is not. A viewing logged with "Now" lands on that exact millisecond about
 * once in 86 million, which is the whole cost of the rule.
 *
 * **Date-only imports must follow it.** A future import whose source records only a date (likely for
 * some streaming services) must store noon local, so it counts by weekday and month but never claims
 * a time of day. Owner's decision, 2026-10-17.
 */
public fun isPickedDate(
    at: Instant,
    timeZone: TimeZone,
): Boolean {
    val local = at.toLocalDateTime(timeZone)
    return local.hour == 12 && local.minute == 0 && local.second == 0 && local.nanosecond == 0
}

/** Builds a tab's numbers from its period's [rows] (#217); see [ViewingBreakdown] for each one. */
public fun viewingBreakdown(
    rows: List<ViewingRow>,
    timeZone: TimeZone,
    weekStart: WeekStartDay,
    withMonths: Boolean,
): ViewingBreakdown {
    val weekdays = List(7) { (weekStart.isoDayNumber - 1 + it) % 7 + 1 }
    val hours = IntArray(24)
    val weekdayCounts = IntArray(7)
    val months = IntArray(12)
    var untimed = 0
    val secondsByTitle = mutableMapOf<String, Pair<String, Long>>()

    for (row in rows) {
        val local = row.watchedAt.toLocalDateTime(timeZone)
        weekdayCounts[weekdays.indexOf(local.dayOfWeek.isoDayNumber)]++
        months[local.month.ordinal]++
        if (isPickedDate(row.watchedAt, timeZone)) untimed++ else hours[local.hour]++
        row.runtimeMinutes?.let { minutes ->
            val (title, seconds) = secondsByTitle[row.mediaId] ?: (row.title to 0L)
            secondsByTitle[row.mediaId] = title to seconds + minutes * 60L
        }
    }

    val dayParts = IntArray(DayPart.entries.size)
    hours.forEachIndexed { hour, count -> dayParts[dayPartOf(hour).ordinal] += count }
    val known = rows.mapNotNull { it.runtimeMinutes }
    return ViewingBreakdown(
        viewings = rows.size,
        rewatches = rows.count { it.isRewatch },
        titles = rows.map { it.mediaId }.distinct().size,
        knownSeconds = if (known.isEmpty()) null else known.sumOf { it * 60L },
        unknownRuntime = rows.count { it.runtimeMinutes == null },
        hours = hours.toList(),
        dayParts = dayParts.toList(),
        untimed = untimed,
        weekdays = weekdays,
        weekdayCounts = weekdayCounts.toList(),
        months = if (withMonths) months.toList() else null,
        topTitles =
            secondsByTitle.values
                .map { (title, seconds) -> TitleTime(title, seconds) }
                .sortedWith(compareBy({ -it.seconds }, { it.title }))
                .take(TOP_TITLES),
    )
}
