package com.hub.media.core.database.dao

import kotlin.time.Instant

/**
 * One episode's worth of provider metadata to fill, addressed by its slot within a show.
 *
 * Carries no `watchedAt`, matching [TVWriteDao.fillEpisodeMetadata]'s statement: a type that cannot
 * express a watch date cannot be the thing that changes one.
 */
public data class EpisodeMetadataFill(
    public val seasonNumber: Int,
    public val episodeNumber: Int,
    public val title: String? = null,
    public val airDate: Instant? = null,
    public val runtimeMinutes: Int? = null,
    public val overview: String? = null,
    public val communityRating: Double? = null,
)
