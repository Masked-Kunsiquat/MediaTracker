package com.hub.media.features.stats.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.features.settings.data.WeekStartDay
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Aggregate reading statistics over `reading_sessions`, wrapping [com.hub.media.core.database.dao.StatsDao]
 * with the domain semantics from the ROADMAP Task 5 note:
 *
 * 1. **Sum only known durations.** A `null` [com.hub.media.core.database.entities.ReadingSessionEntity.durationSeconds]
 *    (schema v2 — "duration unknown") contributes nothing to time-read totals, but a session with
 *    an unknown duration still happened and is still counted by [observeSessionCountInRange] and
 *    still contributes its [com.hub.media.core.database.entities.ReadingSessionEntity.deltaPages]
 *    (if known) to [observePagesReadInRange]. This is the entire point of schema v2: an unknown
 *    duration must not silently become a zero that corrupts a time-read total.
 * 2. **No gap reconciliation.** Sessions are independent recorded facts (start/end position
 *    pairs), not a continuous log. [observePagesReadInRange] sums each session's own
 *    `deltaPages` — it never infers pages from the gap between one session's end position and the
 *    next session's start position, even though the manual-entry UI's start-position autofill
 *    (Task 4 Phase E) makes such gaps less common in practice.
 *
 * All query methods delegate to [com.hub.media.core.database.dao.StatsDao] and inherit its
 * `[from, to)` half-open range semantics and null/`0` SUM handling verbatim — see that interface's
 * KDoc for the exact rules. This class adds no further coalescing of `null` to `0`: a caller
 * (`StatsViewModel`) that wants a displayable `0` for "no known data" makes that choice explicitly,
 * keeping "unknown" and "zero" distinguishable as far up the stack as useful.
 *
 * ### Books-finished stat (ROADMAP Task 6 Phase C): lifetime total AND period-scoped, deliberately
 * The schema has no "finished at" timestamp until `MIGRATION_2_3` adds
 * [com.hub.media.core.database.entities.BookDetailsEntity.finishedAt] in the same phase this stat
 * ships in — so unlike the session-based stats above, "books finished this week/month" is not
 * derivable from data that predates this phase. Two different, individually-honest queries cover
 * this rather than one query stretched to answer both:
 * - [observeBooksFinishedTotal] needs no timestamp at all — it counts the current
 *   [com.hub.media.core.database.entities.BookDetailsEntity.status], which is exact and meaningful
 *   from the moment schema v3 exists, independent of whether [finishedAt] happens to be known.
 * - [observeBooksFinishedInRange] uses [finishedAt] and is therefore only ever honest about
 *   finishes recorded *after* this phase ships: every pre-v3 row's [finishedAt] is `NULL` by
 *   construction (`MIGRATION_2_3` never derives [com.hub.media.core.database.entities.ReadingStatus.FINISHED]
 *   for a pre-existing row — see that migration's KDoc), so a period query run today can only ever
 *   undercount relative to some hypothetical "true" historical total; it never overcounts or
 *   fabricates a period-scoped number from data that can't support one. `StatsViewModel` wires this
 *   into the same `week`/`month` [StatsUiState.Period] shape as the session-based stats, but the
 *   caveat above is inherent to the data, not something the UI layer can paper over.
 *
 * @param db The shared [AppDatabase], matching [com.hub.media.features.books.data.BookRepository]/
 *   [com.hub.media.features.books.data.ReadingSessionRepository]'s constructor shape.
 */
public class StatsRepository(private val db: AppDatabase) {

    /**
     * Total known reading time (seconds) for sessions whose `timestampStart` falls in
     * `[from, to)`. `null` means no session in range has a known duration (including an empty
     * range) — never coerced to `0` (see class KDoc, point 1).
     */
    public fun observeTimeReadInRange(from: Instant, to: Instant): Flow<Long?> =
        db.statsDao().observeTotalKnownDurationInRange(from, to)

    /**
     * Count of ALL sessions (null-duration sessions included) whose `timestampStart` falls in
     * `[from, to)`. Always a real, non-null count — `0` for an empty range is genuine, not a
     * sentinel.
     */
    public fun observeSessionCountInRange(from: Instant, to: Instant): Flow<Int> =
        db.statsDao().observeSessionCountInRange(from, to)

    /**
     * Total pages read (sum of per-session `deltaPages`) for sessions whose `timestampStart`
     * falls in `[from, to)`. `null` means no session in range has a known `deltaPages` — never
     * coerced to `0` (see class KDoc, point 1). Never inferred from position continuity between
     * sessions (see class KDoc, point 2).
     */
    public fun observePagesReadInRange(from: Instant, to: Instant): Flow<Int?> =
        db.statsDao().observePagesReadInRange(from, to)

    /**
     * Lifetime count of books currently [ReadingStatus.FINISHED] — see class KDoc ("Books-finished
     * stat") for why this coexists with [observeBooksFinishedInRange] instead of one query
     * answering both.
     */
    public fun observeBooksFinishedTotal(): Flow<Int> =
        db.statsDao().observeBooksFinishedTotal(ReadingStatus.FINISHED)

    /**
     * Count of books whose [com.hub.media.core.database.entities.BookDetailsEntity.finishedAt]
     * falls in `[from, to)` and whose status is [ReadingStatus.FINISHED] (a book can only have a
     * non-null `finishedAt` while finished, per [com.hub.media.core.database.entities.BookDetailsEntity.finishedAt]'s
     * KDoc, so the status filter is technically redundant with a non-null-in-range `finishedAt` —
     * it is kept explicit here anyway so this query's intent reads the same as
     * [observeBooksFinishedTotal]'s, rather than relying on that invariant silently). See class
     * KDoc for why this necessarily undercounts relative to any pre-v3 history.
     */
    public fun observeBooksFinishedInRange(from: Instant, to: Instant): Flow<Int> =
        db.statsDao().observeBooksFinishedInRange(ReadingStatus.FINISHED, from, to)

    /**
     * Current reading streak: the number of consecutive calendar days — counting backward from
     * today — with at least one reading session, bucketed into calendar days via [timeZone]
     * (never UTC/SQLite-default; see [com.hub.media.core.database.dao.StatsDao]'s KDoc on why
     * day-bucketing happens here in Kotlin, not in SQL).
     *
     * ### Exact rule
     * Let `today` be [clock]'s current instant converted to a [LocalDate] in [timeZone].
     * - If `today` has at least one session, the streak counts backward from `today` (inclusive)
     *   through the longest unbroken run of consecutive prior days that each have a session.
     * - If `today` has **no** session yet, the streak instead counts backward from **yesterday**
     *   (inclusive) — i.e. a streak that ran through yesterday is NOT broken merely because
     *   today hasn't had a session logged yet (the user may simply not have read yet today). If
     *   yesterday also has no session, the streak is `0`.
     * - A single gap day (no session) anywhere in the backward walk stops the count immediately —
     *   the streak is the length of the unbroken run only, not a total lifetime session-day count.
     * - No sessions ever recorded (or none reachable inside the query window) yields `0`.
     *
     * ### Determinism / injected time
     * [timeZone] and [clock] are both parameters (not read from ambient global state) so tests
     * can pin an exact "today" and timezone. Both default to real wall-clock values
     * ([TimeZone.currentSystemDefault], [Clock.System]) for production use.
     *
     * ### Bounds are fixed at flow-creation time, not re-evaluated live
     * `today` (and therefore which calendar day anchors the backward walk) is computed once, when
     * this method is called, not recomputed as real time crosses midnight while the returned
     * [Flow] is being collected. A long-lived collector whose subscription spans midnight will see
     * the streak computed against the "today" that was current when [observeReadingStreak] was
     * invoked until something re-subscribes (e.g. `StatsViewModel` recreated, or the app
     * restarted) — the same accepted staleness documented on `StatsViewModel`'s period bounds.
     * Re-invoke this method (e.g. re-create the owning ViewModel) to pick up a new "today".
     *
     * ### Not affected by the week-start-day preference (ROADMAP Task 7 Phase B finding)
     * This method takes no [WeekStartDay] parameter, unlike [thisWeekBounds]. The streak walks
     * backward one calendar **day** at a time from `today` via [computeStreak] — it has no notion
     * of "week" at all, so there is no week boundary for a start-day preference to shift. Confirmed
     * by inspection of [computeStreak]: it only ever compares consecutive [LocalDate]s via
     * [LocalDate.minus]/day-membership in a `HashSet`, never a week/`DayOfWeek` computation.
     *
     * ### Query window
     * Internally queries every session from the Unix epoch (1970-01-01, `EPOCH`) through the
     * start of the day after `today` (exclusive) — the app's data cannot predate the epoch, so
     * this is an effectively-unbounded lower bound without [Instant]'s distant-past sentinel's
     * edge cases, and the upper bound ensures every one of today's sessions (which necessarily
     * start before "tomorrow" begins) is included.
     */
    public fun observeReadingStreak(
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        clock: Clock = Clock.System,
    ): Flow<Int> {
        val today = clock.now().toLocalDateTime(timeZone).date
        val exclusiveEnd = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone)
        return db.statsDao().observeSessionStartTimestampsInRange(EPOCH, exclusiveEnd)
            .map { timestamps -> computeStreak(timestamps, today, timeZone) }
    }

    public companion object {

        /** Unix epoch — see [observeReadingStreak]'s KDoc for why this is used as its lower bound. */
        private val EPOCH: Instant = Instant.fromEpochMilliseconds(0)

        /**
         * `[from, to)` [Instant] bounds for the calendar week containing `today` (per [clock]/
         * [timeZone]), with week start = [weekStartDay] (ROADMAP Task 7 Phase B — defaults to
         * [WeekStartDay.MONDAY], ISO-8601 convention, matching this method's pre-Phase-B hardcoded
         * behavior exactly so a caller that never passes [weekStartDay] sees no change). Used by
         * `StatsViewModel` to drive [StatsRepository]'s range queries for "this week".
         *
         * ### Day math
         * `daysSinceStart` is `(today's ISO day number - weekStartDay's ISO day number) mod 7`,
         * walking `today` backward that many days to reach the containing week's start — this
         * single formula handles both start-day conventions (and every relative position `today`
         * can have to either) uniformly, with no special-casing: e.g. a Sunday under
         * [WeekStartDay.MONDAY] start walks back 6 days to the *preceding* Monday, while that same
         * Sunday under [WeekStartDay.SUNDAY] start walks back 0 days (it *is* its own week's start).
         */
        public fun thisWeekBounds(
            timeZone: TimeZone = TimeZone.currentSystemDefault(),
            clock: Clock = Clock.System,
            weekStartDay: WeekStartDay = WeekStartDay.MONDAY,
        ): Pair<Instant, Instant> {
            val today = clock.now().toLocalDateTime(timeZone).date
            val daysSinceStart = (today.dayOfWeek.isoDayNumber - weekStartDay.isoDayNumber + 7) % 7
            val weekStart = today.minus(daysSinceStart, DateTimeUnit.DAY)
            val weekEnd = weekStart.plus(7, DateTimeUnit.DAY)
            return weekStart.atStartOfDayIn(timeZone) to weekEnd.atStartOfDayIn(timeZone)
        }

        /**
         * `[from, to)` [Instant] bounds for the calendar month containing `today` (per [clock]/
         * [timeZone]) — the 1st of the month through the 1st of the following month, exclusive.
         * Used by `StatsViewModel` to drive [StatsRepository]'s range queries for "this month".
         */
        public fun thisMonthBounds(
            timeZone: TimeZone = TimeZone.currentSystemDefault(),
            clock: Clock = Clock.System,
        ): Pair<Instant, Instant> {
            val today = clock.now().toLocalDateTime(timeZone).date
            val monthStart = LocalDate(today.year, today.month, 1)
            val monthEnd = monthStart.plus(1, DateTimeUnit.MONTH)
            return monthStart.atStartOfDayIn(timeZone) to monthEnd.atStartOfDayIn(timeZone)
        }

        /**
         * Pure day-math backing [observeReadingStreak] — see its KDoc for the exact rule. Kept as
         * a standalone function (rather than inlined into the `Flow.map`) so it is trivially unit
         * testable independent of any [Flow]/DB plumbing.
         */
        internal fun computeStreak(timestamps: List<Instant>, today: LocalDate, timeZone: TimeZone): Int {
            val daysWithSessions = timestamps.mapTo(HashSet()) { it.toLocalDateTime(timeZone).date }
            var cursor = if (today in daysWithSessions) today else today.minus(1, DateTimeUnit.DAY)
            var streak = 0
            while (cursor in daysWithSessions) {
                streak++
                cursor = cursor.minus(1, DateTimeUnit.DAY)
            }
            return streak
        }
    }
}
