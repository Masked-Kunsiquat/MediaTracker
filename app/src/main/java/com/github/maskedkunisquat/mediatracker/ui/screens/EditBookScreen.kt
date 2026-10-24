package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.EditBookViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.components.BookFormBody
import com.github.maskedkunisquat.mediatracker.ui.components.DetailArtwork
import com.github.maskedkunisquat.mediatracker.ui.components.EditHeader
import com.github.maskedkunisquat.mediatracker.ui.components.EditLoadFailed
import com.github.maskedkunisquat.mediatracker.ui.components.FormScreenMessage
import com.github.maskedkunisquat.mediatracker.ui.components.FormScreenScaffold
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.features.books.domain.BookFormValues
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
                BookFormBody(
                    initial = uiState.toFormValues(),
                    innerPadding = innerPadding,
                    // The saved title, not the field: the header names the book being edited while
                    // its title field is retyped (#174).
                    header = {
                        EditHeader(
                            kind = stringResource(R.string.book_detail_kind),
                            year = uiState.releaseYear,
                            title = uiState.title,
                            artwork =
                                uiState.coverImageHash?.let {
                                    DetailArtwork(
                                        coverStorageDir,
                                        it,
                                        MediaType.BOOK,
                                    )
                                },
                        )
                    },
                    isReread = uiState.isReread,
                    isSaving = uiState.isSaving,
                    errorMessage = uiState.errorMessage,
                    formTag = TestTags.EditBook.FORM,
                    saveTag = TestTags.EditBook.SAVE_BUTTON,
                    cancelTag = TestTags.EditBook.CANCEL_BUTTON,
                    onSave = { values ->
                        onSave(
                            values.title,
                            values.releaseYear,
                            values.purchasePrice,
                            values.totalPages,
                            values.format,
                            values.status,
                            values.trackingMode,
                        )
                    },
                    onCancel = onNavigateBack,
                )
            }
        }
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

/** The loaded book as the shared form's starting values (#214). */
private fun EditBookUiState.Ready.toFormValues(): BookFormValues =
    BookFormValues(
        title = title,
        releaseYear = releaseYear,
        purchasePrice = purchasePrice,
        totalPages = totalPages,
        format = format,
        status = status,
        trackingMode = trackingMode,
    )
