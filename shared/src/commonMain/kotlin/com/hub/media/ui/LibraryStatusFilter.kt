package com.hub.media.ui

import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.features.media.data.MediaWithDetails

/**
 * The library's status filter, media-neutral (ROADMAP Task 13 Phase B).
 *
 * ### Why this exists rather than filtering on [ReadingStatus] directly
 * Books store [ReadingStatus] and movies store [WatchStatus] — two enums, deliberately, because
 * unifying them would have meant rewriting every existing book's stored status (see [WatchStatus]'s
 * KDoc). The library list mixes both types, so the *filter* is where they have to be reconciled.
 *
 * Before this, [LibraryUiState.filteredMedia] filtered on [ReadingStatus] and returned `false` for
 * every non-book, meaning the first movie added would have been **invisible to the status filter
 * entirely** — present in the library, absent from every chip except "All". That was recorded as
 * this phase's inherited blocker.
 *
 * This enum is a presentation concern only. Nothing persists it, so adding or renaming a value here
 * costs no migration — which is the point of keeping it out of the entity layer.
 */
public enum class LibraryStatusFilter {
    /** Tracked but not started: [ReadingStatus.TO_READ] / [WatchStatus.WATCHLIST]. */
    BACKLOG,

    /** Started, not finished: [ReadingStatus.READING] / [WatchStatus.WATCHING]. */
    IN_PROGRESS,

    /** Completed: [ReadingStatus.FINISHED] / [WatchStatus.WATCHED]. */
    FINISHED,

    /** Deliberately given up on: [ReadingStatus.DNF] / [WatchStatus.ABANDONED]. */
    ABANDONED,
    ;

    /**
     * Whether [media] currently sits at this status.
     *
     * An item whose detail row is missing matches nothing — it has no status to compare, and
     * claiming otherwise would put it under a chip it does not belong to. It still appears under
     * "All", which is the filter's `null` case and never reaches this function.
     */
    public fun matches(media: MediaWithDetails): Boolean =
        when (media) {
            is MediaWithDetails.Book -> media.details?.status?.let(::of) == this
            is MediaWithDetails.Movie -> media.details?.status?.let(::of) == this
            // TV has no reachable status yet: nothing creates a TV_SHOW row, and TVDetailsEntity has
            // no DAO until Phase C. **Phase C must add the tv_details case here**, or shows will be
            // invisible to every chip exactly as movies were before this class existed -- the same
            // bug, rediscovered. It is unreachable rather than wrong today only because no TV row
            // can exist.
            is MediaWithDetails.TVShow -> false
        }

    public companion object {
        /** Maps a book's stored status onto the shared filter vocabulary. */
        public fun of(status: ReadingStatus): LibraryStatusFilter =
            when (status) {
                ReadingStatus.TO_READ -> BACKLOG
                ReadingStatus.READING -> IN_PROGRESS
                ReadingStatus.FINISHED -> FINISHED
                ReadingStatus.DNF -> ABANDONED
            }

        /** Maps a movie's stored status onto the shared filter vocabulary. */
        public fun of(status: WatchStatus): LibraryStatusFilter =
            when (status) {
                WatchStatus.WATCHLIST -> BACKLOG
                WatchStatus.WATCHING -> IN_PROGRESS
                WatchStatus.WATCHED -> FINISHED
                WatchStatus.ABANDONED -> ABANDONED
            }
    }
}
