package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.MediaRepository
import com.hub.media.core.util.Resource
import com.hub.media.features.media.domain.BulkDeleteUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the library/media-list screen.
 *
 * Consolidated from book-only version per Issue #67.
 *
 * @param mediaRepository Source of the reactive, all-types media list and universal media
 *   operations. Deliberately not a `BookRepository`: the library list is polymorphic per Issue #67,
 *   so it must not depend on book-specific data access.
 * @param deleteMediaUseCase Bulk delete with reference-aware cover cleanup.
 */
public class LibraryViewModel(
    private val mediaRepository: MediaRepository,
    private val deleteMediaUseCase: BulkDeleteUseCase,
) : ViewModel() {
    private val statusFilter = MutableStateFlow<LibraryStatusFilter?>(null)
    private val searchQuery = MutableStateFlow("")
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val deleteError = MutableStateFlow<DeleteErrorEvent?>(null)
    private var deleteErrorSeq = 0L

    /**
     * The library list paired with derived TV episode progress (ROADMAP Task 13 Phase C).
     *
     * Combined here rather than as a sixth source below only because `combine` has no arity that
     * wide; the two always arrive together as far as the UI is concerned, and a show's chip is
     * wrong if it is placed before its episode counts land.
     */
    private val mediaWithProgress =
        combine(
            mediaRepository.observeAllMediaWithDetails(),
            mediaRepository.observeTVProgressByMediaId(),
        ) { media, progress -> media to progress }

    public val uiState: StateFlow<LibraryUiState> =
        combine(
            mediaWithProgress,
            statusFilter,
            searchQuery,
            selectedIds,
            deleteError,
        ) { (media, tvProgress), filter, query, selected, error ->
            LibraryUiState(
                media = media,
                tvProgress = tvProgress,
                statusFilter = filter,
                searchQuery = query,
                // Drop ids that no longer exist.
                selectedIds = selected intersect media.mapTo(mutableSetOf()) { it.item.id },
                deleteError = error,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5.seconds),
            initialValue = LibraryUiState(),
        )

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
     * Deletes every currently selected item, whether or not the active filter or search happens to
     * be showing it, then leaves selection mode.
     */
    public fun deleteSelected() {
        // Reads the selection source of truth, NOT uiState.value.
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) {
            clearSelection()
            return
        }
        viewModelScope.launch {
            // Selection is cleared only on success.
            when (val result = deleteMediaUseCase.execute(ids)) {
                is Resource.Success -> clearSelection()
                is Resource.Error ->
                    deleteError.value = DeleteErrorEvent(++deleteErrorSeq, result.message)
            }
        }
    }

    /**
     * Deletes the item identified by [id] via [BulkDeleteUseCase] to ensure
     * reference-aware cover cleanup (ROADMAP Task 14 Phase B).
     */
    public fun deleteMediaItem(id: String) {
        viewModelScope.launch {
            when (val result = deleteMediaUseCase.execute(listOf(id))) {
                is Resource.Success -> Unit
                is Resource.Error ->
                    deleteError.value = DeleteErrorEvent(++deleteErrorSeq, result.message)
            }
        }
    }

    /**
     * Sets the library's status filter (ROADMAP Task 6 Phase C): `null` shows every item ("All"),
     * a specific [LibraryStatusFilter] narrows [LibraryUiState.filteredMedia] to items currently at that
     * status.
     */
    public fun setStatusFilter(status: LibraryStatusFilter?) {
        statusFilter.value = status
    }

    /**
     * Sets the library's local search query (ROADMAP Task 9 Phase A): empty/blank means "no
     * search" (no additional narrowing).
     */
    public fun setSearchQuery(query: String) {
        searchQuery.value = query
    }
}
