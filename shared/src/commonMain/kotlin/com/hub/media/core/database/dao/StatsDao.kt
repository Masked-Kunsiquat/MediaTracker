package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.hub.media.core.database.entities.MediaType
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
          (SELECT COUNT(*) FROM read_throughs
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
               AND (:to IS NULL OR timestampStart < :to)) AS speedPages,
          (SELECT COUNT(*) FROM read_throughs r
             WHERE r.status = :finished
               AND (:from IS NULL OR r.finishedAt >= :from)
               AND (:to IS NULL OR r.finishedAt < :to)
               AND r.number = (SELECT MIN(f.number) FROM read_throughs f
                                WHERE f.mediaId = r.mediaId AND f.status = :finished)) AS firstFinishes
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
        "SELECT finishedAt FROM read_throughs " +
            "WHERE status = :finished AND finishedAt >= :from AND finishedAt < :to",
    )
    fun observeFinishedAtInRange(
        from: Instant,
        to: Instant,
        finished: ReadingStatus,
    ): Flow<List<Instant>>

    /**
     * Every session in `[from, to)` with a known duration above zero, with its book's format and
     * authors (#219): the one read Books stats part 2 builds when you read, top authors and formats
     * from, so the three always agree. Both bounds null is All time. Raw start instants, for the same
     * reason as [observeFinishedAtInRange]: the part of the day and the weekday are decided in Kotlin
     * in the injected zone, never by SQLite.
     */
    @Query(
        """
        SELECT s.timestampStart, s.durationSeconds, b.format, b.authors, s.mediaId, mi.title, mi.coverImageHash
          FROM reading_sessions s
          JOIN book_details b ON b.mediaId = s.mediaId
          JOIN media_items mi ON mi.id = s.mediaId
         WHERE s.durationSeconds > 0
           AND (:from IS NULL OR s.timestampStart >= :from)
           AND (:to IS NULL OR s.timestampStart < :to)
        """,
    )
    fun observeTimedSessions(
        from: Instant?,
        to: Instant?,
    ): Flow<List<TimedSessionRow>>

    /**
     * Reads FINISHED and reads DNF, whatever their dates (#219): the All time did-not-finish rate. Over
     * reads since #230, so a re-read that ends is an outcome of its own.
     */
    @Query(
        "SELECT (SELECT COUNT(*) FROM read_throughs WHERE status = :finished) AS finished, " +
            "(SELECT COUNT(*) FROM read_throughs WHERE status = :dnf) AS dnf",
    )
    fun observeFinishOutcomes(
        finished: ReadingStatus,
        dnf: ReadingStatus,
    ): Flow<FinishOutcomes>

    /**
     * Every viewing in `[from, to)` of a media item of [type] (#217): the one read behind the Films
     * and Shows tabs. The runtime is the film's for a film and the episode's for an episode, `null`
     * when unknown; a viewing's own duration is never recorded, so it is the only source of time.
     *
     * `isRewatch` asks whether an earlier viewing of the same film -- or the same episode -- exists at
     * any time, so a rewatch of something first seen last year still counts as one this week. Two
     * viewings at the same instant break the tie by id, so exactly one of them is the first.
     * `IS` rather than `=` on `episodeId`, so a film's `NULL` matches a film's `NULL`.
     */
    @Query(
        """
        SELECT w.watchedAt, w.mediaId, w.episodeId, m.title, m.coverImageHash,
               COALESCE(e.runtimeMinutes, md.runtimeMinutes) AS runtimeMinutes,
               EXISTS (SELECT 1 FROM watch_logs p
                        WHERE p.mediaId = w.mediaId AND p.episodeId IS w.episodeId
                          AND (p.watchedAt < w.watchedAt OR (p.watchedAt = w.watchedAt AND p.id < w.id)))
                 AS isRewatch
          FROM watch_logs w
          JOIN media_items m ON m.id = w.mediaId
          LEFT JOIN movie_details md ON md.mediaId = w.mediaId AND w.episodeId IS NULL
          LEFT JOIN episodes e ON e.id = w.episodeId
         WHERE m.type = :type
           AND (:from IS NULL OR w.watchedAt >= :from)
           AND (:to IS NULL OR w.watchedAt < :to)
        """,
    )
    fun observeViewings(
        from: Instant?,
        to: Instant?,
        type: MediaType,
    ): Flow<List<ViewingRow>>

    /**
     * Every rated read, film viewing and watch-through of [type] (#232), each with the date it takes from
     * what it is on: a read's finish, else its latest session's end, else its start (a Did-not-finish read
     * has no finish); a film viewing's day; a watch-through's latest episode viewing. Both bounds null is
     * All time and returns dateless items too; with bounds, only items dated in `[from, to)`.
     */
    @Query(
        """
        SELECT mediaId, title, rating, at, coverImageHash FROM (
            SELECT r.mediaId AS mediaId, m.title AS title, r.rating AS rating, m.type AS type,
                   m.coverImageHash AS coverImageHash,
                   COALESCE(r.finishedAt,
                            (SELECT MAX(s.timestampEnd) FROM reading_sessions s WHERE s.readThroughId = r.id),
                            r.startedAt) AS at
              FROM read_throughs r JOIN media_items m ON m.id = r.mediaId
             WHERE r.rating IS NOT NULL
            UNION ALL
            SELECT w.mediaId, m.title, w.rating, m.type, m.coverImageHash, w.watchedAt
              FROM watch_logs w JOIN media_items m ON m.id = w.mediaId
             WHERE w.episodeId IS NULL AND w.rating IS NOT NULL
            UNION ALL
            SELECT t.mediaId, m.title, t.rating, m.type, m.coverImageHash,
                   (SELECT MAX(l.watchedAt) FROM watch_logs l WHERE l.watchThroughId = t.id)
              FROM watch_throughs t JOIN media_items m ON m.id = t.mediaId
             WHERE t.rating IS NOT NULL
        )
         WHERE type = :type
           AND ((:from IS NULL AND :to IS NULL) OR (at >= :from AND at < :to))
        """,
    )
    fun observeRatedItems(
        from: Instant?,
        to: Instant?,
        type: MediaType,
    ): Flow<List<RatedItemRow>>

    /**
     * Each show's airing state, its latest watch-through's abandoned flag, and whether an earlier run was
     * finished (#217, #231), for "shows finished" (see `countFinishedShows`). Watch again is offered only
     * once a run is complete or abandoned, so an earlier run that is not abandoned was finished.
     */
    @Query(
        "SELECT t.mediaId, t.airingStatus, " +
            "COALESCE((SELECT w.abandoned FROM watch_throughs w WHERE w.mediaId = t.mediaId " +
            "ORDER BY w.number DESC LIMIT 1), 0) AS abandoned, " +
            "EXISTS (SELECT 1 FROM watch_throughs w WHERE w.mediaId = t.mediaId AND w.abandoned = 0 AND w.number < " +
            "(SELECT MAX(x.number) FROM watch_throughs x WHERE x.mediaId = t.mediaId)) AS finishedEarlierRun " +
            "FROM tv_details t",
    )
    fun observeShowStates(): Flow<List<ShowStateRow>>

    /**
     * Reads FINISHED with a finish date in `[from, to)`, with their book's pages (#221): the year's longest
     * book. A re-read appears at its own finish date (#230).
     */
    @Query(
        """
        SELECT b.mediaId, mi.title, b.totalPages, b.authors, r.finishedAt, mi.coverImageHash
          FROM read_throughs r
          JOIN book_details b ON b.mediaId = r.mediaId
          JOIN media_items mi ON mi.id = r.mediaId
         WHERE r.status = :finished AND r.finishedAt >= :from AND r.finishedAt < :to
        """,
    )
    fun observeFinishedBooks(
        from: Instant,
        to: Instant,
        finished: ReadingStatus,
    ): Flow<List<FinishedBookRow>>

    /**
     * The earliest thing logged (#221): a reading session's start, a viewing, or a finish date,
     * whichever came first, or `null` when nothing is logged. The year in review's picker reaches back
     * to its year.
     */
    @Query(
        """
        SELECT MIN(t) FROM (
            SELECT MIN(timestampStart) AS t FROM reading_sessions
            UNION ALL SELECT MIN(watchedAt) FROM watch_logs
            UNION ALL SELECT MIN(finishedAt) FROM read_throughs
        )
        """,
    )
    fun observeFirstActivity(): Flow<Instant?>
}
