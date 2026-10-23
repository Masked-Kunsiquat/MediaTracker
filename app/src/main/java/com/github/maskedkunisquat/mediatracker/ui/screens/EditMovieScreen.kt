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
import com.github.maskedkunisquat.mediatracker.ui.EditMovieViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.components.DetailArtwork
import com.github.maskedkunisquat.mediatracker.ui.components.EditFormBody
import com.github.maskedkunisquat.mediatracker.ui.components.EditHeader
import com.github.maskedkunisquat.mediatracker.ui.components.EditLoadFailed
import com.github.maskedkunisquat.mediatracker.ui.components.FilmWatchedDialog
import com.github.maskedkunisquat.mediatracker.ui.components.FormScreenMessage
import com.github.maskedkunisquat.mediatracker.ui.components.FormScreenScaffold
import com.github.maskedkunisquat.mediatracker.ui.components.FormSection
import com.github.maskedkunisquat.mediatracker.ui.components.StatusDropdownChip
import com.github.maskedkunisquat.mediatracker.ui.text.filterDecimalInput
import com.github.maskedkunisquat.mediatracker.ui.text.toDisplayDecimal
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.features.movies.data.MovieMetadataValidation
import com.hub.media.ui.AppContainer
import com.hub.media.ui.EditMovieUiState
import com.hub.media.ui.EditMovieViewModel
import com.hub.media.ui.filterIntegerInput
import com.hub.media.ui.parseOptionalNumber

/** Route wrapper: owns the [EditMovieViewModel] and leaves once the save lands. */
@Composable
fun EditMovieScreenRoute(
    appContainer: AppContainer,
    movieId: String,
    coverStorageDir: String,
    onNavigateBack: () -> Unit,
) {
    val viewModel: EditMovieViewModel =
        viewModel(factory = EditMovieViewModelFactory(appContainer, movieId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val saved = (uiState as? EditMovieUiState.Editing)?.saved == true
    LaunchedEffect(saved) {
        if (saved) onNavigateBack()
    }
    LaunchedEffect(uiState) {
        if (uiState is EditMovieUiState.NotFound) onNavigateBack()
    }

    // Choosing Watched asks when (#192); the form's status only changes once this is answered.
    (uiState as? EditMovieUiState.Editing)?.takeIf { it.pendingWatched }?.let { form ->
        FilmWatchedDialog(
            title = stringResource(R.string.film_watched_title, form.title),
            onConfirm = { watchedWhen, _ -> viewModel.confirmWatched(watchedWhen) },
            onDismiss = viewModel::dismissWatched,
        )
    }

    EditMovieScreen(
        uiState = uiState,
        coverStorageDir = coverStorageDir,
        onTitleChange = viewModel::onTitleChange,
        onReleaseYearChange = viewModel::onReleaseYearChange,
        onRuntimeChange = viewModel::onRuntimeChange,
        onPurchasePriceChange = viewModel::onPurchasePriceChange,
        onStatusChange = viewModel::onStatusChange,
        onSave = viewModel::save,
        onErrorShown = viewModel::consumeSaveError,
        onRetry = viewModel::retry,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * Stateless edit-movie form (ROADMAP Task 13 Phase B).
 *
 * Correcting a manually-entered movie is the only way to fix a typo, since nothing else can supply
 * the right value — see [EditMovieViewModel]'s KDoc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMovieScreen(
    uiState: EditMovieUiState,
    coverStorageDir: String,
    onTitleChange: (String) -> Unit,
    onReleaseYearChange: (String) -> Unit,
    onRuntimeChange: (String) -> Unit,
    onPurchasePriceChange: (String) -> Unit,
    onStatusChange: (WatchStatus) -> Unit,
    onSave: () -> Unit,
    onErrorShown: () -> Unit,
    onRetry: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val form = uiState as? EditMovieUiState.Editing

    LaunchedEffect(form?.saveError) {
        val message = form?.saveError
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onErrorShown()
        }
    }

    FormScreenScaffold(
        title = stringResource(R.string.edit_movie_title),
        onNavigateBack = onNavigateBack,
    ) { innerPadding ->
        if (form == null) {
            FormScreenMessage(innerPadding) {
                if (uiState is EditMovieUiState.LoadFailed) {
                    EditLoadFailed(onRetry = onRetry, onNavigateBack = onNavigateBack)
                } else {
                    CircularProgressIndicator()
                }
            }
            return@FormScreenScaffold
        }

        // Blank means "unknown" and clears the stored value on purpose; text that cannot be
        // parsed means neither, and saving it would erase the number the user came here to
        // correct (EditMovieViewModel refuses it outright -- this is what says which field).
        // Only parseability is checked; range and sign stay in MovieMetadataValidation.
        val releaseYearIsValid = parseOptionalNumber(form.releaseYear, String::toIntOrNull) != null
        val runtimeIsValid = parseOptionalNumber(form.runtimeMinutes, String::toIntOrNull) != null
        val purchasePriceIsValid = parseOptionalNumber(form.purchasePrice, String::toDoubleOrNull) != null

        EditFormBody(
            innerPadding = innerPadding,
            formTag = TestTags.EditMovie.FORM,
            isSaving = form.isSaving,
            // Clearing a numeric field is legitimate ("unknown") and does not block saving; a
            // field that cannot be read at all does -- the same rule the add form uses.
            canSave =
                form.title.isNotBlank() &&
                    releaseYearIsValid &&
                    runtimeIsValid &&
                    purchasePriceIsValid,
            onCancel = onNavigateBack,
            onSave = onSave,
            cancelTag = TestTags.EditMovie.CANCEL_BUTTON,
            saveTag = TestTags.EditMovie.SAVE_BUTTON,
            snackbarHostState = snackbarHostState,
        ) {
            // The title as loaded, not form.title: the header names the item while it is retyped.
            EditHeader(
                kind = stringResource(R.string.movie_detail_kind),
                year = form.savedYear,
                title = form.savedTitle,
                artwork = form.coverImageHash?.let { DetailArtwork(coverStorageDir, it, MediaType.MOVIE) },
            )
            FormSection(title = stringResource(R.string.edit_section_details)) {
                OutlinedTextField(
                    value = form.title,
                    onValueChange = onTitleChange,
                    label = { Text(stringResource(R.string.add_movie_field_title)) },
                    singleLine = true,
                    isError = form.title.isBlank(),
                    enabled = !form.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.releaseYear,
                    onValueChange = { onReleaseYearChange(it.filterIntegerInput()) },
                    label = { Text(stringResource(R.string.add_movie_field_year)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !releaseYearIsValid,
                    supportingText =
                        if (!releaseYearIsValid) {
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
                    enabled = !form.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.runtimeMinutes,
                    onValueChange = { onRuntimeChange(it.filterIntegerInput()) },
                    label = { Text(stringResource(R.string.add_movie_field_runtime)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !runtimeIsValid,
                    supportingText =
                        if (!runtimeIsValid) {
                            { Text(stringResource(R.string.add_movie_runtime_invalid_error)) }
                        } else {
                            null
                        },
                    enabled = !form.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FormSection(title = stringResource(R.string.edit_section_status)) {
                // The detail screens' chip (#174). Choosing Watched still goes through
                // onStatusChange, so it still asks when (FilmWatchedDialog) before the form changes.
                StatusDropdownChip(
                    value = form.status,
                    options = WatchStatus.entries.toList(),
                    label = { it.displayLabel() },
                    onSelect = onStatusChange,
                    onClickLabel = stringResource(R.string.detail_status_change_action_label),
                    enabled = !form.isSaving,
                )
            }
            FormSection(title = stringResource(R.string.edit_section_purchase)) {
                OutlinedTextField(
                    value = form.purchasePrice.toDisplayDecimal(),
                    // Filtered the same way EditBookScreen filters its price field: an unfiltered box
                    // accepts "12,50" or "1.2.3", neither of which toDoubleOrNull can read.
                    onValueChange = { onPurchasePriceChange(it.filterDecimalInput()) },
                    label = { Text(stringResource(R.string.add_movie_field_price)) },
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
