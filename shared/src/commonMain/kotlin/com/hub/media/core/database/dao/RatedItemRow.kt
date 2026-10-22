package com.hub.media.core.database.dao

import kotlin.time.Instant

/**
 * One rated read, film viewing or watch-through (#232): its title, its rating in halves, and the date
 * it takes from what it is on (see [StatsDao.observeRatedItems]); `null` when that has no date.
 *
 * @property coverImageHash The title's stored cover (#229), for the year in review; `null` when it has none.
 */
public data class RatedItemRow(
    val mediaId: String,
    val title: String,
    val rating: Int,
    val at: Instant?,
    val coverImageHash: String? = null,
)
