package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.EditMovieViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.text.filterDecimalInput
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.features.movies.data.MovieMetadataValidation
import com.hub.media.ui.AppContainer
import com.hub.media.ui.EditMovieUiState
import com.hub.media.ui.EditMovieViewModel
import com.hub.media.ui.filterIntegerInput

/** Route wrapper: owns the [EditMovieViewModel] and leaves once the save lands. */
@Composable
fun EditMovieScreenRoute(
    appContainer: AppContainer,
    movieId: String,
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

    EditMovieScreen(
        uiState = uiState,
        onTitleChange = viewModel::onTitleChange,
        onReleaseYearChange = viewModel::onReleaseYearChange,
        onRuntimeChange = viewModel::onRuntimeChange,
        onPurchasePriceChange = viewModel::onPurchasePriceChange,
        onStatusChange = viewModel::onStatusChange,
        onSave = viewModel::save,
        onErrorShown = viewModel::consumeSaveError,
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
    onTitleChange: (String) -> Unit,
    onReleaseYearChange: (String) -> Unit,
    onRuntimeChange: (String) -> Unit,
    onPurchasePriceChange: (String) -> Unit,
    onStatusChange: (WatchStatus) -> Unit,
    onSave: () -> Unit,
    onErrorShown: () -> Unit,
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_movie_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (form == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }

            // Blank means "unknown" and clears the stored value on purpose; text that cannot be
            // parsed means neither, and saving it would erase the number the user came here to
            // correct (EditMovieViewModel refuses it outright -- this is what says which field).
            // Only parseability is checked; range and sign stay in MovieMetadataValidation.
            val releaseYearIsValid = form.releaseYear.isBlank() || form.releaseYear.toIntOrNull() != null
            val runtimeIsValid = form.runtimeMinutes.isBlank() || form.runtimeMinutes.toIntOrNull() != null
            val purchasePriceIsValid = form.purchasePrice.isBlank() || form.purchasePrice.toDoubleOrNull() != null

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
            OutlinedTextField(
                value = form.purchasePrice,
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

            Text(
                text = stringResource(R.string.add_movie_status_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WatchStatus.entries.forEach { option ->
                    FilterChip(
                        selected = form.status == option,
                        onClick = { onStatusChange(option) },
                        label = { Text(option.displayLabel()) },
                        enabled = !form.isSaving,
                    )
                }
            }

            Button(
                onClick = onSave,
                // Clearing a numeric field is legitimate ("unknown") and does not block saving; a
                // field that cannot be read at all does -- the same rule the add form uses.
                enabled =
                    form.title.isNotBlank() &&
                        releaseYearIsValid &&
                        runtimeIsValid &&
                        purchasePriceIsValid &&
                        !form.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (form.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                } else {
                    Text(stringResource(R.string.edit_movie_save))
                }
            }
        }
    }
}
