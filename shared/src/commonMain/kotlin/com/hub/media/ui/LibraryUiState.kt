package com.hub.media.ui

import com.hub.media.core.database.entities.MediaItemEntity

/**
 * UI state for the library/book-list screen.
 *
 * @property books The currently tracked books, ordered by title (see
 *   [com.hub.media.features.books.data.BookRepository.observeAllBooks]).
 * @property isEmpty True when [books] is empty, hoisted here so screens don't need to
 *   re-derive it (AGENTS.md §5 "State Hoisting").
 */
public data class LibraryUiState(
    val books: List<MediaItemEntity> = emptyList(),
    val isEmpty: Boolean = books.isEmpty(),
)
