package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.hub.media.core.database.entities.ReadingStatus
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Reactive aggregate queries for the stats screen (ROADMAP Task 5, reworked in #91).
 *
 * Since #91 a stats period is ONE query, [observePeriodStats], instead of four per-field queries
 * that each emitted on their own and so published halfway mixes after a write. The only other
 * query is [observeSessionStartTimestampsInRange], the streak's raw input. Adding or reshaping DAO
 * methods does not alter the schema (schema hashes come from `@Entity` tables), so none of this
 * needed a Room version bump.
 *
 * ### Range semantics: "falls in [from, to)"
 * Ranges bucket sessions by [com.hub.media.core.database.entities.ReadingSessionEntity.timestampStart]
 * only, half-open: a session starting exactly at `to` belongs to the next bucket, one starting at
 * `from` is in. A session that starts inside a window but ends after it counts whole in the window
 * its start falls into -- sessions are independent recorded facts, never split across buckets.
 *
 * ### SUM-over-empty-set nullability (SQLite semantics)
 * `SUM()` is `NULL`, not `0`, when no row matches or every matching value is `NULL`. The DAO keeps
 * that faithfully (time read and pages read are nullable): collapsing "nothing known" into "a known
 * zero" here would destroy information. `COUNT(*)` always returns a real integer, so an empty range
 * legitimately yields `0`.
 */
@Dao
interface StatsDao {
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
     * Everything a stats period shows, in one query (#91). The four numbers used to come from four
     * independent flows, so one write published every halfway mix (a session counted before its
     * pages arrived) before settling. One `SELECT` of four scalar subqueries is one emission per
     * write, with the numbers already consistent; Room re-runs it when either table changes.
     *
     * `SUM` over no rows is `NULL` in SQLite, which is exactly the null-versus-zero rule: no known
     * duration in range means time read is unknown, not zero. [from] / [to] are a half-open range;
     * either may be `null` for unbounded, and with both `null` (All time) a FINISHED book with no
     * `finishedAt` still counts, as the lifetime count always did, while no dated range can match it.
     */
    @Query(
        """
        SELECT
          (SELECT SUM(durationSeconds) FROM reading_sessions
             WHERE durationSeconds IS NOT NULL
               AND (:from IS NULL OR timestampStart >= :from)
               AND (:to IS NULL OR timestampStart < :to)) AS timeReadSeconds,
          (SELECT COUNT(*) FROM reading_sessions
             WHERE (:from IS NULL OR timestampStart >= :from)
               AND (:to IS NULL OR timestampStart < :to)) AS sessionCount,
          (SELECT SUM(deltaPages) FROM reading_sessions
             WHERE deltaPages IS NOT NULL
               AND (:from IS NULL OR timestampStart >= :from)
               AND (:to IS NULL OR timestampStart < :to)) AS pagesRead,
          (SELECT COUNT(*) FROM book_details
             WHERE status = :finished
               AND (:from IS NULL OR finishedAt >= :from)
               AND (:to IS NULL OR finishedAt < :to)) AS booksFinished,
          (SELECT COUNT(*) FROM reading_sessions
             WHERE durationSeconds IS NOT NULL
               AND (:from IS NULL OR timestampStart >= :from)
               AND (:to IS NULL OR timestampStart < :to)) AS timedSessionCount,
          (SELECT MAX(durationSeconds) FROM reading_sessions
             WHERE durationSeconds IS NOT NULL
               AND (:from IS NULL OR timestampStart >= :from)
               AND (:to IS NULL OR timestampStart < :to)) AS longestSessionSeconds,
          (SELECT SUM(durationSeconds) FROM reading_sessions
             WHERE durationSeconds > 0 AND deltaPages > 0
               AND (:from IS NULL OR timestampStart >= :from)
               AND (:to IS NULL OR timestampStart < :to)) AS speedSeconds,
          (SELECT SUM(deltaPages) FROM reading_sessions
             WHERE durationSeconds > 0 AND deltaPages > 0
               AND (:from IS NULL OR timestampStart >= :from)
               AND (:to IS NULL OR timestampStart < :to)) AS speedPages
        """,
    )
    fun observePeriodStats(
        from: Instant?,
        to: Instant?,
        finished: ReadingStatus,
    ): Flow<PeriodStatsRow>

    /**
     * Finish dates of FINISHED books within `[from, to)`, for the books-per-month chart (#219). Raw
     * instants rather than a SQL `strftime` grouping: SQLite's `'localtime'` is the device's zone, not
     * the injected one, so the month a late-evening finish belongs to is decided in Kotlin instead --
     * the same reason the streak reads raw session starts.
     */
    @Query(
        "SELECT finishedAt FROM book_details " +
            "WHERE status = :finished AND finishedAt >= :from AND finishedAt < :to",
    )
    fun observeFinishedAtInRange(
        from: Instant,
        to: Instant,
        finished: ReadingStatus,
    ): Flow<List<Instant>>
}
