package com.hub.media.core.database.dao

import kotlin.time.Instant

/**
 * A book finished in a period (#221), for the year in review's longest book.
 *
 * @property totalPages The edition's page count, or `null` when unknown.
 * @property coverImageHash The title's stored cover (#229), for the year in review; `null` when it has none.
 */
data class FinishedBookRow(
    val mediaId: String,
    val title: String,
    val totalPages: Int?,
    val authors: String?,
    val finishedAt: Instant,
    val coverImageHash: String? = null,
)
