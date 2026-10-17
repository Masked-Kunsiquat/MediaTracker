package com.hub.media.core.database.dao

import com.hub.media.core.database.entities.BookFormat
import kotlin.time.Instant

/**
 * One timed reading session with its book's format and authors (#219): the single read Books stats
 * part 2 builds its three breakdowns from (when you read, top authors, formats), so they always
 * agree with each other. [durationSeconds] is always above zero; sessions without a known duration
 * are not rows here.
 *
 * @property authors The book's stored authors, `"; "`-joined, or `null` when none is on record.
 */
data class TimedSessionRow(
    val timestampStart: Instant,
    val durationSeconds: Long,
    val format: BookFormat,
    val authors: String?,
)
