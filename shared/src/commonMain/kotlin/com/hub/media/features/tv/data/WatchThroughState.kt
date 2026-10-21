package com.hub.media.features.tv.data

import com.hub.media.core.database.dao.TVProgressRow

/** Every counted episode ticked in the latest run (#231); a show with none is never complete. */
public fun isComplete(counted: TVProgressRow): Boolean =
    counted.totalEpisodes > 0 && counted.watchedEpisodes >= counted.totalEpisodes
