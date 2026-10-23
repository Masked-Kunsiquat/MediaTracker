package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.EditMovieViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.components.DetailArtwork
import com.github.maskedkunisquat.mediatracker.ui.components.EditFormBody
import com.github.maskedkunisquat.mediatracker.ui.components.EditHeader
import com.github.maskedkunisquat.mediatracker.ui.components.EditLoadFailed
import com.github.maskedkunisquat.mediatracker.ui.components.FilmFieldValidity
import com.github.maskedkunisquat.mediatracker.ui.components.FilmFormContent
import com.github.maskedkunisquat.mediatracker.ui.components.FilmWatchedDialog
import com.github.maskedkunisquat.mediatracker.ui.components.FormScreenMessage
import com.github.maskedkunisquat.mediatracker.ui.components.FormScreenScaffold
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.ui.AppContainer
import com.hub.media.ui.EditMovieUiState
import com.hub.media.ui.EditMovieViewModel

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
    (uiState as? EditMovieUiState.Editing)?.takeIf { it.form.pendingWatched }?.let { form ->
        FilmWatchedDialog(
            title = stringResource(R.string.film_watched_title, form.form.title),
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

        EditFormBody(
            innerPadding = innerPadding,
            formTag = TestTags.EditMovie.FORM,
            isSaving = form.isSaving,
            canSave = FilmFieldValidity(form.form).canSave,
            onCancel = onNavigateBack,
            onSave = onSave,
            cancelTag = TestTags.EditMovie.CANCEL_BUTTON,
            saveTag = TestTags.EditMovie.SAVE_BUTTON,
            snackbarHostState = snackbarHostState,
        ) {
            FilmFormContent(
                form = form.form,
                // The title as loaded, not the field: the header names the item while it is retyped.
                header = {
                    EditHeader(
                        kind = stringResource(R.string.movie_detail_kind),
                        year = form.savedYear,
                        title = form.savedTitle,
                        artwork = form.coverImageHash?.let { DetailArtwork(coverStorageDir, it, MediaType.MOVIE) },
                    )
                },
                enabled = !form.isSaving,
                onTitleChange = onTitleChange,
                onReleaseYearChange = onReleaseYearChange,
                onRuntimeChange = onRuntimeChange,
                onPurchasePriceChange = onPurchasePriceChange,
                onStatusChange = onStatusChange,
            )
        }
    }
}
