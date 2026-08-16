package com.hub.media.ui

import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode

/** UI state for the edit-book-metadata screen (ROADMAP Task 6 Phase A). */
public sealed class EditBookUiState {
    /** Initial state before the book's current metadata has loaded. */
    public data object Loading : EditBookUiState()

    /**
     * The book id [EditBookViewModel] was constructed with does not resolve to any
     * [MediaItemEntity] — either it was never valid, or the book was deleted (e.g. from the
     * library or Book Detail screen) while this screen was open.
     */
    public data object NotFound : EditBookUiState()

    /**
     * The book's current metadata, prefilled for editing.
     *
     * @property format Falls back to [BookFormat.PHYSICAL] when this book has no
     *   [BookDetailsEntity] row yet (the data-integrity edge case documented on
     *   [com.hub.media.features.books.data.BookRepository.observeBookDetail] /
     *   [com.hub.media.features.books.data.BookRepository.updateBookMetadata]) — a save from this
     *   state self-heals by creating the missing row, see that method's KDoc.
     * @property status Current [ReadingStatus] (ROADMAP Task 6 Phase C). Falls back to
     *   [ReadingStatus.TO_READ] for the same missing-[BookDetailsEntity]-row edge case as [format].
     * @property trackingMode Current [TrackingMode] (schema v4, ROADMAP Task 7 Phase A). Falls back
     *   to [TrackingMode.PAGES] for the same missing-[BookDetailsEntity]-row edge case as [format]
     *   (mirroring [BookDetailsEntity.trackingMode]'s own field-level default).
     * @property errorMessage Message from the most recently failed [EditBookViewModel.save] call,
     *   or null.
     * @property isSaving True while a [EditBookViewModel.save] call is in flight, so the UI can
     *   disable the Save button and avoid a double-submit.
     */
    public data class Ready(
        val title: String,
        val releaseYear: Int?,
        val purchasePrice: Double?,
        val totalPages: Int?,
        val format: BookFormat,
        val status: ReadingStatus = ReadingStatus.TO_READ,
        val trackingMode: TrackingMode = TrackingMode.PAGES,
        val errorMessage: String? = null,
        val isSaving: Boolean = false,
    ) : EditBookUiState()

    /**
     * [EditBookViewModel.save] succeeded. The route wrapper navigates back on this state; once
     * reached, [uiState][EditBookViewModel.uiState] stays [Saved] regardless of further DB
     * emissions (see [EditBookViewModel] KDoc).
     */
    public data object Saved : EditBookUiState()
}
