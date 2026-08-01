package com.hub.media.ui

import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.ReadingSessionEntity
import com.hub.media.features.books.timer.ReadingTimerResult

/** UI state for the book detail screen (ROADMAP Task 4 Phase B). */
public sealed class BookDetailUiState {

    /** Initial state before the first book/session emission arrives. */
    public data object Loading : BookDetailUiState()

    /**
     * The book id [BookDetailViewModel] was constructed with does not resolve to any
     * [MediaItemEntity] — either it was never valid, or the book was deleted (e.g. via
     * [LibraryViewModel.deleteBook]) while this screen was open.
     */
    public data object NotFound : BookDetailUiState()

    /**
     * The book exists; its metadata and session history are loaded.
     *
     * @property book Universal media metadata.
     * @property details Book-specific metadata (isbn/format/totalPages). Null only in the
     *   data-integrity edge case where no [BookDetailsEntity] row exists for this media id —
     *   never expected in practice since [com.hub.media.features.books.data.BookRepository.addBook]
     *   inserts [MediaItemEntity] and [BookDetailsEntity] atomically.
     * @property sessions Reading session history, most recent first, exactly as
     *   [com.hub.media.features.books.data.ReadingSessionRepository.observeSessionsForMedia]
     *   provides it.
     * @property pendingSession A finished [com.hub.media.features.books.timer.ReadingTimer] run
     *   awaiting user-entered position bounds before [BookDetailViewModel.saveSession] can persist
     *   it, or null if there is none. See [BookDetailViewModel] KDoc for why this lives on [Ready]
     *   rather than a separate `StateFlow`.
     * @property errorMessage Message from the most recently failed
     *   [BookDetailViewModel.saveSession] or [BookDetailViewModel.logManualSession] call, or null.
     *   Deliberately NOT cleared when [pendingSession] itself changes shape via unrelated DB
     *   emissions — only a subsequent successful save or [BookDetailViewModel.discardPendingSession]
     *   clears it — so the user's error doesn't silently vanish on an unrelated recomposition.
     */
    public data class Ready(
        val book: MediaItemEntity,
        val details: BookDetailsEntity?,
        val sessions: List<ReadingSessionEntity> = emptyList(),
        val pendingSession: ReadingTimerResult? = null,
        val errorMessage: String? = null,
    ) : BookDetailUiState() {

        /**
         * Current reading progress: the [ReadingSessionEntity.endUnit] of the most recent session
         * (`sessions` is already most-recent-first, so this is simply `sessions.firstOrNull()`),
         * or null if no session has ever been logged. Computed on read rather than stored as a
         * constructor property so it can never drift out of sync with [sessions] through
         * `copy()` (a stored default-expression property is only evaluated when a constructor
         * parameter is omitted from a specific `copy()`/constructor call, not recomputed from the
         * new field values — a derived `get()` sidesteps that trap entirely).
         */
        val currentProgress: Double? get() = sessions.firstOrNull()?.endUnit
    }
}
