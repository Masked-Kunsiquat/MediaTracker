package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.util.Resource
import com.hub.media.core.util.catchingNonCancellation
import com.hub.media.features.movies.data.MovieRepository
import com.hub.media.features.tv.domain.WatchedWhen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** State of the edit-movie form (ROADMAP Task 13 Phase B). */
public sealed class EditMovieUiState {
    /** The movie's current values are still being read. */
    public data object Loading : EditMovieUiState()

    /** No movie with this id — deleted, or never a movie. */
    public data object NotFound : EditMovieUiState()

    /**
     * The film's current values could not be read -- the database threw. Offered as Retry or Back
     * rather than left as an endless spinner or an app crash.
     */
    public data object LoadFailed : EditMovieUiState()

    /**
     * The form, pre-filled with the movie's current values.
     *
     * The field values and their rules are the shared [FilmFormState] (#214), the same one Add film
     * and the TMDB review hold, so the three forms cannot drift apart.
     *
     * @property saveError Message from a rejected save, or null.
     * @property saved True once the write succeeded, so the caller can leave the screen.
     */
    public data class Editing(
        val form: FilmFormState,
        /**
         * The header's title (#174): the title as loaded, so the header keeps saying which item is
         * being edited while the title field changes underneath it. No field edit writes it.
         */
        val savedTitle: String = form.title,
        /** The release year as loaded, for the header's kind line. */
        val savedYear: Int? = null,
        /** The cover shown in the header; `null` when the item has none. */
        val coverImageHash: String? = null,
        val isSaving: Boolean = false,
        val saveError: String? = null,
        val saved: Boolean = false,
    ) : EditMovieUiState()
}

/**
 * Drives correcting a movie's metadata (ROADMAP Task 13 Phase B) — the movie counterpart of
 * [EditBookViewModel].
 *
 * ### Why this exists rather than being deferred
 * A book's wrong metadata can be re-fetched from a provider; a manually-entered movie has no source
 * but the person who typed it. Without an edit path the only way to fix a mistyped runtime would be
 * to delete the movie and add it again, which discards its id and anything attached to it. Edit is
 * the correction mechanism for manual entry, not a convenience on top of one.
 *
 * Values are loaded **once** into editable state rather than observed continuously: a form that
 * re-read the database on every emission would overwrite whatever the user was halfway through
 * typing if anything else touched the row.
 */
public class EditMovieViewModel(
    private val movieId: String,
    private val movieRepository: MovieRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<EditMovieUiState>(EditMovieUiState.Loading)
    public val uiState: StateFlow<EditMovieUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /**
     * Reads the film again after [EditMovieUiState.LoadFailed]. A no-op in any other state, so a stray tap
     * cannot reload a form the user is part-way through editing.
     */
    public fun retry() {
        if (_uiState.value !is EditMovieUiState.LoadFailed) return
        _uiState.value = EditMovieUiState.Loading
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val movie =
                catchingNonCancellation(
                    block = {
                        movieRepository.observeMovieDetail(movieId).first()
                    },
                    onException = {
                        // Uncaught, this would escape viewModelScope and crash the app.
                        _uiState.value = EditMovieUiState.LoadFailed
                        return@launch
                    },
                )
            _uiState.value =
                if (movie == null) {
                    EditMovieUiState.NotFound
                } else {
                    EditMovieUiState.Editing(
                        form =
                            FilmFormState(
                                title = movie.item.title,
                                // Blank rather than "null" or "0" for an unknown value, so the field
                                // shows empty and saving it back unchanged keeps it unknown.
                                releaseYear =
                                    movie.item.releaseYear
                                        ?.toString()
                                        .orEmpty(),
                                runtimeMinutes =
                                    movie.details
                                        ?.runtimeMinutes
                                        ?.toString()
                                        .orEmpty(),
                                purchasePrice =
                                    movie.item.purchasePrice
                                        ?.toString()
                                        .orEmpty(),
                                status = movie.details?.status ?: WatchStatus.WATCHLIST,
                            ),
                        savedTitle = movie.item.title,
                        savedYear = movie.item.releaseYear,
                        coverImageHash = movie.item.coverImageHash,
                    )
                }
        }
    }

    public fun onTitleChange(value: String): Unit = updateFields { it.copy(title = value) }

    public fun onReleaseYearChange(value: String): Unit = updateFields { it.copy(releaseYear = value) }

    public fun onRuntimeChange(value: String): Unit = updateFields { it.copy(runtimeMinutes = value) }

    public fun onPurchasePriceChange(value: String): Unit = updateFields { it.copy(purchasePrice = value) }

    /** See [FilmFormState.choosingStatus]: choosing Watched asks when first ([confirmWatched]). */
    public fun onStatusChange(value: WatchStatus): Unit = updateFields { it.choosingStatus(value) }

    public fun confirmWatched(watchedWhen: WatchedWhen): Unit = updateFields { it.confirmingWatched(watchedWhen) }

    public fun dismissWatched(): Unit = updateFields { it.dismissingWatched() }

    /**
     * Writes the edited values, or reports the field that cannot be read (see
     * [FilmFormState.parsed] on why unreadable is refused rather than saved as "unknown").
     */
    public fun save() {
        val editing = _uiState.value as? EditMovieUiState.Editing ?: return
        if (editing.isSaving) return

        val values =
            when (val parsed = editing.form.parsed()) {
                is FilmFormParse.Unreadable -> {
                    _uiState.value = editing.copy(saveError = parsed.message())
                    return
                }
                is FilmFormParse.Valid -> parsed
            }

        _uiState.value = editing.copy(isSaving = true, saveError = null)
        viewModelScope.launch {
            val result =
                movieRepository.updateMovieMetadata(
                    mediaId = movieId,
                    title = values.title,
                    releaseYear = values.releaseYear,
                    purchasePrice = values.purchasePrice,
                    runtimeMinutes = values.runtimeMinutes,
                    status = values.status,
                    watchedWhen = values.watchedWhen,
                )
            val current = _uiState.value as? EditMovieUiState.Editing ?: return@launch
            _uiState.value =
                when (result) {
                    is Resource.Success -> current.copy(isSaving = false, saved = true)
                    is Resource.Error -> current.copy(isSaving = false, saveError = result.message)
                }
        }
    }

    /** Acknowledges a save error once shown, so it is not repeated on the next recomposition. */
    public fun consumeSaveError(): Unit = updateForm { it.copy(saveError = null) }

    private inline fun updateForm(transform: (EditMovieUiState.Editing) -> EditMovieUiState.Editing) {
        val current = _uiState.value as? EditMovieUiState.Editing ?: return
        _uiState.value = transform(current)
    }

    private inline fun updateFields(transform: (FilmFormState) -> FilmFormState): Unit =
        updateForm { it.copy(form = transform(it.form)) }
}
