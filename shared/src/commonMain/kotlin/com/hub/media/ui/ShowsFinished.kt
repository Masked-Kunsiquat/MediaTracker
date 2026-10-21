package com.hub.media.ui

import com.hub.media.core.database.dao.ShowStateRow
import com.hub.media.core.database.dao.TVProgressCounts
import com.hub.media.core.database.entities.AiringStatus

/**
 * How many shows are finished (#217), by exactly the rule the library files them under "Finished":
 * [LibraryStatusFilter.ofShow] over the same episode progress, honouring the "include specials"
 * setting, so a fully watched show that is still airing is in progress, not finished, in both.
 *
 * One deliberate difference (#231): a show being rewatched still counts here, because an earlier
 * watch-through finished it, while the library files it by its current run -- In progress, or Not started
 * right after Watch again.
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
        // A show being rewatched (#231) was finished once: Watch again resets the ticks, not the history.
        (show.finishedEarlierRun && show.airingStatus != AiringStatus.CONTINUING) ||
            LibraryStatusFilter.ofShow(
                abandoned = show.abandoned,
                tvProgress = progress[show.mediaId]?.counted(includeSpecials),
                airingStatus = show.airingStatus,
            ) == LibraryStatusFilter.FINISHED
    }
