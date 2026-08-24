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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.AddMovieViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.text.filterDecimalInput
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.features.movies.data.MovieMetadataValidation
import com.hub.media.ui.AddMovieUiState
import com.hub.media.ui.AddMovieViewModel
import com.hub.media.ui.AppContainer
import com.hub.media.ui.filterIntegerInput

/**
 * Route wrapper: owns the [AddMovieViewModel] and turns a successful save into navigation.
 */
@Composable
fun AddMovieScreenRoute(
    appContainer: AppContainer,
    onNavigateBack: () -> Unit,
    onMovieAdded: (String) -> Unit,
) {
    val viewModel: AddMovieViewModel = viewModel(factory = AddMovieViewModelFactory(appContainer))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Navigation is an effect of reaching Saved, not something the button does directly: the save
    // is asynchronous, so the button cannot know the id at click time.
    LaunchedEffect(uiState) {
        val state = uiState
        if (state is AddMovieUiState.Saved) {
            viewModel.reset()
            onMovieAdded(state.mediaId)
        }
    }

    AddMovieScreen(
        uiState = uiState,
        onSave = viewModel::save,
        onErrorShown = viewModel::reset,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * Manual movie entry form (ROADMAP Task 13 Phase B).
 *
 * Stateless with respect to the **save lifecycle** — idle/saving/saved/error all arrive as
 * [uiState] — so it can be driven directly by an instrumented test with fabricated state and fake
 * callbacks, per AGENTS.md §7. That is the part worth testing: it touches no database and behaves
 * identically on an empty device or a full one.
 *
 * The field values themselves are deliberately *not* hoisted into [AddMovieViewModel], unlike
 * [EditMovieScreen], whose values live in `EditMovieUiState.Editing`. That difference is not an
 * inconsistency: the edit form has to prefill from a row the ViewModel loads once, so the values
 * must survive that load and the ViewModel is the only thing that can hold them. This form starts
 * empty, so there is nothing to load and nothing for a ViewModel to own — [rememberSaveable]
 * already carries the typed text across rotation and process death, which is the only durability
 * requirement here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMovieScreen(
    uiState: AddMovieUiState,
    onSave: (String, Int?, Int?, Double?, WatchStatus) -> Unit,
    onErrorShown: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    // rememberSaveable so a rotation mid-entry does not discard what was typed.
    var title by rememberSaveable { mutableStateOf("") }
    var releaseYear by rememberSaveable { mutableStateOf("") }
    var runtimeMinutes by rememberSaveable { mutableStateOf("") }
    var purchasePrice by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf(WatchStatus.WATCHLIST) }

    val snackbarHostState = remember { SnackbarHostState() }
    val errorState = uiState as? AddMovieUiState.Error
    LaunchedEffect(errorState) {
        if (errorState != null) {
            snackbarHostState.showSnackbar(errorState.message)
            onErrorShown()
        }
    }

    val isSaving = uiState is AddMovieUiState.Saving

    // Blank means "unknown" and saves as null; text that cannot be parsed is a different thing
    // entirely and must not be quietly forwarded as null, which would discard what was typed
    // without saying so. Only parseability is checked here -- the range and sign rules stay in
    // MovieMetadataValidation, so this never becomes a second, drifting copy of them.
    val parsedReleaseYear = releaseYear.toIntOrNull()
    val releaseYearIsValid = releaseYear.isBlank() || parsedReleaseYear != null
    val parsedRuntimeMinutes = runtimeMinutes.toIntOrNull()
    val runtimeIsValid = runtimeMinutes.isBlank() || parsedRuntimeMinutes != null
    val parsedPurchasePrice = purchasePrice.toDoubleOrNull()
    val purchasePriceIsValid = purchasePrice.isBlank() || parsedPurchasePrice != null

    // Only the title is required. Every other field blank means "unknown", which is a valid state
    // and must not block saving -- an empty runtime is not a zero-minute film.
    val canSave =
        title.isNotBlank() &&
            releaseYearIsValid &&
            runtimeIsValid &&
            purchasePriceIsValid &&
            !isSaving

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_movie_title)) },
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
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.add_movie_field_title)) },
                singleLine = true,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = releaseYear,
                onValueChange = { releaseYear = it.filterIntegerInput() },
                label = { Text(stringResource(R.string.add_movie_field_year)) },
                singleLine = true,
                keyboardOptions = numericKeyboard(KeyboardType.Number),
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
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = runtimeMinutes,
                onValueChange = { runtimeMinutes = it.filterIntegerInput() },
                label = { Text(stringResource(R.string.add_movie_field_runtime)) },
                singleLine = true,
                keyboardOptions = numericKeyboard(KeyboardType.Number),
                isError = !runtimeIsValid,
                supportingText =
                    if (!runtimeIsValid) {
                        { Text(stringResource(R.string.add_movie_runtime_invalid_error)) }
                    } else {
                        null
                    },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = purchasePrice,
                // Filtered the same way EditBookScreen filters its price field: an unfiltered box
                // accepts "12,50" or "1.2.3", neither of which toDoubleOrNull can read.
                onValueChange = { purchasePrice = it.filterDecimalInput() },
                label = { Text(stringResource(R.string.add_movie_field_price)) },
                singleLine = true,
                keyboardOptions = numericKeyboard(KeyboardType.Decimal),
                isError = !purchasePriceIsValid,
                supportingText =
                    if (!purchasePriceIsValid) {
                        { Text(stringResource(R.string.edit_purchase_price_invalid_error)) }
                    } else {
                        null
                    },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.add_movie_status_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WatchStatus.entries.forEach { option ->
                    FilterChip(
                        selected = status == option,
                        onClick = { status = option },
                        label = { Text(option.displayLabel()) },
                        enabled = !isSaving,
                    )
                }
            }

            Button(
                onClick = {
                    // The parsed values, not a fresh parse: canSave has already established that
                    // each non-blank field actually read, so a null here can only mean blank
                    // ("unknown") -- never "unreadable, forwarded as unknown anyway".
                    //
                    // Parsing is still not trusted as validation: toDoubleOrNull accepts
                    // "Infinity". MovieMetadataValidation rejects non-finite values before the
                    // write, which is what actually protects the column.
                    onSave(
                        title,
                        parsedReleaseYear,
                        parsedRuntimeMinutes,
                        parsedPurchasePrice,
                        status,
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                } else {
                    Text(stringResource(R.string.add_movie_save))
                }
            }
        }
    }
}

/** Shared keyboard options for the numeric fields. */
private fun numericKeyboard(type: KeyboardType) = KeyboardOptions(keyboardType = type)
