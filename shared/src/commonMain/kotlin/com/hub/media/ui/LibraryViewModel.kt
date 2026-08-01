package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.features.books.data.BookRepository
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the library/book-list screen.
 *
 * [uiState] is a hot [StateFlow] derived from [BookRepository.observeAllBooks] (AGENTS.md §2 —
 * `StateFlow`/`SharedFlow` for UI state, no raw callbacks): it starts collecting the underlying
 * reactive query when the first subscriber appears and stops 5 seconds after the last one
 * disappears (survives brief configuration-change-style gaps without leaking a live DB query
 * forever).
 *
 * @param bookRepository Source of the reactive book list and the delete operation.
 */
public class LibraryViewModel(
    private val bookRepository: BookRepository,
) : ViewModel() {

    public val uiState: StateFlow<LibraryUiState> = bookRepository.observeAllBooks()
        .map { books -> LibraryUiState(books = books, isEmpty = books.isEmpty()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5.seconds),
            initialValue = LibraryUiState(),
        )

    /**
     * Deletes the book identified by [id]. Fire-and-forget: [uiState] reflects the outcome
     * reactively via [BookRepository.observeAllBooks] once the delete completes, so no separate
     * result needs to be threaded back to the caller here.
     */
    public fun deleteBook(id: String) {
        viewModelScope.launch {
            bookRepository.deleteBook(id)
        }
    }
}
