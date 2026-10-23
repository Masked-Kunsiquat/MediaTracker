package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.EditTVShowViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.components.DetailArtwork
import com.github.maskedkunisquat.mediatracker.ui.components.EditFormBody
import com.github.maskedkunisquat.mediatracker.ui.components.EditHeader
import com.github.maskedkunisquat.mediatracker.ui.components.EditLoadFailed
import com.github.maskedkunisquat.mediatracker.ui.components.FormScreenMessage
import com.github.maskedkunisquat.mediatracker.ui.components.FormScreenScaffold
import com.github.maskedkunisquat.mediatracker.ui.components.FormSection
import com.github.maskedkunisquat.mediatracker.ui.text.filterDecimalInput
import com.github.maskedkunisquat.mediatracker.ui.text.toDisplayDecimal
import com.hub.media.core.database.entities.MediaType
import com.hub.media.features.tv.data.TVMetadataValidation
import com.hub.media.ui.AppContainer
import com.hub.media.ui.EditTVShowUiState
import com.hub.media.ui.EditTVShowViewModel
import com.hub.media.ui.filterIntegerInput
import com.hub.media.ui.parseOptionalNumber

/** Route wrapper: owns the [EditTVShowViewModel] and leaves once the save lands or the show is gone. */
@Composable
fun EditTVShowScreenRoute(
    appContainer: AppContainer,
    showId: String,
    coverStorageDir: String,
    onNavigateBack: () -> Unit,
) {
    val viewModel: EditTVShowViewModel =
        viewModel(factory = EditTVShowViewModelFactory(appContainer, showId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val saved = (uiState as? EditTVShowUiState.Editing)?.saved == true
    LaunchedEffect(saved) {
        if (saved) onNavigateBack()
    }
    LaunchedEffect(uiState) {
        if (uiState is EditTVShowUiState.NotFound) onNavigateBack()
    }

    EditTVShowScreen(
        uiState = uiState,
        coverStorageDir = coverStorageDir,
        onTitleChange = viewModel::onTitleChange,
        onReleaseYearChange = viewModel::onReleaseYearChange,
        onTotalSeasonsChange = viewModel::onTotalSeasonsChange,
        onPurchasePriceChange = viewModel::onPurchasePriceChange,
        onSave = viewModel::save,
        onErrorShown = viewModel::consumeSaveError,
        onRetry = viewModel::retry,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * Stateless edit-show form (#173): title, release year, total seasons, purchase price.
 *
 * No status control -- abandoning is the detail screen's toggle -- and no per-season episode
 * counts, which stay in the season dialog. Since #174 it shares Edit Book's shape: a header,
 * Details and Purchase cards, and the pinned Cancel/Save bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTVShowScreen(
    uiState: EditTVShowUiState,
    coverStorageDir: String,
    onTitleChange: (String) -> Unit,
    onReleaseYearChange: (String) -> Unit,
    onTotalSeasonsChange: (String) -> Unit,
    onPurchasePriceChange: (String) -> Unit,
    onSave: () -> Unit,
    onErrorShown: () -> Unit,
    onRetry: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val form = uiState as? EditTVShowUiState.Editing

    LaunchedEffect(form?.saveError) {
        val message = form?.saveError
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onErrorShown()
        }
    }

    FormScreenScaffold(
        title = stringResource(R.string.edit_tv_show_title),
        onNavigateBack = onNavigateBack,
    ) { innerPadding ->
        if (form == null) {
            FormScreenMessage(innerPadding) {
                if (uiState is EditTVShowUiState.LoadFailed) {
                    EditLoadFailed(onRetry = onRetry, onNavigateBack = onNavigateBack)
                } else {
                    CircularProgressIndicator()
                }
            }
            return@FormScreenScaffold
        }

        val releaseYearIsValid = parseOptionalNumber(form.releaseYear, String::toIntOrNull) != null
        val totalSeasonsIsValid = parseOptionalNumber(form.totalSeasons, String::toIntOrNull) != null
        val purchasePriceIsValid = parseOptionalNumber(form.purchasePrice, String::toDoubleOrNull) != null

        EditFormBody(
            innerPadding = innerPadding,
            formTag = TestTags.EditTVShow.FORM,
            isSaving = form.isSaving,
            // Clearing a numeric field is legitimate ("unknown") and does not block saving; a
            // field that cannot be read at all does -- the same rule the add form uses.
            canSave =
                form.title.isNotBlank() &&
                    releaseYearIsValid &&
                    totalSeasonsIsValid &&
                    purchasePriceIsValid,
            onCancel = onNavigateBack,
            onSave = onSave,
            cancelTag = TestTags.EditTVShow.CANCEL_BUTTON,
            saveTag = TestTags.EditTVShow.SAVE_BUTTON,
            snackbarHostState = snackbarHostState,
        ) {
            // The title as loaded, not form.title: the header names the item while it is retyped.
            EditHeader(
                kind = stringResource(R.string.tv_show_detail_kind),
                year = form.savedYear,
                title = form.savedTitle,
                artwork = form.coverImageHash?.let { DetailArtwork(coverStorageDir, it, MediaType.TV_SHOW) },
            )
            FormSection(title = stringResource(R.string.edit_section_details)) {
                OutlinedTextField(
                    value = form.title,
                    onValueChange = onTitleChange,
                    label = { Text(stringResource(R.string.add_tv_show_field_title)) },
                    singleLine = true,
                    isError = form.title.isBlank(),
                    enabled = !form.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.releaseYear,
                    onValueChange = { onReleaseYearChange(it.filterIntegerInput()) },
                    label = { Text(stringResource(R.string.add_tv_show_field_year)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !releaseYearIsValid,
                    supportingText =
                        if (!releaseYearIsValid) {
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
                    enabled = !form.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.totalSeasons,
                    onValueChange = { onTotalSeasonsChange(it.filterIntegerInput()) },
                    label = { Text(stringResource(R.string.add_tv_show_field_total_seasons)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !totalSeasonsIsValid,
                    supportingText =
                        if (!totalSeasonsIsValid) {
                            { Text(stringResource(R.string.add_tv_show_total_seasons_invalid_error)) }
                        } else {
                            null
                        },
                    enabled = !form.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FormSection(title = stringResource(R.string.edit_section_purchase)) {
                OutlinedTextField(
                    value = form.purchasePrice.toDisplayDecimal(),
                    // Filtered as EditMovieScreen does: an unfiltered box accepts "12,50" or "1.2.3",
                    // neither of which toDoubleOrNull can read.
                    onValueChange = { onPurchasePriceChange(it.filterDecimalInput()) },
                    label = { Text(stringResource(R.string.add_tv_show_field_price)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = !purchasePriceIsValid,
                    supportingText =
                        if (!purchasePriceIsValid) {
                            { Text(stringResource(R.string.edit_purchase_price_invalid_error)) }
                        } else {
                            null
                        },
                    enabled = !form.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
