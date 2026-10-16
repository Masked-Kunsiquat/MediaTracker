package com.hub.media.core.database.dao

/**
 * One stats period's numbers, read together by [StatsDao.observePeriodStats] (#91) so they can
 * never disagree with each other. `null` [timeReadSeconds] / [pagesRead] mean "no session in range
 * has a known value", never zero; the counts are real counts, so `0` is genuine.
 *
 * Since #219 the row also carries what speed and session lengths are derived from, so they share
 * the same single read and can never disagree with the totals above:
 * @property timedSessionCount Sessions with a known duration -- the average's denominator.
 * @property longestSessionSeconds The longest known duration, or `null` when none is known.
 * @property speedSeconds / [speedPages] Time and pages over sessions where BOTH are known and the
 *   duration is above zero, or `null` when there is no such session. A session missing either
 *   value would otherwise inflate or deflate the speed.
 */
public data class PeriodStatsRow(
    val timeReadSeconds: Long?,
    val sessionCount: Int,
    val pagesRead: Int?,
    val booksFinished: Int,
    val timedSessionCount: Int,
    val longestSessionSeconds: Long?,
    val speedSeconds: Long?,
    val speedPages: Int?,
)
