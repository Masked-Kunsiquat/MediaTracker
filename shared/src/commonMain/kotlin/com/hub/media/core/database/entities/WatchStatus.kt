package com.hub.media.core.database.entities

/**
 * Viewing lifecycle status for a [MediaType.MOVIE] or [MediaType.TV_SHOW] (schema v6, ROADMAP
 * Task 13 Phase A). The viewing counterpart of [ReadingStatus].
 *
 * ### Why a second enum rather than generalizing [ReadingStatus]
 * Task 13 Phase A was handed this as an open decision: generalize the existing book-shaped enum
 * across every media type, or give non-book types their own. Deciding it against the real schema
 * (rather than in the abstract, per that issue) settled it on **data safety**:
 *
 * [ReadingStatus] is persisted as its `name` string (see
 * [com.hub.media.core.database.converters.Converters] — enums are stored by name, never ordinal).
 * Renaming `TO_READ`/`READING`/`FINISHED` to media-neutral equivalents would therefore not be a
 * rename at all: it would be a **data migration rewriting the `status` column of every existing
 * `book_details` row**, to buy naming symmetry and nothing else. AGENTS.md §1 puts user data
 * safety ahead of tidiness, and this trade is the wrong way round.
 *
 * The reverse direction also matters: adding a second enum now leaves unifying them later
 * possible, whereas rewriting every book's stored status is not something a later change can
 * un-do if the naming turns out to have been fine.
 *
 * The semantics are genuinely different too, which is a smaller argument but points the same way:
 * "watchlist" is the natural word for an unwatched film, and [ReadingStatus.DNF] carries a
 * specifically bookish connotation that [ABANDONED] does not.
 *
 * ### The cost this defers, deliberately and with a named owner
 * [com.hub.media.ui.LibraryUiState.filteredMedia]'s status filter now has **two** enums to
 * reconcile rather than one. That is Task 13 Phase B's problem and is recorded on its issue: today
 * the filter returns `false` for every non-book item, so a movie row would be hidden from the
 * library filter entirely. Phase B has to map both enums onto whatever the filter chip exposes.
 */
public enum class WatchStatus {
    /** Added to the library but not yet started. The default for a newly-added movie or show. */
    WATCHLIST,

    /**
     * Currently being watched. For a show this means some but not all episodes are watched; for a
     * film, that it was started and not finished in one sitting.
     */
    WATCHING,

    /** Finished. For a show, every episode watched. */
    WATCHED,

    /** Deliberately abandoned partway through — the viewing counterpart of [ReadingStatus.DNF]. */
    ABANDONED,
}
