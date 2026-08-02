package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.EditBookViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.features.books.data.BookRepository
import com.hub.media.ui.AppContainer
import com.hub.media.ui.EditBookUiState
import com.hub.media.ui.EditBookViewModel

/**
 * Route-level composable for the edit-book-metadata screen (ROADMAP Task 6 Phase A).
 * Connects the [EditBookViewModel] to the stateless [EditBookScreen] and handles navigation.
 *
 * Navigates back automatically on either terminal state: [EditBookUiState.NotFound] (the book was
 * deleted elsewhere while this screen was open — nothing left to edit) or [EditBookUiState.Saved]
 * (the save succeeded — nothing left to do).
 *
 * @param appContainer The dependency container for creating ViewModels.
 * @param bookId The media id this screen was opened for; forwarded to [EditBookViewModelFactory].
 * @param onNavigateBack Callback to navigate back (Cancel button, back icon, or automatic on
 *   [EditBookUiState.NotFound]/[EditBookUiState.Saved]).
 */
@Composable
fun EditBookScreenRoute(
    appContainer: AppContainer,
    bookId: String,
    onNavigateBack: () -> Unit,
) {
    val viewModel: EditBookViewModel = viewModel(
        factory = EditBookViewModelFactory(appContainer, bookId),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val shouldNavigateBack = uiState is EditBookUiState.NotFound || uiState is EditBookUiState.Saved
    LaunchedEffect(shouldNavigateBack) {
        if (shouldNavigateBack) {
            onNavigateBack()
        }
    }

    EditBookScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onSave = { title, releaseYear, purchasePrice, totalPages, format ->
            viewModel.save(
                title = title,
                releaseYear = releaseYear,
                purchasePrice = purchasePrice,
                totalPages = totalPages,
                format = format,
            )
        },
    )
}

/**
 * Stateless edit-book-metadata screen composable (AGENTS.md §5 State Hoisting).
 *
 * Renders [uiState]:
 * - [EditBookUiState.Loading]: a centered [CircularProgressIndicator].
 * - [EditBookUiState.NotFound]/[EditBookUiState.Saved]: nothing (the route wrapper navigates back
 *   before either would be visible for more than a frame).
 * - [EditBookUiState.Ready]: the editable form, see [EditBookForm].
 *
 * @param uiState Current [EditBookUiState].
 * @param onNavigateBack Called when the back icon or Cancel button is pressed.
 * @param onSave Called with the edited (title, releaseYear, purchasePrice, totalPages, format)
 *   once the form passes client-side validation, wired to [EditBookViewModel.save].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBookScreen(
    uiState: EditBookUiState,
    onNavigateBack: () -> Unit,
    onSave: (
        title: String,
        releaseYear: Int?,
        purchasePrice: Double?,
        totalPages: Int?,
        format: BookFormat,
    ) -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.edit_book_title_bar)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (uiState) {
                is EditBookUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is EditBookUiState.NotFound, is EditBookUiState.Saved -> {
                    // Nothing to render; the route wrapper navigates back on these states.
                }
                is EditBookUiState.Ready -> {
                    EditBookForm(
                        state = uiState,
                        onSave = onSave,
                        onCancel = onNavigateBack,
                    )
                }
            }
        }
    }
}

/**
 * The editable form for [EditBookUiState.Ready]: title, release year, purchase price, total
 * pages, and a format picker covering all five [BookFormat] values, plus Save/Cancel.
 *
 * ### Field state seeding
 * Each field's local `remember`ed text/selection state is seeded from [state] only on this
 * composable's *first* entry into composition (a `remember` initializer only runs once per call
 * site). Recompositions triggered by [state] changing shape while remaining
 * [EditBookUiState.Ready] (e.g. [EditBookUiState.Ready.isSaving] flipping true, or
 * [EditBookUiState.Ready.errorMessage] being set after a failed save) do NOT reset the fields —
 * the user's in-progress edits survive a validation-failure round-trip exactly like
 * [BookDetailScreen]'s `PendingSessionDialog`/`ManualSessionDialog` already do for their own local
 * field state.
 *
 * ### Parse-once validation (blank vs. unparseable vs. out-of-range)
 * Each optional numeric field (release year, purchase price, total pages) is parsed exactly once
 * per recomposition, above the fields, mirroring `BookDetailScreen`'s `ManualSessionDialog`
 * duration-field fix: a raw `text.toIntOrNull()`/`toDoubleOrNull()` collapses "intentionally
 * blank" and "unparseable/overflowed" into the same `null`, which would silently let a mistyped or
 * overflowing value save as "cleared" instead of being rejected. Each field's `*IsValid` flag
 * disambiguates the two `null` causes (blank is always valid; a non-blank value is only valid when
 * it both parses AND satisfies its bound), and only `validated*` — never the raw parse — is what
 * ever reaches [onSave].
 *
 * Release year and total pages are digit-filtered ([filterIntegerInput], no sign or decimal point
 * can be typed); purchase price is digit-and-decimal-point filtered ([filterDecimalInput], no
 * minus sign), so a negative value — the one thing
 * [BookRepository.updateBookMetadata][com.hub.media.features.books.data.BookRepository.updateBookMetadata]
 * rejects for purchase price/total pages — can never be typed in the first place, same as the
 * position fields on [BookDetailScreen]'s session dialogs. The release-year bound is sourced from
 * [BookRepository.MIN_RELEASE_YEAR]/[BookRepository.MAX_RELEASE_YEAR] (the same constants the
 * repository itself validates against) rather than a second hardcoded copy, so client-side and
 * server-side validation can never drift apart.
 */
@Composable
private fun EditBookForm(
    state: EditBookUiState.Ready,
    onSave: (
        title: String,
        releaseYear: Int?,
        purchasePrice: Double?,
        totalPages: Int?,
        format: BookFormat,
    ) -> Unit,
    onCancel: () -> Unit,
) {
    var titleText by remember { mutableStateOf(state.title) }
    var releaseYearText by remember { mutableStateOf(state.releaseYear?.toString() ?: "") }
    var purchasePriceText by remember { mutableStateOf(state.purchasePrice?.let(::formatUnit) ?: "") }
    var totalPagesText by remember { mutableStateOf(state.totalPages?.toString() ?: "") }
    var format by remember { mutableStateOf(state.format) }

    val titleIsValid = titleText.isNotBlank()

    val parsedReleaseYear = releaseYearText.toIntOrNull()
    val releaseYearIsValid = releaseYearText.isBlank() ||
        (
            parsedReleaseYear != null &&
                parsedReleaseYear in BookRepository.MIN_RELEASE_YEAR..BookRepository.MAX_RELEASE_YEAR
            )
    val validatedReleaseYear = if (releaseYearText.isBlank()) null else parsedReleaseYear

    val parsedPurchasePrice = purchasePriceText.toDoubleOrNull()
    val purchasePriceIsValid = purchasePriceText.isBlank() ||
        (parsedPurchasePrice != null && parsedPurchasePrice >= 0.0)
    val validatedPurchasePrice = if (purchasePriceText.isBlank()) null else parsedPurchasePrice

    val parsedTotalPages = totalPagesText.toIntOrNull()
    val totalPagesIsValid = totalPagesText.isBlank() || (parsedTotalPages != null && parsedTotalPages > 0)
    val validatedTotalPages = if (totalPagesText.isBlank()) null else parsedTotalPages

    val formIsValid = titleIsValid && releaseYearIsValid && purchasePriceIsValid && totalPagesIsValid

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = titleText,
            onValueChange = { titleText = it },
            label = { Text(stringResource(R.string.edit_title_label)) },
            isError = !titleIsValid,
            supportingText = if (!titleIsValid) {
                { Text(stringResource(R.string.edit_title_required_error)) }
            } else {
                null
            },
            singleLine = true,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = releaseYearText,
            onValueChange = { releaseYearText = it.filterIntegerInput() },
            label = { Text(stringResource(R.string.edit_release_year_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !releaseYearIsValid,
            supportingText = if (!releaseYearIsValid) {
                {
                    Text(
                        stringResource(
                            R.string.edit_release_year_invalid_error,
                            BookRepository.MIN_RELEASE_YEAR,
                            BookRepository.MAX_RELEASE_YEAR,
                        ),
                    )
                }
            } else {
                null
            },
            singleLine = true,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = purchasePriceText,
            onValueChange = { purchasePriceText = it.filterDecimalInput() },
            label = { Text(stringResource(R.string.edit_purchase_price_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = !purchasePriceIsValid,
            supportingText = if (!purchasePriceIsValid) {
                { Text(stringResource(R.string.edit_purchase_price_invalid_error)) }
            } else {
                null
            },
            singleLine = true,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = totalPagesText,
            onValueChange = { totalPagesText = it.filterIntegerInput() },
            label = { Text(stringResource(R.string.edit_total_pages_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !totalPagesIsValid,
            supportingText = if (!totalPagesIsValid) {
                { Text(stringResource(R.string.edit_total_pages_invalid_error)) }
            } else {
                null
            },
            singleLine = true,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = stringResource(R.string.edit_format_label),
            style = MaterialTheme.typography.titleMedium,
        )
        Column(modifier = Modifier.selectableGroup()) {
            BookFormat.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = format == option,
                            onClick = { format = option },
                            enabled = !state.isSaving,
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = format == option,
                        onClick = null,
                        enabled = !state.isSaving,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(option.displayLabel())
                }
            }
        }

        val errorMessage = state.errorMessage
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    onSave(
                        titleText.trim(),
                        validatedReleaseYear,
                        validatedPurchasePrice,
                        validatedTotalPages,
                        format,
                    )
                },
                enabled = formIsValid && !state.isSaving,
            ) {
                Text(stringResource(R.string.save_button))
            }
            OutlinedButton(onClick = onCancel, enabled = !state.isSaving) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    }
}

private val PREVIEW_READY_STATE = EditBookUiState.Ready(
    title = "The Great Gatsby",
    releaseYear = 1925,
    purchasePrice = 9.99,
    totalPages = 180,
    format = BookFormat.PHYSICAL,
)

/** Preview of the edit-book screen prefilled with existing metadata. */
@Preview(showBackground = true)
@Composable
private fun EditBookScreenReadyPreview() {
    MediaTrackerTheme {
        EditBookScreen(
            uiState = PREVIEW_READY_STATE,
            onNavigateBack = {},
            onSave = { _, _, _, _, _ -> },
        )
    }
}

/** Preview of the edit-book screen with a failed-save error message displayed. */
@Preview(showBackground = true)
@Composable
private fun EditBookScreenErrorPreview() {
    MediaTrackerTheme {
        EditBookScreen(
            uiState = PREVIEW_READY_STATE.copy(errorMessage = "Total pages must be a positive number"),
            onNavigateBack = {},
            onSave = { _, _, _, _, _ -> },
        )
    }
}

/** Preview of the edit-book screen while the current metadata is still loading. */
@Preview(showBackground = true)
@Composable
private fun EditBookScreenLoadingPreview() {
    MediaTrackerTheme {
        EditBookScreen(
            uiState = EditBookUiState.Loading,
            onNavigateBack = {},
            onSave = { _, _, _, _, _ -> },
        )
    }
}
