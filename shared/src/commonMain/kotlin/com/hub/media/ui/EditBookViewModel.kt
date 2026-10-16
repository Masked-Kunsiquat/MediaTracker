package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.media.data.MediaWithDetails
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the edit-book-metadata screen (ROADMAP Task 6 Phase A): lets the user correct
 * provider-supplied metadata a real-world edition got wrong — e.g. Open Library reporting 384
 * pages for an edition that physically has 366 — and record a more precise [BookFormat].
 *
 * ### Why a dedicated ViewModel rather than extending [BookDetailViewModel]
 * [Route.EditBook][com.github.maskedkunisquat.mediatracker.ui.navigation.Route] (app module) is a
 * separate navigation destination from [Route.BookDetail], and Compose Navigation gives each
 * `composable()` destination its own `ViewModelStoreOwner` by default — sharing one
 * [BookDetailViewModel] instance across both screens would require nav-graph-scoped ViewModel
 * wiring this project doesn't otherwise use, or constructing a second [BookDetailViewModel] (with
 * its own live [com.hub.media.features.books.timer.ReadingTimer]) purely to reuse a couple of
 * fields. A small dedicated ViewModel, constructed per-navigation-argument exactly like
 * [BookDetailViewModel] already is (via a per-bookId factory), is simpler and keeps this screen's
 * form-editing concerns (no timer, no session history) separate from Book Detail's.
 *
 * ### Reactive [uiState], and why it can settle into [EditBookUiState.Saved] for good
 * [uiState] combines [BookRepository.observeBookDetail] (the DB-backed current metadata) with an
 * in-memory [localState] (errorMessage/isSaving/saved — no DB representation), matching
 * [BookDetailViewModel]'s combine-based shape. `local.saved` is checked first in the `combine`
 * lambda: once [save] succeeds, every subsequent emission (even a later, unrelated
 * [BookRepository.observeBookDetail] re-emission) still maps to [EditBookUiState.Saved] rather
 * than flipping back to [EditBookUiState.Ready] — there is nothing left for this screen to do
 * once its one save has succeeded, and the route wrapper's `LaunchedEffect` navigates back on
 * first seeing [EditBookUiState.Saved].
 *
 * @param bookId The media id this screen was opened for.
 * @param bookRepository Source of reactive book metadata and [BookRepository.updateBookMetadata].
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class EditBookViewModel(
    private val bookId: String,
    private val bookRepository: BookRepository,
) : ViewModel() {
    /** UI-only state with no DB representation; see class KDoc. */
    private data class LocalState(
        val errorMessage: String? = null,
        val isSaving: Boolean = false,
        val saved: Boolean = false,
    )

    private val localState = MutableStateFlow(LocalState())

    /**
     * In-flight guard for [save], mirroring [BookDetailViewModel.saveSession]'s rationale: a
     * double-tap on Save fires the click handler twice before the first `launch`'s suspending
     * [BookRepository.updateBookMetadata] call completes; without this guard both calls would
     * fire the same update concurrently. Plain `var` is safe here for the same reason it is on
     * [BookDetailViewModel]: [viewModelScope] dispatches on the main thread, so reads/writes are
     * never concurrent, only interleaved between suspension points.
     */
    private var saveInFlight: Boolean = false

    /** Where the book's current metadata stands: still being read, read, or failed. */
    private sealed interface Load {
        data object Pending : Load

        data class Loaded(
            val book: MediaWithDetails.Book?,
        ) : Load

        data object Failed : Load
    }

    /** Bumped by [retry]; each value restarts the read below. */
    private val loadAttempt = MutableStateFlow(0)

    /**
     * The book's metadata, with a failed read turned into [Load.Failed] rather than left to escape
     * the `stateIn` coroutine, which would crash the app. `catch` does not swallow cancellation.
     */
    private val load =
        loadAttempt.flatMapLatest {
            bookRepository
                .observeBookDetail(bookId)
                .map<MediaWithDetails.Book?, Load> { Load.Loaded(it) }
                .onStart { emit(Load.Pending) }
                .catch { emit(Load.Failed) }
        }

    /**
     * Reads the book again after [EditBookUiState.LoadFailed]. A no-op in any other state, so a
     * stray tap cannot restart a read the form already has.
     */
    public fun retry() {
        if (uiState.value is EditBookUiState.LoadFailed) loadAttempt.update { it + 1 }
    }

    public val uiState: StateFlow<EditBookUiState> =
        combine(
            load,
            localState,
        ) { load, local ->
            val bookDetail = (load as? Load.Loaded)?.book
            when {
                local.saved -> EditBookUiState.Saved
                load is Load.Pending -> EditBookUiState.Loading
                load is Load.Failed -> EditBookUiState.LoadFailed
                bookDetail == null -> EditBookUiState.NotFound
                else ->
                    EditBookUiState.Ready(
                        title = bookDetail.item.title,
                        releaseYear = bookDetail.item.releaseYear,
                        purchasePrice = bookDetail.item.purchasePrice,
                        totalPages = bookDetail.details?.totalPages,
                        format = bookDetail.details?.format ?: BookFormat.PHYSICAL,
                        status = bookDetail.details?.status ?: ReadingStatus.TO_READ,
                        trackingMode = bookDetail.details?.trackingMode ?: TrackingMode.PAGES,
                        errorMessage = local.errorMessage,
                        isSaving = local.isSaving,
                        coverImageHash = bookDetail.item.coverImageHash,
                    )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5.seconds),
            initialValue = EditBookUiState.Loading,
        )

    /**
     * Persists edited metadata via [BookRepository.updateBookMetadata]. No-ops (does not throw or
     * queue) if a save is already in flight — see [saveInFlight].
     *
     * On [Resource.Success], sets [LocalState.saved] so [uiState] settles into
     * [EditBookUiState.Saved] (see class KDoc). On [Resource.Error] (a validation failure or DB
     * error), surfaces [Resource.Error.message] as [EditBookUiState.Ready.errorMessage] and clears
     * [LocalState.isSaving], so the user can correct their input and retry without losing the
     * form.
     */
    public fun save(
        title: String,
        releaseYear: Int?,
        purchasePrice: Double?,
        totalPages: Int?,
        format: BookFormat,
        status: ReadingStatus,
        trackingMode: TrackingMode,
    ) {
        if (saveInFlight) return
        saveInFlight = true
        localState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                when (
                    val result =
                        bookRepository.updateBookMetadata(
                            mediaId = bookId,
                            title = title,
                            releaseYear = releaseYear,
                            purchasePrice = purchasePrice,
                            totalPages = totalPages,
                            format = format,
                            status = status,
                            trackingMode = trackingMode,
                        )
                ) {
                    is Resource.Success -> localState.update { it.copy(isSaving = false, saved = true) }
                    is Resource.Error ->
                        localState.update {
                            it.copy(isSaving = false, errorMessage = result.message)
                        }
                }
            } finally {
                saveInFlight = false
            }
        }
    }
}
