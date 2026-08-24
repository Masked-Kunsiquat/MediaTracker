package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.util.Resource
import com.hub.media.features.movies.data.MovieRepository
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
     * The form, pre-filled with the movie's current values.
     *
     * Numeric fields are carried as the strings the user is editing rather than as parsed numbers,
     * because "" and "0" are different claims that both have to survive a round trip through the
     * form: empty means "unknown", and re-parsing on every keystroke would make an in-progress "1"
     * of "116" indistinguishable from a finished one.
     *
     * @property saveError Message from a rejected save, or null.
     * @property saved True once the write succeeded, so the caller can leave the screen.
     */
    public data class Editing(
        val title: String,
        val releaseYear: String,
        val runtimeMinutes: String,
        val purchasePrice: String,
        val status: WatchStatus,
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
        viewModelScope.launch {
            val movie = movieRepository.observeMovieDetail(movieId).first()
            _uiState.value =
                if (movie == null) {
                    EditMovieUiState.NotFound
                } else {
                    EditMovieUiState.Editing(
                        title = movie.item.title,
                        // Blank rather than "null" or "0" for an unknown value, so the field shows
                        // empty and saving it back unchanged keeps it unknown.
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
                    )
                }
        }
    }

    public fun onTitleChange(value: String): Unit = updateForm { it.copy(title = value) }

    public fun onReleaseYearChange(value: String): Unit = updateForm { it.copy(releaseYear = value) }

    public fun onRuntimeChange(value: String): Unit = updateForm { it.copy(runtimeMinutes = value) }

    public fun onPurchasePriceChange(value: String): Unit = updateForm { it.copy(purchasePrice = value) }

    public fun onStatusChange(value: WatchStatus): Unit = updateForm { it.copy(status = value) }

    /**
     * Writes the edited values.
     *
     * Blank numeric fields are sent as `null` ("unknown"), never `0`. Text that fails to parse is
     * **not** the same claim as blank and is refused rather than sent as `null`: a plain
     * `toIntOrNull()` collapses "the user cleared this field" and "the user typed something this
     * can't read" into the same value, so an unreadable entry would silently erase the very number
     * the user opened this screen to correct.
     *
     * Range and sign rules are still not duplicated here —
     * [com.hub.media.features.movies.data.MovieMetadataValidation] inside the repository remains the
     * only place that decides whether a *parsed* value is acceptable, so this class never becomes a
     * second copy of those rules.
     */
    public fun save() {
        val form = _uiState.value as? EditMovieUiState.Editing ?: return
        if (form.isSaving) return

        val releaseYear = parseOptionalNumber(form.releaseYear, String::toIntOrNull)
        val runtimeMinutes = parseOptionalNumber(form.runtimeMinutes, String::toIntOrNull)
        val purchasePrice = parseOptionalNumber(form.purchasePrice, String::toDoubleOrNull)
        if (releaseYear == null || runtimeMinutes == null || purchasePrice == null) {
            val unreadableField =
                when {
                    releaseYear == null -> "release year"
                    runtimeMinutes == null -> "runtime"
                    else -> "purchase price"
                }
            _uiState.value = form.copy(saveError = "Enter a valid $unreadableField, or clear the field")
            return
        }

        _uiState.value = form.copy(isSaving = true, saveError = null)
        viewModelScope.launch {
            val result =
                movieRepository.updateMovieMetadata(
                    mediaId = movieId,
                    title = form.title.trim(),
                    releaseYear = releaseYear.value,
                    purchasePrice = purchasePrice.value,
                    runtimeMinutes = runtimeMinutes.value,
                    status = form.status,
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
}
