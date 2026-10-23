package com.hub.media.ui

import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.domain.TmdbShowMapping

/**
 * The show form's values and rules (#214), written once for every screen that shows the form: Add
 * show by hand, the review after picking a TMDB result, and Edit show.
 *
 * Numeric fields are the strings being typed, for the reason [EditMovieUiState.Editing] gives: ""
 * and "0" are different claims ("unknown" vs. a real zero) that both have to survive the form.
 *
 * @property seasonRows The quick-fill rows of a show added by hand. Empty on Edit show, which has
 *   none, and on a TMDB review, whose seasons come whole from TMDB instead.
 */
public data class ShowFormState(
    val title: String = "",
    val releaseYear: String = "",
    val totalSeasons: String = "",
    val purchasePrice: String = "",
    val seasonRows: List<SeasonRow> = emptyList(),
) {
    /**
     * Appends an empty row pre-filled with the next unused season number: one past the highest
     * number currently typed (ignoring blank or unreadable ones), or `1` when there is none. That is
     * "next unused" only when seasons are filled in order -- a deliberate gap still has to be typed.
     */
    public fun addingSeasonRow(): ShowFormState {
        val next =
            seasonRows
                .mapNotNull { it.seasonNumber.trim().toIntOrNull() }
                .maxOrNull()
                ?.plus(1)
                ?: 1
        return copy(seasonRows = seasonRows + SeasonRow(seasonNumber = next.toString()))
    }

    /** Removes the row at [index]; ignored if [index] is no longer valid. */
    public fun removingSeasonRow(index: Int): ShowFormState =
        if (index !in seasonRows.indices) this else copy(seasonRows = seasonRows.filterIndexed { i, _ -> i != index })

    /** Changes the row at [index]; ignored if [index] is no longer valid. */
    public fun changingSeasonRow(
        index: Int,
        transform: (SeasonRow) -> SeasonRow,
    ): ShowFormState =
        if (index !in seasonRows.indices) {
            this
        } else {
            copy(seasonRows = seasonRows.mapIndexed { i, row -> if (i == index) transform(row) else row })
        }

    /**
     * The values a save sends, or a message naming what cannot be read.
     *
     * Blank show-level numbers are `null` ("unknown"); unreadable text is refused rather than sent
     * as `null`, naming the field -- [FilmFormState.parsed] explains the distinction. A season row is
     * different: its season number and episode count are **required**, because a blank one is a row
     * the user has not finished, not an unknown. Range and sign stay in `TVMetadataValidation`.
     */
    public fun parsed(): ShowFormParse {
        val releaseYear = parseOptionalNumber(releaseYear, String::toIntOrNull)
        val totalSeasons = parseOptionalNumber(totalSeasons, String::toIntOrNull)
        val purchasePrice = parseOptionalNumber(purchasePrice, String::toDoubleOrNull)
        val unreadableField =
            when {
                releaseYear == null -> "release year"
                totalSeasons == null -> "total seasons"
                purchasePrice == null -> "purchase price"
                else -> null
            }
        if (unreadableField != null) {
            return ShowFormParse.Unreadable("Enter a valid $unreadableField, or clear the field")
        }

        val seasons =
            seasonRows.mapIndexed { index, row ->
                SeasonQuickFill(
                    seasonNumber =
                        parseRequiredInt(row.seasonNumber)
                            ?: return ShowFormParse.Unreadable("Season row ${index + 1}: enter a season number"),
                    episodeCount =
                        parseRequiredInt(row.episodeCount)
                            ?: return ShowFormParse.Unreadable("Season row ${index + 1}: enter an episode count"),
                )
            }
        return ShowFormParse.Valid(
            title = title.trim(),
            releaseYear = releaseYear!!.value,
            totalSeasons = totalSeasons!!.value,
            purchasePrice = purchasePrice!!.value,
            seasons = seasons,
        )
    }
}

/** What [ShowFormState.parsed] found. */
public sealed interface ShowFormParse {
    public data class Valid(
        val title: String,
        val releaseYear: Int?,
        val totalSeasons: Int?,
        val purchasePrice: Double?,
        val seasons: List<SeasonQuickFill>,
    ) : ShowFormParse

    /** [message] is the save error every show form shows for it. */
    public data class Unreadable(
        val message: String,
    ) : ShowFormParse
}

/**
 * A TMDB show as a form to review (#214): what TMDB knows, with the price left for the user. No
 * quick-fill rows: the review's seasons come whole from TMDB, with episode titles the rows cannot
 * hold.
 */
public fun TmdbShowMapping.toShowForm(): ShowFormState =
    ShowFormState(
        title = title,
        releaseYear = releaseYear?.toString().orEmpty(),
        totalSeasons = totalSeasons?.toString().orEmpty(),
    )
