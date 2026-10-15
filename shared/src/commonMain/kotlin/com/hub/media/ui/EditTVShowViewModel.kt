package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.util.Resource
import com.hub.media.core.util.catchingNonCancellation
import com.hub.media.features.tv.data.TVShowRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** State of the edit-show form (#173). */
public sealed class EditTVShowUiState {
    /** The show's current values are still being read. */
    public data object Loading : EditTVShowUiState()

    /** No show with this id -- deleted, or never a show. */
    public data object NotFound : EditTVShowUiState()

    /**
     * The show's current values could not be read -- the database threw. Offered as Retry or Back
     * rather than left as an endless spinner or an app crash.
     */
    public data object LoadFailed : EditTVShowUiState()

    /**
     * The form, pre-filled with the show's current values. Numbers are the strings being edited,
     * for the reason [EditMovieUiState.Editing] gives: blank ("unknown") and an in-progress number
     * must both survive the round trip.
     *
     * There is no status here: abandoning is the detail screen's toggle, and the save never writes
     * status (see [TVShowRepository.updateShowMetadata]).
     */
    public data class Editing(
        val title: String,
        val releaseYear: String,
        val totalSeasons: String,
        val purchasePrice: String,
        /**
         * The header's title (#174): the title as loaded, so the header keeps saying which item is
         * being edited while [title] changes underneath it. No field edit writes it.
         */
        val savedTitle: String = title,
        /** The release year as loaded, for the header's kind line. */
        val savedYear: Int? = null,
        /** The cover shown in the header; `null` when the item has none. */
        val coverImageHash: String? = null,
        val isSaving: Boolean = false,
        val saveError: String? = null,
        val saved: Boolean = false,
    ) : EditTVShowUiState()
}

/**
 * Drives correcting a TV show's title, year, total seasons and price (#173) -- the show counterpart
 * of [EditMovieViewModel]. A hand-entered show has no provider to correct a typo from, so this is
 * the only way to fix one.
 *
 * Values are loaded once, not observed: a form that re-read the database on every emission would
 * overwrite what the user is typing whenever anything else touched the row.
 */
public class EditTVShowViewModel(
    private val showId: String,
    private val tvShowRepository: TVShowRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<EditTVShowUiState>(EditTVShowUiState.Loading)
    public val uiState: StateFlow<EditTVShowUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /**
     * Reads the show again after [EditTVShowUiState.LoadFailed]. A no-op in any other state, so a stray tap
     * cannot reload a form the user is part-way through editing.
     */
    public fun retry() {
        if (_uiState.value !is EditTVShowUiState.LoadFailed) return
        _uiState.value = EditTVShowUiState.Loading
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val show =
                catchingNonCancellation(
                    block = {
                        tvShowRepository.observeShowDetail(showId).first()
                    },
                    onException = {
                        // Uncaught, this would escape viewModelScope and crash the app.
                        _uiState.value = EditTVShowUiState.LoadFailed
                        return@launch
                    },
                )
            _uiState.value =
                if (show == null) {
                    EditTVShowUiState.NotFound
                } else {
                    EditTVShowUiState.Editing(
                        title = show.item.title,
                        // Blank rather than "null" or "0" for an unknown value, so saving it back
                        // unchanged keeps it unknown.
                        releaseYear =
                            show.item.releaseYear
                                ?.toString()
                                .orEmpty(),
                        totalSeasons =
                            show.details
                                ?.totalSeasons
                                ?.toString()
                                .orEmpty(),
                        purchasePrice =
                            show.item.purchasePrice
                                ?.toString()
                                .orEmpty(),
                        savedTitle = show.item.title,
                        savedYear = show.item.releaseYear,
                        coverImageHash = show.item.coverImageHash,
                    )
                }
        }
    }

    public fun onTitleChange(value: String): Unit = updateForm { it.copy(title = value) }

    public fun onReleaseYearChange(value: String): Unit = updateForm { it.copy(releaseYear = value) }

    public fun onTotalSeasonsChange(value: String): Unit = updateForm { it.copy(totalSeasons = value) }

    public fun onPurchasePriceChange(value: String): Unit = updateForm { it.copy(purchasePrice = value) }

    /**
     * Writes the edited values. Blank numbers go as `null` ("unknown"); text that does not parse is
     * refused rather than sent as `null`, which would erase the value the user came to correct --
     * [EditMovieViewModel.save] explains the distinction. Range rules stay in
     * [com.hub.media.features.tv.data.TVMetadataValidation].
     */
    public fun save() {
        val form = _uiState.value as? EditTVShowUiState.Editing ?: return
        if (form.isSaving) return

        val releaseYear = parseOptionalNumber(form.releaseYear, String::toIntOrNull)
        val totalSeasons = parseOptionalNumber(form.totalSeasons, String::toIntOrNull)
        val purchasePrice = parseOptionalNumber(form.purchasePrice, String::toDoubleOrNull)
        if (releaseYear == null || totalSeasons == null || purchasePrice == null) {
            val unreadableField =
                when {
                    releaseYear == null -> "release year"
                    totalSeasons == null -> "total seasons"
                    else -> "purchase price"
                }
            _uiState.value = form.copy(saveError = "Enter a valid $unreadableField, or clear the field")
            return
        }

        _uiState.value = form.copy(isSaving = true, saveError = null)
        viewModelScope.launch {
            val result =
                tvShowRepository.updateShowMetadata(
                    mediaId = showId,
                    title = form.title.trim(),
                    releaseYear = releaseYear.value,
                    purchasePrice = purchasePrice.value,
                    totalSeasons = totalSeasons.value,
                )
            val current = _uiState.value as? EditTVShowUiState.Editing ?: return@launch
            _uiState.value =
                when (result) {
                    is Resource.Success -> current.copy(isSaving = false, saved = true)
                    is Resource.Error -> current.copy(isSaving = false, saveError = result.message)
                }
        }
    }

    /** Acknowledges a save error once shown, so it is not repeated on the next recomposition. */
    public fun consumeSaveError(): Unit = updateForm { it.copy(saveError = null) }

    private inline fun updateForm(transform: (EditTVShowUiState.Editing) -> EditTVShowUiState.Editing) {
        val current = _uiState.value as? EditTVShowUiState.Editing ?: return
        // Refused while saving: the save already captured the form, so a change now would be shown
        // as saved without having been.
        if (current.isSaving) return
        _uiState.value = transform(current)
    }
}
