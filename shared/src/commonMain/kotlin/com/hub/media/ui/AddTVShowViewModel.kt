package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.util.Resource
import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.data.TVShowRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One season row on the add-show form: "Season [seasonNumber] has [episodeCount] episodes,"
 * carried as the strings the user is editing rather than parsed numbers — the same reasoning
 * [EditMovieUiState.Editing]'s KDoc gives for every numeric field on that form, extended to a field
 * that repeats per row.
 */
public data class SeasonRow(
    val seasonNumber: String = "",
    val episodeCount: String = "",
)

/**
 * The add-show form's current values (ROADMAP Task 13 Phase C).
 *
 * Numeric fields are strings for the reason [EditMovieUiState.Editing] documents: "" and "0" are
 * different claims ("unknown" vs. a real zero) that both have to survive a round trip through the
 * form.
 *
 * @property saveError Message from a rejected save, naming the offending field or season row, or
 *   `null`.
 * @property savedMediaId The new show's id once the save succeeds, so the caller can navigate to
 *   its detail screen. `null` until then.
 */
public data class AddTVShowUiState(
    val title: String = "",
    val releaseYear: String = "",
    val totalSeasons: String = "",
    val purchasePrice: String = "",
    val seasons: List<SeasonRow> = emptyList(),
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val savedMediaId: String? = null,
)

/**
 * Drives manual TV show entry with season quick-fill (ROADMAP Task 13 Phase C) — the TV
 * counterpart of [AddMovieViewModel], with one deliberate structural difference from it.
 *
 * ### Why this form's values live here, unlike [AddMovieScreen]
 * [AddMovieScreen]'s KDoc explains why *that* form keeps its field values in the composable via
 * `rememberSaveable` rather than in [AddMovieViewModel]: the form starts empty, so there is nothing
 * to load and `rememberSaveable` already carries typed text across rotation and process death,
 * which is the only durability requirement there. This form cannot make the same choice. The season
 * list is a dynamic collection of rows the user adds and removes one at a time — not a fixed set of
 * fields — and `rememberSaveable` cannot carry an arbitrary-length list of `data class` rows without
 * a hand-written `Saver` doing essentially the same bookkeeping this class already has to do to
 * validate the rows before saving. So the season list, and the rest of the form's fields alongside
 * it for one consistent home, live in [AddTVShowUiState] instead. This is a decision made once here
 * rather than drift from [AddMovieScreen]'s shape: a future screen for this form is stateless with
 * respect to save lifecycle exactly like [AddMovieScreen], but *is* hoisted for field values, where
 * [AddMovieScreen] is not.
 *
 * ### Validation
 * Range and sign rules are not duplicated here — [com.hub.media.features.tv.data.TVMetadataValidation]
 * inside [TVShowRepository] remains the only place that decides whether a *parsed* value is
 * acceptable. This class only decides whether the text the user typed can be read as a number at
 * all, per [EditMovieViewModel]'s `Parsed`/`parseOptional` split: blank is "unknown" and a legitimate
 * value to save for the show-level fields, but unreadable text is refused rather than silently
 * forwarded as `null`, naming the offending field.
 *
 * A season row is different from those show-level fields: [SeasonRow.seasonNumber] and
 * [SeasonRow.episodeCount] are **required**, not optional. A blank episode count is not "unknown
 * episode count" the way a blank release year is "unknown year" — it is a row the user has not
 * finished filling in, and [save] refuses it, naming which row.
 */
public class AddTVShowViewModel(
    private val tvShowRepository: TVShowRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AddTVShowUiState())
    public val uiState: StateFlow<AddTVShowUiState> = _uiState.asStateFlow()

    public fun onTitleChange(value: String): Unit = updateForm { it.copy(title = value) }

    public fun onReleaseYearChange(value: String): Unit = updateForm { it.copy(releaseYear = value) }

    public fun onTotalSeasonsChange(value: String): Unit = updateForm { it.copy(totalSeasons = value) }

    public fun onPurchasePriceChange(value: String): Unit = updateForm { it.copy(purchasePrice = value) }

    /**
     * Appends a new, empty season row, pre-filled with the next unused season number: one past the
     * highest season number currently typed on the form (ignoring rows whose season number is blank
     * or unreadable), or `1` when there are no rows yet. That is "next unused" only in the common
     * case of filling seasons in order — a user who deliberately leaves a gap and wants it filled
     * still has to type the number themselves, which [onSeasonNumberChange] supports.
     */
    public fun addSeasonRow() {
        val current = _uiState.value
        val nextSeasonNumber =
            current.seasons
                .mapNotNull { it.seasonNumber.trim().toIntOrNull() }
                .maxOrNull()
                ?.plus(1)
                ?: 1
        _uiState.value =
            current.copy(
                seasons = current.seasons + SeasonRow(seasonNumber = nextSeasonNumber.toString()),
            )
    }

    /** Removes the season row at [index]. Silently ignored if [index] is no longer valid. */
    public fun removeSeasonRow(index: Int) {
        val current = _uiState.value
        if (index !in current.seasons.indices) return
        _uiState.value = current.copy(seasons = current.seasons.filterIndexed { i, _ -> i != index })
    }

    public fun onSeasonNumberChange(
        index: Int,
        value: String,
    ) {
        updateSeasonRow(index) { it.copy(seasonNumber = value) }
    }

    public fun onEpisodeCountChange(
        index: Int,
        value: String,
    ) {
        updateSeasonRow(index) { it.copy(episodeCount = value) }
    }

    /**
     * Validates and saves the show and its quick-filled seasons.
     *
     * Unreadable text in [AddTVShowUiState.title]/`releaseYear`/`totalSeasons`/`purchasePrice`, or a
     * season row missing or unable to parse its season number or episode count, is refused with a
     * message naming the field or row — nothing is saved in that case. A second call while
     * [AddTVShowUiState.isSaving] is `true` is ignored, so a double-tapped save cannot create the
     * same show twice.
     */
    public fun save() {
        val current = _uiState.value
        if (current.isSaving) return

        val releaseYear = parseOptionalTvField(current.releaseYear, String::toIntOrNull)
        val totalSeasons = parseOptionalTvField(current.totalSeasons, String::toIntOrNull)
        val purchasePrice = parseOptionalTvField(current.purchasePrice, String::toDoubleOrNull)
        if (releaseYear == null || totalSeasons == null || purchasePrice == null) {
            val unreadableField =
                when {
                    releaseYear == null -> "release year"
                    totalSeasons == null -> "total seasons"
                    else -> "purchase price"
                }
            _uiState.value = current.copy(saveError = "Enter a valid $unreadableField, or clear the field")
            return
        }

        val seasons = mutableListOf<SeasonQuickFill>()
        current.seasons.forEachIndexed { index, row ->
            val seasonNumber =
                parseRequiredInt(row.seasonNumber)
                    ?: run {
                        _uiState.value = current.copy(saveError = "Season row ${index + 1}: enter a season number")
                        return
                    }
            val episodeCount =
                parseRequiredInt(row.episodeCount)
                    ?: run {
                        _uiState.value = current.copy(saveError = "Season row ${index + 1}: enter an episode count")
                        return
                    }
            seasons += SeasonQuickFill(seasonNumber = seasonNumber, episodeCount = episodeCount)
        }

        _uiState.value = current.copy(isSaving = true, saveError = null)
        viewModelScope.launch {
            val result =
                tvShowRepository.addShow(
                    title = current.title.trim(),
                    releaseYear = releaseYear.value,
                    purchasePrice = purchasePrice.value,
                    totalSeasons = totalSeasons.value,
                    seasons = seasons,
                )
            val latest = _uiState.value
            _uiState.value =
                when (result) {
                    is Resource.Success -> latest.copy(isSaving = false, savedMediaId = result.data)
                    is Resource.Error -> latest.copy(isSaving = false, saveError = result.message)
                }
        }
    }

    /**
     * Acknowledges a terminal signal (a save error shown, or a saved id already navigated on) so it
     * is not repeated on the next recomposition.
     *
     * Unlike [AddMovieViewModel.reset], which returns the whole form to `Idle` because the
     * composable owns the field values there, this clears only [AddTVShowUiState.saveError] and
     * [AddTVShowUiState.savedMediaId] and leaves every typed field untouched — this class is the
     * only owner of them (see the class KDoc), so wiping them here would discard whatever the user
     * was correcting when an error appeared.
     */
    public fun reset() {
        _uiState.value = _uiState.value.copy(isSaving = false, saveError = null, savedMediaId = null)
    }

    private inline fun updateForm(transform: (AddTVShowUiState) -> AddTVShowUiState) {
        _uiState.value = transform(_uiState.value)
    }

    private inline fun updateSeasonRow(
        index: Int,
        transform: (SeasonRow) -> SeasonRow,
    ) {
        val current = _uiState.value
        if (index !in current.seasons.indices) return
        _uiState.value =
            current.copy(
                seasons = current.seasons.mapIndexed { i, row -> if (i == index) transform(row) else row },
            )
    }
}

/**
 * A numeric form field that was read successfully. [value] is `null` when the field was blank —
 * "unknown", which is a legitimate thing to save. See [EditMovieViewModel]'s copy of this type for
 * the full rationale; duplicated here (under a distinct name -- two file-private top-level classes
 * sharing a name in the same package collide at the JVM class-file level despite both being
 * `private`) rather than shared, because it is `private` there too.
 */
private class ParsedTvField<T : Any>(
    val value: T?,
)

/**
 * Reads an optional numeric field: [ParsedTvField] with a `null` [ParsedTvField.value] for blank
 * text, [ParsedTvField] with the number for text that parses, and `null` for text that does not
 * parse at all — the third case being the one a plain `toIntOrNull()`/`toDoubleOrNull()` cannot
 * express on its own.
 */
private fun <T : Any> parseOptionalTvField(
    text: String,
    parse: (String) -> T?,
): ParsedTvField<T>? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ParsedTvField(null)
    return parse(trimmed)?.let { ParsedTvField(it) }
}

/**
 * Reads a *required* numeric field for one season row: `null` for both blank text and text that
 * fails to parse. Unlike [parseOptionalTvField], a season row has no legitimate "unknown" value to
 * fall back to — a blank episode count is an unfinished row, not a known-absent one — so both
 * failure shapes collapse to the same "not usable" outcome here.
 */
private fun parseRequiredInt(text: String): Int? = text.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
