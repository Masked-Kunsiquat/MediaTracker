package com.hub.media.ui

import com.hub.media.features.books.domain.BookDraft

/** UI state for the "add book by ISBN" screen. */
public sealed class AddBookUiState {
    /** No submission has been made yet, or [AddBookViewModel.reset] was called. */
    public data object Idle : AddBookUiState()

    /** A submission is in flight; further calls to [AddBookViewModel.addBook] are ignored. */
    public data object Loading : AddBookUiState()

    /**
     * The book has been looked up and is open for the user to check and edit before anything is
     * saved (#214). [isSaving] is a save in flight; [errorMessage] is a rejected one, shown on the
     * still-open review.
     */
    public data class Reviewing(
        val draft: BookDraft,
        val isSaving: Boolean = false,
        val errorMessage: String? = null,
    ) : AddBookUiState()

    /**
     * Ingestion succeeded.
     *
     * @property mediaId The newly created book's media ID.
     */
    public data class Success(
        val mediaId: String,
    ) : AddBookUiState()

    /**
     * Ingestion failed.
     *
     * @property message A user-facing/diagnostic description of the failure.
     */
    public data class Error(
        val message: String,
    ) : AddBookUiState()
}
