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

/**
 * A delete failure, carried as an event rather than a bare message.
 *
 * [id] exists because two consecutive failures can produce an identical [message] -- a repeated
 * retry against the same broken state is the likely case, not an exotic one. Keyed only on the
 * text, the UI's `LaunchedEffect` would see no change and silently swallow the second, leaving the
 * user with a delete that appears to have quietly succeeded.
 */
public data class DeleteErrorEvent(
    public val id: Long,
    public val message: String,
)

public data class LibraryUiState(
    val books: List<BookWithDetails> = emptyList(),
    val statusFilter: ReadingStatus? = null,
    val searchQuery: String = "",
    val isEmpty: Boolean = books.isEmpty(),
    val selectedIds: Set<String> = emptySet(),
    val deleteError: DeleteErrorEvent? = null,
) {
    /**
     * True when a bulk selection is active, which is what swaps the library's app bar for the
     * contextual one. Derived from [selectedIds] rather than tracked as its own flag so the two
     * cannot disagree -- a separate boolean could be left true over an empty selection, leaving the
     * user in a mode with no way out and no visible reason why.
     */
    public val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()

    /**
     * The books [selectedIds] refers to, in library order, regardless of the active filter or
     * search.
     *
     * Selection is a property of the books, not of the current view: hiding a selected book behind
     * a filter does not unselect it, and a bulk action operates on everything selected. An earlier
     * version scoped actions to only the *visible* selection, which was reasoned as a safety
     * measure -- never act on something the user cannot see -- and turned out to be worse in
     * practice. The count changed as filters changed, which read as the selection being silently
     * lost, and a delete then did a partial job leaving the rest selected and invisible with
     * nothing to explain it. Found by using the app, not by any test.
     *
     * What replaces that safety is the confirmation naming the books by title, so "something you
     * cannot see" no longer applies -- it is listed in the dialog.
     */
    public val selectedBooks: List<BookWithDetails>
        get() = books.filter { it.mediaItem.id in selectedIds }

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
            val statusFiltered =
                if (statusFilter ==
                    null
                ) {
                    books
                } else {
                    books.filter { it.details?.status == statusFilter }
                }
            val query = searchQuery.trim()
            if (query.isEmpty()) return statusFiltered
            return statusFiltered.filter { book ->
                book.mediaItem.title.contains(query, ignoreCase = true) ||
                    book.details?.authors?.contains(query, ignoreCase = true) == true
            }
        }
}
