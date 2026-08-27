package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.AddTVShowViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.insets.scrollingContentPadding
import com.github.maskedkunisquat.mediatracker.ui.text.filterDecimalInput
import com.hub.media.features.tv.data.TVMetadataValidation
import com.hub.media.ui.AddTVShowUiState
import com.hub.media.ui.AddTVShowViewModel
import com.hub.media.ui.AppContainer
import com.hub.media.ui.SeasonRow
import com.hub.media.ui.filterIntegerInput
import com.hub.media.ui.parseOptionalNumber
import com.hub.media.ui.parseRequiredInt

/**
 * Route wrapper: owns the [AddTVShowViewModel] and turns a successful save into navigation.
 */
@Composable
fun AddTVShowScreenRoute(
    appContainer: AppContainer,
    onNavigateBack: () -> Unit,
    onShowAdded: (String) -> Unit,
) {
    val viewModel: AddTVShowViewModel = viewModel(factory = AddTVShowViewModelFactory(appContainer))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Navigation is an effect of reaching a saved id, not something the button does directly: the
    // save is asynchronous, so the button cannot know the id at click time. Mirrors
    // AddMovieScreenRoute, including the reset() before navigating.
    LaunchedEffect(uiState) {
        val savedMediaId = uiState.savedMediaId
        if (savedMediaId != null) {
            viewModel.reset()
            onShowAdded(savedMediaId)
        }
    }

    AddTVShowScreen(
        uiState = uiState,
        onTitleChange = viewModel::onTitleChange,
        onReleaseYearChange = viewModel::onReleaseYearChange,
        onTotalSeasonsChange = viewModel::onTotalSeasonsChange,
        onPurchasePriceChange = viewModel::onPurchasePriceChange,
        onAddSeasonRow = viewModel::addSeasonRow,
        onRemoveSeasonRow = viewModel::removeSeasonRow,
        onSeasonNumberChange = viewModel::onSeasonNumberChange,
        onEpisodeCountChange = viewModel::onEpisodeCountChange,
        onSave = viewModel::save,
        onErrorShown = viewModel::reset,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * Manual TV show entry form with season quick-fill (ROADMAP Task 13 Phase C).
 *
 * Stateless with respect to the **save lifecycle** exactly like [AddMovieScreen], so an
 * instrumented test can drive it with fabricated [uiState] and fake callbacks. Unlike
 * [AddMovieScreen], the field values themselves are *not* held locally here -- they live in
 * [AddTVShowUiState] because the season list is a dynamic collection `rememberSaveable` cannot
 * carry without hand-written bookkeeping. See [AddTVShowViewModel]'s KDoc for the full reasoning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTVShowScreen(
    uiState: AddTVShowUiState,
    onTitleChange: (String) -> Unit,
    onReleaseYearChange: (String) -> Unit,
    onTotalSeasonsChange: (String) -> Unit,
    onPurchasePriceChange: (String) -> Unit,
    onAddSeasonRow: () -> Unit,
    onRemoveSeasonRow: (Int) -> Unit,
    onSeasonNumberChange: (Int, String) -> Unit,
    onEpisodeCountChange: (Int, String) -> Unit,
    onSave: () -> Unit,
    onErrorShown: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.saveError) {
        val message = uiState.saveError
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onErrorShown()
        }
    }

    // Blank means "unknown" and saves as null for these show-level fields; text that cannot be
    // parsed is refused rather than quietly forwarded as null -- same rule AddMovieScreen/
    // EditMovieScreen apply. Range and sign rules stay in TVMetadataValidation.
    val releaseYearIsValid = parseOptionalNumber(uiState.releaseYear, String::toIntOrNull) != null
    val totalSeasonsIsValid = parseOptionalNumber(uiState.totalSeasons, String::toIntOrNull) != null
    val purchasePriceIsValid = parseOptionalNumber(uiState.purchasePrice, String::toDoubleOrNull) != null

    // A season row's fields are REQUIRED, not optional -- see AddTVShowViewModel's KDoc: a blank
    // episode count is a row the user has not finished filling in, not "unknown episode count."
    val seasonRowsAreValid =
        uiState.seasons.all { row ->
            parseRequiredInt(row.seasonNumber) != null && parseRequiredInt(row.episodeCount) != null
        }

    val canSave =
        uiState.title.isNotBlank() &&
            releaseYearIsValid &&
            totalSeasonsIsValid &&
            purchasePriceIsValid &&
            seasonRowsAreValid &&
            !uiState.isSaving

    Scaffold(
        // Season rows are a stack of OutlinedTextFields, and Scaffold's default insets don't
        // include the IME -- add safeDrawing so the keyboard doesn't just cover them.
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_tv_show_title)) },
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
                    // Keyboard outside the scroll, bars inside it -- see
                    // barPaddingForScrollingContent for why those two insets part company here.
                    .imePadding()
                    .consumeWindowInsets(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(scrollingContentPadding(innerPadding, PaddingValues(16.dp)))
                    .testTag(TestTags.AddTVShow.FORM),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = uiState.title,
                onValueChange = onTitleChange,
                label = { Text(stringResource(R.string.add_tv_show_field_title)) },
                singleLine = true,
                // No isError, matching AddMovieScreen: this form opens empty, so flagging the blank
                // title would put the very first field in an error state before the user has typed
                // anything. The disabled Save button already says the title is required, without
                // accusing anyone of a mistake they have not made yet. EditMovieScreen does flag it,
                // and is right to -- there a blank title means the user cleared a value that existed.
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.releaseYear,
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
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.totalSeasons,
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
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.purchasePrice,
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
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.add_tv_show_seasons_section_label),
                style = MaterialTheme.typography.labelLarge,
            )
            if (uiState.seasons.isEmpty()) {
                Text(
                    text = stringResource(R.string.add_tv_show_seasons_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.seasons.forEachIndexed { index, row ->
                        SeasonRowEditor(
                            index = index,
                            row = row,
                            onSeasonNumberChange = onSeasonNumberChange,
                            onEpisodeCountChange = onEpisodeCountChange,
                            onRemove = onRemoveSeasonRow,
                            enabled = !uiState.isSaving,
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = onAddSeasonRow,
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.add_tv_show_add_season))
            }

            Button(
                onClick = onSave,
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.AddTVShow.SAVE_BUTTON),
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                } else {
                    Text(stringResource(R.string.add_tv_show_save))
                }
            }
        }
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
