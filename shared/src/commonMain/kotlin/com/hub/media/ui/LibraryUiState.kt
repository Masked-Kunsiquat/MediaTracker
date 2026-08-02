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
 * @property isEmpty True when [books] (the unfiltered library) is empty, hoisted here so screens
 *   don't need to re-derive it (AGENTS.md §5 "State Hoisting"). Distinct from [filteredBooks] being
 *   empty, which can happen even with a non-empty library (e.g. filtering to a status nothing
 *   currently has) — the screen renders a different message for each case.
 */
public data class LibraryUiState(
    val books: List<BookWithDetails> = emptyList(),
    val statusFilter: ReadingStatus? = null,
    val isEmpty: Boolean = books.isEmpty(),
) {

    /**
     * [books] narrowed to [statusFilter] (matched against
     * [com.hub.media.core.database.entities.BookDetailsEntity.status]; a book with no
     * [BookWithDetails.details] row — the data-integrity edge case documented on
     * [com.hub.media.features.books.data.BookRepository.observeBookDetail] — never matches a
     * non-null filter), or all of [books] unchanged when [statusFilter] is `null` ("All").
     */
    public val filteredBooks: List<BookWithDetails>
        get() = if (statusFilter == null) books else books.filter { it.details?.status == statusFilter }
}
