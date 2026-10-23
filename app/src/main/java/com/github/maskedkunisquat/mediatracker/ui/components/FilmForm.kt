package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.screens.displayLabel
import com.github.maskedkunisquat.mediatracker.ui.text.filterDecimalInput
import com.github.maskedkunisquat.mediatracker.ui.text.toDisplayDecimal
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.features.movies.data.MovieMetadataValidation
import com.hub.media.ui.FilmFormState
import com.hub.media.ui.filterIntegerInput
import com.hub.media.ui.parseOptionalNumber

/**
 * Which of a film form's fields hold something a save would refuse (#214).
 *
 * Blank is always fine ("unknown"). A non-blank field must parse, and then pass the same
 * [MovieMetadataValidation] rule the repository runs -- called, not copied, so the form and the
 * write cannot disagree. Checking the range here and not only at the write matters because the
 * review form opens prefilled from TMDB: an out-of-range year is flagged on the field the user is
 * looking at, as Edit book flags one, instead of coming back as a snackbar after Save.
 */
internal class FilmFieldValidity(
    form: FilmFormState,
) {
    val releaseYear: Boolean =
        parseOptionalNumber(form.releaseYear, String::toIntOrNull)
            ?.let { MovieMetadataValidation.validateReleaseYear(it.value) == null } == true
    val runtime: Boolean =
        parseOptionalNumber(form.runtimeMinutes, String::toIntOrNull)
            ?.let { MovieMetadataValidation.validateRuntimeMinutes(it.value) == null } == true
    val purchasePrice: Boolean = parseOptionalNumber(form.purchasePrice, String::toDoubleOrNull) != null
    val title: Boolean = form.title.isNotBlank()

    /** Every field savable. Clearing a numeric field is legitimate and never blocks saving. */
    val canSave: Boolean = title && releaseYear && runtime && purchasePrice
}

/**
 * The film form (#214), one copy for every screen that shows it: Add film by hand, the review after
 * picking a TMDB result, and Edit film. [header] comes first; then the Details, Status and Purchase
 * cards in Edit film's order (#174).
 *
 * Stateless: the values are [form], and every edit leaves through a callback. Choosing Watched goes
 * through [onStatusChange] like any other status, so the host's [FilmFormState.choosingStatus] asks
 * when (FilmWatchedDialog) before the form changes.
 *
 * [flagBlankTitle] is false on a form that opened empty: a blank title there is not yet a mistake,
 * and the disabled Save already says it is required. Where a title was loaded, a blank one means it
 * was cleared, and the field says so.
 */
@Composable
fun ColumnScope.FilmFormContent(
    form: FilmFormState,
    header: @Composable () -> Unit,
    enabled: Boolean,
    flagBlankTitle: Boolean,
    onTitleChange: (String) -> Unit,
    onReleaseYearChange: (String) -> Unit,
    onRuntimeChange: (String) -> Unit,
    onPurchasePriceChange: (String) -> Unit,
    onStatusChange: (WatchStatus) -> Unit,
) {
    val valid = FilmFieldValidity(form)
    header()
    FormSection(title = stringResource(R.string.edit_section_details)) {
        OutlinedTextField(
            value = form.title,
            onValueChange = onTitleChange,
            label = { Text(stringResource(R.string.add_movie_field_title)) },
            singleLine = true,
            isError = flagBlankTitle && !valid.title,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.releaseYear,
            onValueChange = { onReleaseYearChange(it.filterIntegerInput()) },
            label = { Text(stringResource(R.string.add_movie_field_year)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !valid.releaseYear,
            supportingText =
                if (!valid.releaseYear) {
                    {
                        Text(
                            stringResource(
                                R.string.edit_release_year_invalid_error,
                                MovieMetadataValidation.MIN_RELEASE_YEAR,
                                MovieMetadataValidation.MAX_RELEASE_YEAR,
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
            value = form.runtimeMinutes,
            onValueChange = { onRuntimeChange(it.filterIntegerInput()) },
            label = { Text(stringResource(R.string.add_movie_field_runtime)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !valid.runtime,
            supportingText =
                if (!valid.runtime) {
                    { Text(stringResource(R.string.add_movie_runtime_invalid_error)) }
                } else {
                    null
                },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    FormSection(title = stringResource(R.string.edit_section_status)) {
        // The detail screens' chip (#174).
        StatusDropdownChip(
            value = form.status,
            options = WatchStatus.entries.toList(),
            label = { it.displayLabel() },
            onSelect = onStatusChange,
            onClickLabel = stringResource(R.string.detail_status_change_action_label),
            enabled = enabled,
        )
    }
    FormSection(title = stringResource(R.string.edit_section_purchase)) {
        OutlinedTextField(
            value = form.purchasePrice.toDisplayDecimal(),
            // Filtered the same way the book form filters its price field: an unfiltered box accepts
            // "12,50" or "1.2.3", neither of which toDoubleOrNull can read.
            onValueChange = { onPurchasePriceChange(it.filterDecimalInput()) },
            label = { Text(stringResource(R.string.add_movie_field_price)) },
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
