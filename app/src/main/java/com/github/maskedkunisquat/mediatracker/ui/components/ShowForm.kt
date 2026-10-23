package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.text.filterDecimalInput
import com.github.maskedkunisquat.mediatracker.ui.text.toDisplayDecimal
import com.hub.media.features.tv.data.TVMetadataValidation
import com.hub.media.ui.SeasonRow
import com.hub.media.ui.ShowFormState
import com.hub.media.ui.filterIntegerInput
import com.hub.media.ui.parseOptionalNumber
import com.hub.media.ui.parseRequiredInt

/**
 * Which of a show form's fields hold something a save would refuse (#214) -- the show counterpart
 * of [FilmFieldValidity], with the same rule: blank show-level numbers are fine ("unknown"), and a
 * non-blank one must parse and pass the [TVMetadataValidation] check the repository runs, called
 * rather than copied. A quick-fill row's two numbers are required (see [ShowFormState.parsed]).
 */
internal class ShowFieldValidity(
    form: ShowFormState,
) {
    val title: Boolean = form.title.isNotBlank()
    val releaseYear: Boolean =
        parseOptionalNumber(form.releaseYear, String::toIntOrNull)
            ?.let { TVMetadataValidation.validateReleaseYear(it.value) == null } == true
    val totalSeasons: Boolean =
        parseOptionalNumber(form.totalSeasons, String::toIntOrNull)
            ?.let { TVMetadataValidation.validateTotalSeasons(it.value) == null } == true
    val purchasePrice: Boolean = parseOptionalNumber(form.purchasePrice, String::toDoubleOrNull) != null
    val seasonRows: Boolean =
        form.seasonRows.all { parseRequiredInt(it.seasonNumber) != null && parseRequiredInt(it.episodeCount) != null }

    /** Every field savable. Clearing a show-level number is legitimate and never blocks saving. */
    val canSave: Boolean = title && releaseYear && totalSeasons && purchasePrice && seasonRows
}

/**
 * The show form (#214), one copy for every screen that shows it: Add show by hand, the review after
 * picking a TMDB result, and Edit show. [header] first; then the Details card, the [seasons] card
 * when there is one, and the Purchase card.
 *
 * [flagBlankTitle] is false on a form that opened empty: a blank title there is not yet a mistake,
 * and the disabled Save already says it is required. Where a title was loaded, a blank one means it
 * was cleared, and the field says so.
 */
@Composable
fun ColumnScope.ShowFormContent(
    form: ShowFormState,
    header: @Composable () -> Unit,
    enabled: Boolean,
    flagBlankTitle: Boolean,
    onTitleChange: (String) -> Unit,
    onReleaseYearChange: (String) -> Unit,
    onTotalSeasonsChange: (String) -> Unit,
    onPurchasePriceChange: (String) -> Unit,
    seasons: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val valid = ShowFieldValidity(form)
    header()
    FormSection(title = stringResource(R.string.edit_section_details)) {
        OutlinedTextField(
            value = form.title,
            onValueChange = onTitleChange,
            label = { Text(stringResource(R.string.add_tv_show_field_title)) },
            singleLine = true,
            isError = flagBlankTitle && !valid.title,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.releaseYear,
            onValueChange = { onReleaseYearChange(it.filterIntegerInput()) },
            label = { Text(stringResource(R.string.add_tv_show_field_year)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !valid.releaseYear,
            supportingText =
                if (!valid.releaseYear) {
                    {
                        Text(
                            stringResource(
                                R.string.edit_release_year_invalid_error,
                                TVMetadataValidation.MIN_RELEASE_YEAR,
                                TVMetadataValidation.MAX_RELEASE_YEAR,
                            ),
                        )
                    }
                } else {
                    null
                },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.totalSeasons,
            onValueChange = { onTotalSeasonsChange(it.filterIntegerInput()) },
            label = { Text(stringResource(R.string.add_tv_show_field_total_seasons)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !valid.totalSeasons,
            supportingText =
                if (!valid.totalSeasons) {
                    { Text(stringResource(R.string.add_tv_show_total_seasons_invalid_error)) }
                } else {
                    null
                },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (seasons != null) {
        FormSection(title = stringResource(R.string.add_tv_show_seasons_section_label), content = seasons)
    }
    FormSection(title = stringResource(R.string.edit_section_purchase)) {
        OutlinedTextField(
            value = form.purchasePrice.toDisplayDecimal(),
            // Filtered as the film form does: an unfiltered box accepts "12,50" or "1.2.3", neither of
            // which toDoubleOrNull can read.
            onValueChange = { onPurchasePriceChange(it.filterDecimalInput()) },
            label = { Text(stringResource(R.string.add_tv_show_field_price)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = !valid.purchasePrice,
            supportingText =
                if (!valid.purchasePrice) {
                    { Text(stringResource(R.string.edit_purchase_price_invalid_error)) }
                } else {
                    null
                },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The quick-fill rows of a show added by hand (ROADMAP Task 13 Phase C): one season number and
 * episode count per row, a remove control on each, and "Add season" below. The Seasons card's body
 * on Add show; a TMDB review has whole seasons from TMDB instead, and Edit show has neither.
 */
@Composable
fun SeasonQuickFillRows(
    rows: List<SeasonRow>,
    enabled: Boolean,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onSeasonNumberChange: (Int, String) -> Unit,
    onEpisodeCountChange: (Int, String) -> Unit,
) {
    if (rows.isEmpty()) {
        Text(
            text = stringResource(R.string.add_tv_show_seasons_empty),
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEachIndexed { index, row ->
                SeasonRowEditor(
                    index = index,
                    row = row,
                    onSeasonNumberChange = onSeasonNumberChange,
                    onEpisodeCountChange = onEpisodeCountChange,
                    onRemove = onRemove,
                    enabled = enabled,
                )
            }
        }
    }
    OutlinedButton(
        onClick = onAdd,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.add_tv_show_add_season))
    }
}

/** One editable season row: season number, episode count, and a remove control. */
@Composable
private fun SeasonRowEditor(
    index: Int,
    row: SeasonRow,
    onSeasonNumberChange: (Int, String) -> Unit,
    onEpisodeCountChange: (Int, String) -> Unit,
    onRemove: (Int) -> Unit,
    enabled: Boolean,
) {
    val seasonNumberIsValid = parseRequiredInt(row.seasonNumber) != null
    val episodeCountIsValid = parseRequiredInt(row.episodeCount) != null

    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = row.seasonNumber,
            onValueChange = { onSeasonNumberChange(index, it.filterIntegerInput()) },
            label = { Text(stringResource(R.string.tv_season_number_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !seasonNumberIsValid,
            supportingText =
                if (!seasonNumberIsValid) {
                    { Text(stringResource(R.string.add_tv_show_season_number_required_error)) }
                } else {
                    null
                },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = row.episodeCount,
            onValueChange = { onEpisodeCountChange(index, it.filterIntegerInput()) },
            label = { Text(stringResource(R.string.tv_episode_count_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !episodeCountIsValid,
            supportingText =
                if (!episodeCountIsValid) {
                    { Text(stringResource(R.string.add_tv_show_episode_count_required_error)) }
                } else {
                    null
                },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onRemove(index) }, enabled = enabled) {
            Icon(
                Icons.Default.Delete,
                contentDescription =
                    stringResource(R.string.add_tv_show_remove_season_content_description, index + 1),
            )
        }
    }
}
