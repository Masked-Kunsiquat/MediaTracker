package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.util.Resource
import com.hub.media.features.movies.data.MovieRepository
import com.hub.media.features.tv.domain.WatchedWhen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Outcome of an add-movie attempt (ROADMAP Task 13 Phase B).
 *
 * Deliberately smaller than [AddBookUiState]: there is no provider lookup here, so there is no
 * searching, no resolving, and no partial-metadata state to represent. Manual entry either
 * validates and saves, or reports why it did not.
 */
public sealed class AddMovieUiState {
    /** Nothing attempted yet, or the form was reset after a save. */
    public data object Idle : AddMovieUiState()

    /** A save is in flight. The form disables its submit control while this is current. */
    public data object Saving : AddMovieUiState()

    /** Saved. [mediaId] is the new movie, so the caller can navigate straight to its detail screen. */
    public data class Saved(
        val mediaId: String,
    ) : AddMovieUiState()

    /** The save was rejected or failed. [message] is already user-facing. */
    public data class Error(
        val message: String,
    ) : AddMovieUiState()
}

/**
 * Drives manual movie entry (ROADMAP Task 13 Phase B).
 *
 * Validation is **not** duplicated here. The form gates its submit button on cheap local checks,
 * but the authoritative rules live in
 * [com.hub.media.features.movies.data.MovieMetadataValidation] and run inside
 * [MovieRepository.addMovie] — so a value the UI's gate lets through (the classic being
 * `"Infinity"` typed into a numeric field, which parses to a valid [Double]) is still rejected
 * before it reaches the database. Anything this class rejected independently would be a second,
 * drifting copy of those rules.
 */
public class AddMovieViewModel(
    private val movieRepository: MovieRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<AddMovieUiState>(AddMovieUiState.Idle)
    public val uiState: StateFlow<AddMovieUiState> = _uiState.asStateFlow()

    /**
     * The form's values (#214). Held here rather than in the composable so the add form and the
     * TMDB review render the one shared form from the same [FilmFormState] Edit film holds. The cost
     * is the one [AddTVShowViewModel] already pays: typed text survives rotation with this class, but
     * not process death, which `rememberSaveable` used to cover.
     */
    private val _form = MutableStateFlow(FilmFormState())
    public val form: StateFlow<FilmFormState> = _form.asStateFlow()

    public fun onTitleChange(value: String): Unit = _form.update { it.copy(title = value) }

    public fun onReleaseYearChange(value: String): Unit = _form.update { it.copy(releaseYear = value) }

    public fun onRuntimeChange(value: String): Unit = _form.update { it.copy(runtimeMinutes = value) }

    public fun onPurchasePriceChange(value: String): Unit = _form.update { it.copy(purchasePrice = value) }

    /** See [FilmFormState.choosingStatus]: choosing Watched asks when first ([confirmWatched]). */
    public fun onStatusChange(value: WatchStatus): Unit = _form.update { it.choosingStatus(value) }

    public fun confirmWatched(watchedWhen: WatchedWhen): Unit = _form.update { it.confirmingWatched(watchedWhen) }

    public fun dismissWatched(): Unit = _form.update { it.dismissingWatched() }

    /**
     * Validates and saves the form's movie.
     *
     * Blank numeric fields are "unknown", not zero -- an empty runtime box means the runtime is not
     * known, which is a different claim from a zero-minute film and is stored as `null`
     * accordingly. A field that cannot be read is reported rather than saved as unknown (see
     * [FilmFormState.parsed]).
     *
     * A second call while [AddMovieUiState.Saving] is current is ignored rather than queued, so a
     * double-tapped save cannot create the same movie twice.
     */
    public fun save() {
        // Two states are refused, not one: a save in flight, and a save that has already produced a
        // movie and not yet been reset. See AddTVShowViewModel.save's KDoc -- the in-flight half
        // alone holds only for as long as the write takes, so a fast save leaves the form armed to
        // create a second, identical movie on the next tap. The screen navigates away when Saved
        // appears, which hides that window rather than closing it.
        if (_uiState.value is AddMovieUiState.Saving || _uiState.value is AddMovieUiState.Saved) return
        val values =
            when (val parsed = _form.value.parsed()) {
                is FilmFormParse.Unreadable -> {
                    _uiState.value = AddMovieUiState.Error(parsed.message())
                    return
                }
                is FilmFormParse.Valid -> parsed
            }
        _uiState.value = AddMovieUiState.Saving
        viewModelScope.launch {
            _uiState.value =
                when (
                    val result =
                        movieRepository.addMovie(
                            title = values.title,
                            releaseYear = values.releaseYear,
                            purchasePrice = values.purchasePrice,
                            runtimeMinutes = values.runtimeMinutes,
                            status = values.status,
                            watchedWhen = values.watchedWhen,
                        )
                ) {
                    is Resource.Success -> AddMovieUiState.Saved(result.data)
                    is Resource.Error -> AddMovieUiState.Error(result.message)
                }
        }
    }

    /**
     * Clears a terminal state so the form can be used again.
     *
     * Needed for the error case in particular: without it a rejected save would leave the message
     * on screen while the user edits the offending field, with no way to tell the stale complaint
     * apart from a fresh one.
     */
    public fun reset() {
        _uiState.value = AddMovieUiState.Idle
    }
}
