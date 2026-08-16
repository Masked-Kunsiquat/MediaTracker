package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.hub.media.core.database.entities.ReadingStatus
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Reactive aggregate queries for ROADMAP Task 5 (stats): originally `reading_sessions`-only, joined
 * by ROADMAP Task 6 Phase C with two `book_details`-based "books finished" queries
 * ([observeBooksFinishedTotal]/[observeBooksFinishedInRange]). Adding this interface and
 * registering it on `AppDatabase` does not alter the schema (schema hashes are derived from
 * `@Entity`-annotated tables, not DAO surface), so no Room version bump accompanied its original
 * addition — see `AppDatabase`'s KDoc. (The Phase C `book_details` columns those two new queries
 * read *did* require a version bump, to schema v3 — that bump came from the columns themselves,
 * not from this DAO gaining new methods.)
 *
 * ### Range semantics: "falls in [from, to)"
 * Every range query below buckets by [com.hub.media.core.database.entities.ReadingSessionEntity.timestampStart]
 * only, using a half-open interval: `timestampStart >= from AND timestampStart < to`. A session
 * starting exactly at `to` is excluded (it belongs to the *next* bucket); one starting exactly at
 * `from` is included. Bucketing is by a session's *start*, never its end -- a session that starts
 * inside a window but finishes after it (e.g. started 11:58pm on the last day of a month) is
 * counted whole in the window its start falls into. This deliberately never splits one session's
 * contribution across two buckets, consistent with the ROADMAP Task 5 note that sessions are
 * independent recorded facts, not a continuous log to be reconciled.
 *
 * ### SUM-over-empty-set nullability (SQLite semantics)
 * SQLite's `SUM()` returns `NULL` — not `0` — when zero rows match the `WHERE` clause, or when
 * every matching row's summed column is itself `NULL`. [observeTotalKnownDurationInRange] and
 * [observePagesReadInRange] surface that faithfully as `Flow<Long?>`/`Flow<Int?>` rather than
 * coalescing to `0` here: collapsing "nothing known" into "a known zero" at the DAO layer would
 * destroy information a caller might legitimately want (e.g. distinguishing "no sessions in this
 * range at all" from "sessions exist but every one has a null duration/deltaPages", both of which
 * produce `NULL` here). [com.hub.media.features.stats.data.StatsRepository] is the layer that
 * documents any further 0-coalescing for UI display; the DAO stays faithful to the raw SQL result.
 * [observeSessionCountInRange]'s `COUNT(*)` is different: it always returns a real integer, so an
 * empty range legitimately yields `0`, not `null`.
 */
@Dao
interface StatsDao {
    /**
     * Sum of `durationSeconds` for sessions whose `timestampStart` falls in `[from, to)` and
     * whose duration is *known* (`durationSeconds IS NOT NULL`) -- null-duration sessions
     * contribute nothing to this total (schema v2 / ROADMAP Task 5 note: "sum only known
     * durations"). Returns `null` when no matching session has a known duration in range
     * (including an empty range); never coerced to `0` here (see class KDoc).
     */
    @Query(
        "SELECT SUM(durationSeconds) FROM reading_sessions " +
            "WHERE durationSeconds IS NOT NULL AND timestampStart >= :from AND timestampStart < :to",
    )
    fun observeTotalKnownDurationInRange(
        from: Instant,
        to: Instant,
    ): Flow<Long?>

    /**
     * Count of ALL sessions whose `timestampStart` falls in `[from, to)`, null-duration sessions
     * included -- a session with unknown duration still happened and still counts as a session
     * (per the ROADMAP Task 5 note: a null duration "does not otherwise affect session counts or
     * page progress"). `COUNT(*)` always yields a real non-null integer, so an empty range
     * legitimately returns `0`.
     */
    @Query("SELECT COUNT(*) FROM reading_sessions WHERE timestampStart >= :from AND timestampStart < :to")
    fun observeSessionCountInRange(
        from: Instant,
        to: Instant,
    ): Flow<Int>

    /**
     * Sum of `deltaPages` for sessions whose `timestampStart` falls in `[from, to)` and whose
     * `deltaPages` is known (`deltaPages IS NOT NULL`). Mirrors
     * [observeTotalKnownDurationInRange]'s null/`0` handling for the same SQLite `SUM()` reason
     * (see class KDoc): `null` means "no known page progress in range", not "zero pages read".
     * Per the ROADMAP Task 5 note, this sums per-session deltas only -- it never infers pages from
     * position continuity between sessions ("no gap reconciliation").
     */
    @Query(
        "SELECT SUM(deltaPages) FROM reading_sessions " +
            "WHERE deltaPages IS NOT NULL AND timestampStart >= :from AND timestampStart < :to",
    )
    fun observePagesReadInRange(
        from: Instant,
        to: Instant,
    ): Flow<Int?>

    /**
     * Distinct `timestampStart` values for every session whose `timestampStart` falls in `[from, to)`.
     * Deliberately NOT bucketed into calendar days in SQL: SQLite has no timezone-aware date
     * function over an epoch-millis column, so grouping by "day" here would silently mean
     * whatever timezone SQLite's build defaults to (effectively UTC), not the user's actual
     * timezone. Day-bucketing for streak computation happens in Kotlin via kotlinx-datetime with
     * an explicit, injected `TimeZone` instead — see
     * [com.hub.media.features.stats.data.StatsRepository.observeReadingStreak].
     */
    @Query(
        "SELECT DISTINCT timestampStart FROM reading_sessions WHERE timestampStart >= :from AND timestampStart < :to",
    )
    fun observeSessionStartTimestampsInRange(
        from: Instant,
        to: Instant,
    ): Flow<List<Instant>>

    /**
     * Lifetime count of `book_details` rows currently at [status] (ROADMAP Task 6 Phase C —
     * "books finished" stat). Reads the current [status] column directly rather than
     * [com.hub.media.core.database.entities.BookDetailsEntity.finishedAt], so this total is exact
     * from the moment this column exists, independent of whether any [finishedAt] value is known
     * for a given row (e.g. a book whose `MIGRATION_2_3` derivation never set one — see that
     * migration's KDoc). Not scoped to a time range — see
     * [observeBooksFinishedInRange] for the period-scoped count and
     * [com.hub.media.features.stats.data.StatsRepository]'s KDoc for why the two coexist.
     */
    @Query("SELECT COUNT(*) FROM book_details WHERE status = :status")
    fun observeBooksFinishedTotal(status: ReadingStatus): Flow<Int>

    /**
     * Count of `book_details` rows at [status] whose
     * [com.hub.media.core.database.entities.BookDetailsEntity.finishedAt] falls in `[from, to)`
     * (same half-open convention as every other range query on this interface). Rows with a `NULL`
     * `finishedAt` — every row that predates schema v3, plus any row `MIGRATION_2_3` derived to
     * [ReadingStatus.READING] rather than [ReadingStatus.FINISHED] — never match any range,
     * including an unbounded one, since `finishedAt >= :from` is never true against `NULL` in SQL.
     * That is intentional, not a bug to work around: a period-scoped "books finished this
     * week/month" total can only ever be honest about finishes recorded *after* this column started
     * being populated (see [com.hub.media.features.stats.data.StatsRepository]'s KDoc).
     */
    @Query(
        "SELECT COUNT(*) FROM book_details WHERE status = :status " +
            "AND finishedAt >= :from AND finishedAt < :to",
    )
    fun observeBooksFinishedInRange(
        status: ReadingStatus,
        from: Instant,
        to: Instant,
    ): Flow<Int>
}
