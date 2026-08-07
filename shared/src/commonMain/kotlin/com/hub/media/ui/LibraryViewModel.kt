package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.features.books.data.BookRepository
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the library/book-list screen.
 *
 * [uiState] is a hot [StateFlow] combining [BookRepository.observeAllBooksWithDetails] (AGENTS.md
 * §2 — `StateFlow`/`SharedFlow` for UI state, no raw callbacks) with an in-memory
 * [statusFilter][setStatusFilter] (ROADMAP Task 6 Phase C) and an in-memory
 * [searchQuery][setSearchQuery] (ROADMAP Task 9 Phase A) — neither has a DB representation, both
 * are purely client-side view filters applied together by [LibraryUiState.filteredBooks] (see its
 * KDoc for why they compose as AND, not OR): it starts collecting the underlying reactive query
 * when the first subscriber appears and stops 5 seconds after the last one disappears (survives
 * brief configuration-change-style gaps without leaking a live DB query forever).
 *
 * @param bookRepository Source of the reactive book list and the delete operation.
 */
public class LibraryViewModel(
    private val bookRepository: BookRepository,
) : ViewModel() {

    private val statusFilter = MutableStateFlow<ReadingStatus?>(null)
    private val searchQuery = MutableStateFlow("")

    public val uiState: StateFlow<LibraryUiState> = combine(
        bookRepository.observeAllBooksWithDetails(),
        statusFilter,
        searchQuery,
    ) { books, filter, query ->
        LibraryUiState(books = books, statusFilter = filter, searchQuery = query, isEmpty = books.isEmpty())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5.seconds),
        initialValue = LibraryUiState(),
    )

    /**
     * Deletes the book identified by [id]. Fire-and-forget: [uiState] reflects the outcome
     * reactively via [BookRepository.observeAllBooksWithDetails] once the delete completes, so no
     * separate result needs to be threaded back to the caller here.
     */
    public fun deleteBook(id: String) {
        viewModelScope.launch {
            bookRepository.deleteBook(id)
        }
    }

    /**
     * Sets the library's status filter (ROADMAP Task 6 Phase C): `null` shows every book ("All"),
     * a specific [ReadingStatus] narrows [LibraryUiState.filteredBooks] to books currently at that
     * status. Purely client-side/in-memory — does not re-query the database, since
     * [bookRepository]'s underlying flow is already unfiltered and held live for [uiState].
     */
    public fun setStatusFilter(status: ReadingStatus?) {
        statusFilter.value = status
    }

    /**
     * Sets the library's local search query (ROADMAP Task 9 Phase A): empty/blank means "no
     * search" (no additional narrowing). Purely client-side/in-memory — see
     * [LibraryUiState.filteredBooks]'s KDoc for the exact title-or-author substring match and how
     * this composes with [setStatusFilter].
     */
    public fun setSearchQuery(query: String) {
        searchQuery.value = query
    }
}
