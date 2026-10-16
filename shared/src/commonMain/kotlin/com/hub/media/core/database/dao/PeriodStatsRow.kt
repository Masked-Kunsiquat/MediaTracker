package com.hub.media.core.database.dao

/**
 * One stats period's numbers, read together by [StatsDao.observePeriodStats] (#91) so they can
 * never disagree with each other. `null` [timeReadSeconds] / [pagesRead] mean "no session in range
 * has a known value", never zero; the counts are real counts, so `0` is genuine.
 */
public data class PeriodStatsRow(
    val timeReadSeconds: Long?,
    val sessionCount: Int,
    val pagesRead: Int?,
    val booksFinished: Int,
)
