package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.EditBookViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.components.DetailArtwork
import com.github.maskedkunisquat.mediatracker.ui.components.EditFormBody
import com.github.maskedkunisquat.mediatracker.ui.components.EditHeader
import com.github.maskedkunisquat.mediatracker.ui.components.EditLoadFailed
import com.github.maskedkunisquat.mediatracker.ui.components.FormScreenMessage
import com.github.maskedkunisquat.mediatracker.ui.components.FormScreenScaffold
import com.github.maskedkunisquat.mediatracker.ui.components.FormSection
import com.github.maskedkunisquat.mediatracker.ui.components.StatusDropdownChip
import com.github.maskedkunisquat.mediatracker.ui.text.filterDecimalInput
import com.github.maskedkunisquat.mediatracker.ui.text.formatUnit
import com.github.maskedkunisquat.mediatracker.ui.text.toDisplayDecimal
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.features.books.domain.BookMetadataValidation
import com.hub.media.ui.AppContainer
import com.hub.media.ui.EditBookUiState
import com.hub.media.ui.EditBookViewModel
import com.hub.media.ui.filterIntegerInput
import com.hub.media.ui.parseOptionalNumber

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
    coverStorageDir: String,
    onNavigateBack: () -> Unit,
) {
    val viewModel: EditBookViewModel =
        viewModel(
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
        coverStorageDir = coverStorageDir,
        onNavigateBack = onNavigateBack,
        onRetry = viewModel::retry,
        onSave = { title, releaseYear, purchasePrice, totalPages, format, status, trackingMode ->
            viewModel.save(
                title = title,
                releaseYear = releaseYear,
                purchasePrice = purchasePrice,
                totalPages = totalPages,
                format = format,
                status = status,
                trackingMode = trackingMode,
            )
        },
    )
}

/**
 * Stateless edit-book-metadata screen composable (AGENTS.md §5 State Hoisting).
 *
 * Renders [uiState]:
 * - [EditBookUiState.Loading]: a centered [CircularProgressIndicator].
 * - [EditBookUiState.LoadFailed]: [EditLoadFailed], offering Retry and Go back.
 * - [EditBookUiState.NotFound]/[EditBookUiState.Saved]: nothing (the route wrapper navigates back
 *   before either would be visible for more than a frame).
 * - [EditBookUiState.Ready]: the editable form, see [EditBookForm].
 *
 * @param uiState Current [EditBookUiState].
 * @param onNavigateBack Called when the back icon or Cancel button is pressed.
 * @param onRetry Re-reads the book after [EditBookUiState.LoadFailed], wired to
 *   [EditBookViewModel.retry].
 * @param onSave Called with the edited (title, releaseYear, purchasePrice, totalPages, format,
 *   status, trackingMode) once the form passes client-side validation, wired to
 *   [EditBookViewModel.save].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBookScreen(
    uiState: EditBookUiState,
    coverStorageDir: String,
    onNavigateBack: () -> Unit,
    onRetry: () -> Unit,
    onSave: (
        title: String,
        releaseYear: Int?,
        purchasePrice: Double?,
        totalPages: Int?,
        format: BookFormat,
        status: ReadingStatus,
        trackingMode: TrackingMode,
    ) -> Unit,
) {
    FormScreenScaffold(
        title = stringResource(R.string.edit_book_title_bar),
        onNavigateBack = onNavigateBack,
        centeredTitle = true,
    ) { innerPadding ->
        when (uiState) {
            is EditBookUiState.Loading -> {
                FormScreenMessage(innerPadding) { CircularProgressIndicator() }
            }
            is EditBookUiState.LoadFailed -> {
                FormScreenMessage(innerPadding) {
                    EditLoadFailed(onRetry = onRetry, onNavigateBack = onNavigateBack)
                }
            }
            is EditBookUiState.NotFound, is EditBookUiState.Saved -> {
                // Nothing to render; the route wrapper navigates back on these states.
            }
            is EditBookUiState.Ready -> {
                EditBookForm(
                    state = uiState,
                    coverStorageDir = coverStorageDir,
                    scaffoldPadding = innerPadding,
                    onSave = onSave,
                    onCancel = onNavigateBack,
                )
            }
        }
    }
}

/**
 * The editable form for [EditBookUiState.Ready] (ROADMAP Task 7 Phase D revamp): title, release
 * year, format, total pages, tracking mode, reading status, purchase price, and a bottom Save/
 * Cancel action bar.
 *
 * ### Visual structure (matches [SettingsScreen]/[BookDetailScreen]'s revamped `DetailsTab`)
 * Previously this screen was one undifferentiated scrolling [Column] of text fields and radio
 * groups. It is now four titled [FormSection] cards — the same "title [Text] above a [Card]"
 * convention `SettingsScreen`'s `SettingsSection` established — grouping related fields instead of
 * presenting them as one flat list:
 * 1. **Book details**: title, release year.
 * 2. **Physical**: format, total pages, tracking mode.
 * 3. **Status**: reading status.
 * 4. **Purchase**: purchase price.
 *
 * Save/Cancel move off the bottom of the scrolling content and onto a persistent, non-scrolling
 * action bar ([EditBottomBar]) pinned to the bottom of the screen — a "committed action pair"
 * that's always reachable without scrolling, rather than two inline buttons the user could lose
 * track of below four sections of fields.
 *
 * ### Picking a control per enum (ROADMAP Task 7 Phase D brief)
 * All three enum pickers used to be identical vertical radio-button groups. Each is now the
 * Material 3 control that actually fits its shape:
 * - [TrackingMode] (2 values): a [SingleChoiceSegmentedButtonRow], exactly like `SettingsScreen`'s
 *   week-start-day control — a small, fixed, side-by-side binary choice is precisely what
 *   segmented buttons are for.
 * - [BookFormat] (5 values): an [ExposedDropdownMenuBox] read-only dropdown. Five stacked radios
 *   dominated the old layout's vertical space for a field edited far less often than title/pages;
 *   collapsing it to one closed field (open only on demand) fits a longer, less-frequently-changed
 *   option set better than either radios or a chip row would.
 * - [ReadingStatus] (4 values): the detail screens' [StatusDropdownChip] (#174). It began as a
 *   [FilterChip] row like `LibraryScreen`'s `StatusFilterRow`, but four labels did not fit a phone's
 *   width -- "Did not finish" was cut off at the edge -- and the same enum is chosen through this
 *   chip on Book Detail, so the edit form now matches the screen it was opened from.
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
 * ### Parse-once validation (blank vs. unparseable vs. out-of-range) — unchanged from Phase A
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
 * [BookMetadataValidation.MIN_RELEASE_YEAR]/[BookMetadataValidation.MAX_RELEASE_YEAR] (the same
 * constants the repository itself validates against) rather than a second hardcoded copy, so
 * client-side and server-side validation can never drift apart. The purchase-price field gets a "$" [prefix] and
 * total pages a "pages" [suffix] (new this phase) so each reads as the unit it represents rather
 * than a bare number box; both keep their existing [KeyboardType.Decimal]/[KeyboardType.Number]
 * keyboards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditBookForm(
    state: EditBookUiState.Ready,
    coverStorageDir: String,
    scaffoldPadding: PaddingValues,
    onSave: (
        title: String,
        releaseYear: Int?,
        purchasePrice: Double?,
        totalPages: Int?,
        format: BookFormat,
        status: ReadingStatus,
        trackingMode: TrackingMode,
    ) -> Unit,
    onCancel: () -> Unit,
) {
    var titleText by remember { mutableStateOf(state.title) }
    var releaseYearText by remember { mutableStateOf(state.releaseYear?.toString() ?: "") }
    var purchasePriceText by remember { mutableStateOf(state.purchasePrice?.let(::formatUnit) ?: "") }
    var totalPagesText by remember { mutableStateOf(state.totalPages?.toString() ?: "") }
    var format by remember { mutableStateOf(state.format) }
    var status by remember { mutableStateOf(state.status) }
    var trackingMode by remember { mutableStateOf(state.trackingMode) }

    val titleIsValid = titleText.isNotBlank()

    val parsedReleaseYear = parseOptionalNumber(releaseYearText, String::toIntOrNull)
    val validatedReleaseYear = parsedReleaseYear?.value
    val releaseYearIsValid =
        parsedReleaseYear != null &&
            (
                validatedReleaseYear == null ||
                    validatedReleaseYear in
                    BookMetadataValidation.MIN_RELEASE_YEAR..BookMetadataValidation.MAX_RELEASE_YEAR
            )

    val parsedPurchasePrice = parseOptionalNumber(purchasePriceText, String::toDoubleOrNull)
    val validatedPurchasePrice = parsedPurchasePrice?.value
    val purchasePriceIsValid =
        parsedPurchasePrice != null &&
            (validatedPurchasePrice == null || validatedPurchasePrice >= 0.0)

    val parsedTotalPages = parseOptionalNumber(totalPagesText, String::toIntOrNull)
    val validatedTotalPages = parsedTotalPages?.value
    val totalPagesIsValid =
        parsedTotalPages != null && (validatedTotalPages == null || validatedTotalPages > 0)

    val formIsValid = titleIsValid && releaseYearIsValid && purchasePriceIsValid && totalPagesIsValid

    EditFormBody(
        innerPadding = scaffoldPadding,
        formTag = TestTags.EditBook.FORM,
        isSaving = state.isSaving,
        canSave = formIsValid,
        onCancel = onCancel,
        cancelTag = TestTags.EditBook.CANCEL_BUTTON,
        saveTag = TestTags.EditBook.SAVE_BUTTON,
        onSave = {
            onSave(
                titleText.trim(),
                validatedReleaseYear,
                validatedPurchasePrice,
                validatedTotalPages,
                format,
                status,
                trackingMode,
            )
        },
        aboveBar = {
            val errorMessage = state.errorMessage
            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                )
            }
        },
    ) {
        // The saved title, not titleText: the header names the book being edited while its
        // title field is retyped (#174).
        EditHeader(
            kind = stringResource(R.string.book_detail_kind),
            year = state.releaseYear,
            title = state.title,
            artwork = state.coverImageHash?.let { DetailArtwork(coverStorageDir, it, MediaType.BOOK) },
        )
        FormSection(title = stringResource(R.string.edit_section_book_details)) {
            OutlinedTextField(
                value = titleText,
                onValueChange = { titleText = it },
                label = { Text(stringResource(R.string.edit_title_label)) },
                isError = !titleIsValid,
                supportingText =
                    if (!titleIsValid) {
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
                supportingText =
                    if (!releaseYearIsValid) {
                        {
                            Text(
                                stringResource(
                                    R.string.edit_release_year_invalid_error,
                                    BookMetadataValidation.MIN_RELEASE_YEAR,
                                    BookMetadataValidation.MAX_RELEASE_YEAR,
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
        }

        FormSection(title = stringResource(R.string.edit_section_physical)) {
            FormatDropdownField(
                format = format,
                onFormatChange = { format = it },
                enabled = !state.isSaving,
            )
            OutlinedTextField(
                value = totalPagesText,
                onValueChange = { totalPagesText = it.filterIntegerInput() },
                label = { Text(stringResource(R.string.edit_total_pages_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                suffix = { Text(stringResource(R.string.edit_total_pages_suffix)) },
                isError = !totalPagesIsValid,
                supportingText =
                    if (!totalPagesIsValid) {
                        { Text(stringResource(R.string.edit_total_pages_invalid_error)) }
                    } else {
                        null
                    },
                singleLine = true,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            TrackingModeRow(
                trackingMode = trackingMode,
                onTrackingModeChange = { trackingMode = it },
                enabled = !state.isSaving,
            )
        }

        FormSection(title = stringResource(R.string.edit_section_status)) {
            StatusChipRow(
                status = status,
                onStatusChange = { status = it },
                enabled = !state.isSaving,
                isReread = state.isReread,
            )
        }

        FormSection(title = stringResource(R.string.edit_section_purchase)) {
            OutlinedTextField(
                value = purchasePriceText.toDisplayDecimal(),
                onValueChange = { purchasePriceText = it.filterDecimalInput() },
                label = { Text(stringResource(R.string.edit_purchase_price_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                prefix = { Text(stringResource(R.string.edit_purchase_price_prefix)) },
                isError = !purchasePriceIsValid,
                supportingText =
                    if (!purchasePriceIsValid) {
                        { Text(stringResource(R.string.edit_purchase_price_invalid_error)) }
                    } else {
                        null
                    },
                singleLine = true,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Format picker (ROADMAP Task 7 Phase D): a read-only [ExposedDropdownMenuBox] dropdown over all
 * five [BookFormat] values, replacing the old five-row radio-button group. See [EditBookForm]'s
 * KDoc "Picking a control per enum" section for why a dropdown fits this particular field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatDropdownField(
    format: BookFormat,
    onFormatChange: (BookFormat) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = format.displayLabel(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.edit_format_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            enabled = enabled,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = enabled),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            BookFormat.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.displayLabel()) },
                    onClick = {
                        onFormatChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Tracking-mode picker (ROADMAP Task 7 Phase D): a two-option [SingleChoiceSegmentedButtonRow],
 * mirroring [SettingsScreen]'s week-start-day control exactly — see [EditBookForm]'s KDoc "Picking
 * a control per enum" section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackingModeRow(
    trackingMode: TrackingMode,
    onTrackingModeChange: (TrackingMode) -> Unit,
    enabled: Boolean,
) {
    Column {
        Text(
            text = stringResource(R.string.edit_tracking_mode_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val options = TrackingMode.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = trackingMode == option,
                    onClick = { onTrackingModeChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    enabled = enabled,
                    label = { Text(option.displayLabel()) },
                )
            }
        }
    }
}

/**
 * Reading-status picker (ROADMAP Task 7 Phase D): a labelled [StatusDropdownChip] over all four
 * [ReadingStatus] values -- see [EditBookForm]'s KDoc "Picking a control per enum" section for why
 * it replaced a chip row in #174.
 */
@Composable
private fun StatusChipRow(
    status: ReadingStatus,
    onStatusChange: (ReadingStatus) -> Unit,
    enabled: Boolean,
    isReread: Boolean = false,
) {
    Column {
        Text(
            text = stringResource(R.string.edit_status_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        StatusDropdownChip(
            value = status,
            // Only read #1 may be To read (#230): on a re-read the form leaves it out, as the status menu does.
            options = if (isReread) ReadingStatus.entries - ReadingStatus.TO_READ else ReadingStatus.entries.toList(),
            label = { it.displayLabel() },
            onSelect = onStatusChange,
            onClickLabel = stringResource(R.string.detail_status_change_action_label),
            enabled = enabled,
        )
    }
}

private val PREVIEW_READY_STATE =
    EditBookUiState.Ready(
        title = "The Great Gatsby",
        releaseYear = 1925,
        purchasePrice = 9.99,
        totalPages = 180,
        format = BookFormat.PHYSICAL,
    )

/** Preview of the edit-book screen prefilled with existing metadata (light theme). */
@Preview(showBackground = true)
@Composable
private fun EditBookScreenReadyPreview() {
    MediaTrackerTheme {
        EditBookScreen(
            coverStorageDir = "",
            uiState = PREVIEW_READY_STATE,
            onRetry = {},
            onNavigateBack = {},
            onSave = { _, _, _, _, _, _, _ -> },
        )
    }
}

/** Preview of the edit-book screen prefilled with existing metadata (dark theme). */
@Preview(showBackground = true)
@Composable
private fun EditBookScreenReadyDarkPreview() {
    MediaTrackerTheme(darkTheme = true, dynamicColor = false) {
        EditBookScreen(
            coverStorageDir = "",
            uiState = PREVIEW_READY_STATE,
            onRetry = {},
            onNavigateBack = {},
            onSave = { _, _, _, _, _, _, _ -> },
        )
    }
}

/** Preview of the edit-book screen with a failed-save/validation error message displayed. */
@Preview(showBackground = true)
@Composable
private fun EditBookScreenErrorPreview() {
    MediaTrackerTheme {
        EditBookScreen(
            coverStorageDir = "",
            uiState = PREVIEW_READY_STATE.copy(errorMessage = "Total pages must be a positive number"),
            onRetry = {},
            onNavigateBack = {},
            onSave = { _, _, _, _, _, _, _ -> },
        )
    }
}

/** Preview of the edit-book screen while a save is in flight (fields disabled, Save spinning). */
@Preview(showBackground = true)
@Composable
private fun EditBookScreenSavingPreview() {
    MediaTrackerTheme {
        EditBookScreen(
            coverStorageDir = "",
            uiState = PREVIEW_READY_STATE.copy(isSaving = true),
            onRetry = {},
            onNavigateBack = {},
            onSave = { _, _, _, _, _, _, _ -> },
        )
    }
}

/** Preview of a book with unknown total pages (blank field, no page count on record). */
@Preview(showBackground = true)
@Composable
private fun EditBookScreenUnknownTotalPagesPreview() {
    MediaTrackerTheme {
        EditBookScreen(
            coverStorageDir = "",
            uiState = PREVIEW_READY_STATE.copy(totalPages = null, purchasePrice = null),
            onRetry = {},
            onNavigateBack = {},
            onSave = { _, _, _, _, _, _, _ -> },
        )
    }
}

/** Preview of the edit-book screen with a very long title, to check wrapping/truncation. */
@Preview(showBackground = true)
@Composable
private fun EditBookScreenLongTitlePreview() {
    MediaTrackerTheme {
        EditBookScreen(
            coverStorageDir = "",
            uiState =
                PREVIEW_READY_STATE.copy(
                    title =
                        "The Extraordinarily Long and Overly Descriptive Subtitle-Laden Book Title " +
                            "That Just Keeps Going",
                ),
            onRetry = {},
            onNavigateBack = {},
            onSave = { _, _, _, _, _, _, _ -> },
        )
    }
}

/** Preview of the edit-book screen while the current metadata is still loading. */
@Preview(showBackground = true)
@Composable
private fun EditBookScreenLoadingPreview() {
    MediaTrackerTheme {
        EditBookScreen(
            coverStorageDir = "",
            uiState = EditBookUiState.Loading,
            onRetry = {},
            onNavigateBack = {},
            onSave = { _, _, _, _, _, _, _ -> },
        )
    }
}
