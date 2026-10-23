package com.hub.media.ui

import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.features.movies.domain.TmdbMovieMapping
import com.hub.media.features.tv.domain.WatchedWhen

/**
 * The film form's values and rules (#214), written once for every screen that shows the form: Add
 * film by hand, the review after picking a TMDB result, and Edit film.
 *
 * Numeric fields are the strings being typed, for the reason [EditMovieUiState.Editing] gives: ""
 * and "0" are different claims ("unknown" vs. a real zero) that both have to survive the form.
 *
 * @property initialStatus The status the film had when the form opened: choosing Watched asks when
 *   (#192) only if it was not already Watched. A new film opens at [WatchStatus.WATCHLIST].
 * @property pendingWatched Choosing Watched is waiting on the "when?" prompt.
 * @property watchedWhen The prompt's answer, sent with the save.
 */
public data class FilmFormState(
    val title: String = "",
    val releaseYear: String = "",
    val runtimeMinutes: String = "",
    val purchasePrice: String = "",
    val status: WatchStatus = WatchStatus.WATCHLIST,
    val initialStatus: WatchStatus = status,
    val pendingWatched: Boolean = false,
    val watchedWhen: WatchedWhen? = null,
) {
    /**
     * Choosing Watched for a film that was not Watched when the form opened asks when first
     * ([confirmingWatched]); the selection only changes once answered. Any other choice applies at once.
     */
    public fun choosingStatus(value: WatchStatus): FilmFormState =
        if (value == WatchStatus.WATCHED && initialStatus != WatchStatus.WATCHED) {
            copy(pendingWatched = true)
        } else {
            copy(status = value, watchedWhen = null)
        }

    public fun confirmingWatched(watchedWhen: WatchedWhen): FilmFormState =
        copy(status = WatchStatus.WATCHED, watchedWhen = watchedWhen, pendingWatched = false)

    public fun dismissingWatched(): FilmFormState = copy(pendingWatched = false)

    /**
     * The values a save sends, or the first field that cannot be read.
     *
     * Blank numeric fields are `null` ("unknown"), never `0`. Text that fails to parse is **not** the
     * same claim as blank and is reported rather than sent as `null`: a plain `toIntOrNull()`
     * collapses "the user cleared this field" and "the user typed something this can't read" into the
     * same value, so an unreadable entry would silently erase the number the user meant. Range and
     * sign are not checked here -- `MovieMetadataValidation` inside the repository remains the only
     * place that decides whether a *parsed* value is acceptable.
     */
    public fun parsed(): FilmFormParse {
        val releaseYear = parseOptionalNumber(releaseYear, String::toIntOrNull)
        val runtimeMinutes = parseOptionalNumber(runtimeMinutes, String::toIntOrNull)
        val purchasePrice = parseOptionalNumber(purchasePrice, String::toDoubleOrNull)
        return when {
            releaseYear == null -> FilmFormParse.Unreadable("release year")
            runtimeMinutes == null -> FilmFormParse.Unreadable("runtime")
            purchasePrice == null -> FilmFormParse.Unreadable("purchase price")
            else ->
                FilmFormParse.Valid(
                    title = title.trim(),
                    releaseYear = releaseYear.value,
                    runtimeMinutes = runtimeMinutes.value,
                    purchasePrice = purchasePrice.value,
                    status = status,
                    watchedWhen = watchedWhen,
                )
        }
    }
}

/** What [FilmFormState.parsed] found. */
public sealed interface FilmFormParse {
    public data class Valid(
        val title: String,
        val releaseYear: Int?,
        val runtimeMinutes: Int?,
        val purchasePrice: Double?,
        val status: WatchStatus,
        val watchedWhen: WatchedWhen?,
    ) : FilmFormParse

    /** [field] is the name a save error uses: "release year", "runtime" or "purchase price". */
    public data class Unreadable(
        val field: String,
    ) : FilmFormParse {
        /** The save error every film form shows for this field. */
        public fun message(): String = "Enter a valid $field, or clear the field"
    }
}

/** A TMDB film as a form to review (#214): what TMDB knows, with the price left for the user. */
public fun TmdbMovieMapping.toFilmForm(): FilmFormState =
    FilmFormState(
        title = title,
        releaseYear = releaseYear?.toString().orEmpty(),
        runtimeMinutes = runtimeMinutes?.toString().orEmpty(),
    )
