package com.hub.media.core.database.dao

import kotlin.time.Instant

/**
 * One watch-through's viewings in one season (#231): how many distinct episodes, and its first and last
 * viewing. A projection of [WatchLogDao.observeRunSeasonRows], not a table.
 */
data class RunSeasonRow(
    val runId: String,
    val seasonNumber: Int,
    val episodes: Int,
    val firstAt: Instant,
    val lastAt: Instant,
)
