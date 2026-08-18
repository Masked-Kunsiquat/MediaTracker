package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.domain.BulkDeleteUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

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
 * @param bookRepository Source of the reactive book list and the single-book delete operation.
 * @param deleteBooksUseCase Bulk delete with reference-aware cover cleanup (ROADMAP Task 14 Phase
 *   B). Required rather than optional-with-a-default: an unwired dependency would make
 *   [deleteSelected] silently do nothing, and a delete button that quietly does nothing is the
 *   exact failure this codebase has already shipped twice (see ROADMAP's Compose-test-harness
 *   entry). A missing dependency should not compile.
 */
public class LibraryViewModel(
    private val bookRepository: BookRepository,
    private val deleteBooksUseCase: BulkDeleteUseCase,
) : ViewModel() {
    private val statusFilter = MutableStateFlow<ReadingStatus?>(null)
    private val searchQuery = MutableStateFlow("")
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val deleteError = MutableStateFlow<DeleteErrorEvent?>(null)
    private var deleteErrorSeq = 0L

    public val uiState: StateFlow<LibraryUiState> =
        combine(
            bookRepository.observeAllBooksWithDetails(),
            statusFilter,
            searchQuery,
            selectedIds,
            deleteError,
        ) { books, filter, query, selected, error ->
            LibraryUiState(
                books = books,
                statusFilter = filter,
                searchQuery = query,
                isEmpty = books.isEmpty(),
                // Drop ids that no longer exist. A selected book can be deleted from Book Detail while
                // selection is active, and a stale id would keep inflating the contextual bar's count
                // and be passed to a delete that could do nothing with it.
                selectedIds = selected intersect books.mapTo(mutableSetOf()) { it.mediaItem.id },
                deleteError = error,
            )
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

    /**
     * Adds or removes [id] from the current selection (ROADMAP Task 14 Phase B), entering selection
     * mode on the first one and leaving it when the last is removed -- see
     * [LibraryUiState.isSelectionMode] for why that is derived rather than a separate flag.
     */
    public fun toggleSelection(id: String) {
        selectedIds.value = selectedIds.value.let { if (id in it) it - id else it + id }
    }

    /**
     * Acknowledges a delete failure once the screen has shown it, so the same message is not
     * re-shown on the next recomposition. Reported as a one-shot event rather than durable state:
     * an error the user has already read is not a condition the library is still in.
     */
    public fun consumeDeleteError(id: Long) {
        // Only clears the event actually shown. Without the id check, a failure arriving while the
        // previous snackbar was still on screen would be discarded unseen.
        if (deleteError.value?.id == id) deleteError.value = null
    }

    /** Leaves selection mode, discarding the selection. Backs the contextual bar's close action. */
    public fun clearSelection() {
        selectedIds.value = emptySet()
    }

    /**
     * Deletes every currently selected book, whether or not the active filter or search happens to
     * be showing it, then leaves selection mode.
     *
     * Selection is cleared **after** the delete completes, not before: clearing first would leave a
     * failure with nothing selected and no way to retry without re-picking every book. [uiState]
     * reflects the removal reactively, so nothing needs threading back here -- matching
     * [deleteBook]'s existing shape.
     */
    public fun deleteSelected() {
        // Reads the selection source of truth, NOT uiState.value. uiState is
        // stateIn(WhileSubscribed), so its value is only recomputed while something is collecting
        // it -- during the stop-timeout window after the screen stops collecting, a selection made
        // here is simply not in it yet, `ids` comes back empty, and this returns having deleted
        // nothing and reported nothing. A delete button that silently does nothing is the exact
        // failure this class's KDoc already records shipping twice.
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) {
            clearSelection()
            return
        }
        viewModelScope.launch {
            // Selection is cleared only on success. A failed delete that also wiped the selection
            // would leave the user with nothing selected, no feedback, and every book to re-pick
            // before they could try again.
            when (val result = deleteBooksUseCase.execute(ids)) {
                is Resource.Success -> clearSelection()
                is Resource.Error ->
                    deleteError.value = DeleteErrorEvent(++deleteErrorSeq, result.message)
            }
        }
    }

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
