package com.hub.media.ui

import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.features.books.data.BookWithDetails

/**
 * UI state for the library/book-list screen.
 *
 * @property books Every currently tracked book together with its [BookWithDetails.details]
 *   (ROADMAP Task 6 Phase C — status filtering needs each book's
 *   [com.hub.media.core.database.entities.BookDetailsEntity.status], which a bare
 *   [com.hub.media.core.database.entities.MediaItemEntity] list can't expose), ordered by title
 *   (see [com.hub.media.features.books.data.BookRepository.observeAllBooksWithDetails]). Always
 *   the *unfiltered* full list — see [filteredBooks] for the one [statusFilter] has been applied
 *   to.
 * @property statusFilter The currently selected status filter, or `null` for "All" (no filter).
 * @property searchQuery The current local-library search text (ROADMAP Task 9 Phase A), or empty
 *   for "no search" (no additional narrowing beyond [statusFilter]). Matched case-insensitively as
 *   a substring against a book's title *or* author — see [filteredBooks].
 * @property isEmpty True when [books] (the unfiltered library) is empty, hoisted here so screens
 *   don't need to re-derive it (AGENTS.md §5 "State Hoisting"). Distinct from [filteredBooks] being
 *   empty, which can happen even with a non-empty library (e.g. filtering to a status nothing
 *   currently has, or a search query matching nothing) — the screen renders a different message for
 *   each case.
 * @property selectedIds Media ids currently selected for a bulk action (ROADMAP Task 14 Phase B).
 *   Empty means selection mode is off -- see [isSelectionMode]. Held as ids rather than as
 *   [BookWithDetails] objects deliberately: the underlying list is reactive, so a selected book can
 *   be re-emitted (an edit elsewhere) or vanish (deleted on another screen) while selection is
 *   active, and an id survives both where a captured object would go stale.
 * @property deleteError Message from the most recent failed bulk delete, or `null`. Without this a
 *   failure left the books present, the selection intact, and nothing on screen -- from the user's
 *   side indistinguishable from the Delete button being ignored. Cleared once shown, so it reports
 *   an event rather than becoming a state the screen can get stuck in.
 */
public data class LibraryUiState(
    val books: List<BookWithDetails> = emptyList(),
    val statusFilter: ReadingStatus? = null,
    val searchQuery: String = "",
    val isEmpty: Boolean = books.isEmpty(),
    val selectedIds: Set<String> = emptySet(),
    val deleteError: String? = null,
) {

    /**
     * True when a bulk selection is active, which is what swaps the library's app bar for the
     * contextual one. Derived from [selectedIds] rather than tracked as its own flag so the two
     * cannot disagree -- a separate boolean could be left true over an empty selection, leaving the
     * user in a mode with no way out and no visible reason why.
     */
    public val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()

    /**
     * [selectedIds] narrowed to books actually present in [filteredBooks].
     *
     * Selection is deliberately *not* cleared when a filter or search changes -- doing so would
     * silently discard work the moment someone refined a search to reach the next book they wanted.
     * But a bulk action must never act on something the user cannot currently see, so this is what
     * the action operates on, and what the count in the contextual bar reflects.
     */
    public val visibleSelectedIds: Set<String>
        get() {
            val visible = filteredBooks.mapTo(mutableSetOf()) { it.mediaItem.id }
            return selectedIds intersect visible
        }

    /**
     * [books] narrowed by [statusFilter] and [searchQuery] **together (AND/intersection, not
     * OR)** — ROADMAP Task 9 Phase A local library search composes with the pre-existing status
     * filter rather than replacing or short-circuiting it: a "Reading" status chip plus a search
     * query narrows to books that are both currently being read *and* match the query, exactly
     * what a user picking both controls would expect ("show me the fantasy novel I'm partway
     * through, out of everything I'm currently reading"), not "show me everything matching either."
     * There is no UI affordance to combine them any other way, so AND is the only behavior this
     * state needs to define.
     *
     * Status matching is unchanged from before this phase: matched against
     * [com.hub.media.core.database.entities.BookDetailsEntity.status]; a book with no
     * [BookWithDetails.details] row — the data-integrity edge case documented on
     * [com.hub.media.features.books.data.BookRepository.observeBookDetail] — never matches a
     * non-null [statusFilter].
     *
     * Search matching (new this phase): [searchQuery] is trimmed, and (when non-blank) matched as
     * a case-insensitive substring against [com.hub.media.core.database.entities.MediaItemEntity.title]
     * **or** [com.hub.media.core.database.entities.BookDetailsEntity.authors] (a book with a blank/
     * missing [searchQuery] is unaffected by this second filter). Matching against the raw, already
     * `"; "`-joined [BookDetailsEntity.authors] string (rather than splitting it back into individual
     * names first) is sufficient for a substring search — searching "tolkien" finds
     * `"J.R.R. Tolkien"` inside `"J.R.R. Tolkien; Christopher Tolkien"` just as well as it would
     * against a parsed list, with no parsing step needed. Deliberately in-memory over the already-
     * reactive [books] list (no DB query, no schema/index work) — personal-scale libraries (tens to
     * low hundreds of books) don't need one, and this keeps the search instant/local exactly like
     * [statusFilter] already is.
     */
    public val filteredBooks: List<BookWithDetails>
        get() {
            val statusFiltered = if (statusFilter == null) books else books.filter { it.details?.status == statusFilter }
            val query = searchQuery.trim()
            if (query.isEmpty()) return statusFiltered
            return statusFiltered.filter { book ->
                book.mediaItem.title.contains(query, ignoreCase = true) ||
                    book.details?.authors?.contains(query, ignoreCase = true) == true
            }
        }
}
