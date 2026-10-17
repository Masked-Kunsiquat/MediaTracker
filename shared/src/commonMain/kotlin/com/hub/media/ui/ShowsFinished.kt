package com.hub.media.ui

import com.hub.media.core.database.dao.ShowStateRow
import com.hub.media.core.database.dao.TVProgressCounts

/**
 * How many shows are finished (#217), by exactly the rule the library files them under "Finished":
 * [LibraryStatusFilter.ofShow] over the same episode progress, honouring the "include specials"
 * setting. So Stats and the library can never disagree about whether a show is done -- a fully
 * watched show that is still airing is in progress, not finished, in both.
 *
 * A show has no "finished on" date, so this is an All time number only.
 *
 * @param progress Each show's episode counts by id; a show with no episodes is absent, and not
 *   started rather than finished.
 */
public fun countFinishedShows(
    states: List<ShowStateRow>,
    progress: Map<String, TVProgressCounts>,
    includeSpecials: Boolean,
): Int =
    states.count { show ->
        LibraryStatusFilter.ofShow(
            storedStatus = show.status,
            tvProgress = progress[show.mediaId]?.counted(includeSpecials),
            airingStatus = show.airingStatus,
        ) == LibraryStatusFilter.FINISHED
    }
