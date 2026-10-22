package com.hub.media.core.database.dao

import com.hub.media.core.database.entities.AiringStatus

/**
 * Whether a show is still airing, and its watch-throughs' state (#217, #231): what
 * `LibraryStatusFilter.ofShow` takes besides episode progress, read for "shows finished".
 */
data class ShowStateRow(
    val mediaId: String,
    val airingStatus: AiringStatus?,
    /** The latest watch-through's flag (#231). */
    val abandoned: Boolean,
    /** An earlier, non-abandoned watch-through exists: the show was finished before this rewatch began (#231). */
    val finishedEarlierRun: Boolean,
)
