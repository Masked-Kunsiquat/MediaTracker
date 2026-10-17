package com.hub.media.core.database.dao

import com.hub.media.core.database.entities.AiringStatus
import com.hub.media.core.database.entities.WatchStatus

/**
 * A show's stored status and whether it is still airing (#217): the two `tv_details` inputs
 * `LibraryStatusFilter.ofShow` takes besides episode progress, read for "shows finished".
 */
data class ShowStateRow(
    val mediaId: String,
    val status: WatchStatus,
    val airingStatus: AiringStatus?,
)
