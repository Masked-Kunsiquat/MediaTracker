package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.AddMovieViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.components.EditFormScaffold
import com.github.maskedkunisquat.mediatracker.ui.components.EditHeader
import com.github.maskedkunisquat.mediatracker.ui.components.FilmFieldValidity
import com.github.maskedkunisquat.mediatracker.ui.components.FilmFormContent
import com.github.maskedkunisquat.mediatracker.ui.components.FilmWatchedDialog
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.ui.AddMovieUiState
import com.hub.media.ui.AddMovieViewModel
import com.hub.media.ui.AppContainer
import com.hub.media.ui.FilmFormState

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
    val form by viewModel.form.collectAsStateWithLifecycle()

    // Navigation is an effect of reaching Saved, not something the button does directly: the save
    // is asynchronous, so the button cannot know the id at click time.
    LaunchedEffect(uiState) {
        val state = uiState
        if (state is AddMovieUiState.Saved) {
            viewModel.reset()
            onMovieAdded(state.mediaId)
        }
    }

    // Choosing Watched asks when (#192); the form's status only changes once this is answered.
    if (form.pendingWatched) {
        FilmWatchedDialog(
            title = stringResource(R.string.film_watched_title, form.title),
            onConfirm = { watchedWhen, _ -> viewModel.confirmWatched(watchedWhen) },
            onDismiss = viewModel::dismissWatched,
        )
    }

    AddMovieScreen(
        uiState = uiState,
        form = form,
        onTitleChange = viewModel::onTitleChange,
        onReleaseYearChange = viewModel::onReleaseYearChange,
        onRuntimeChange = viewModel::onRuntimeChange,
        onPurchasePriceChange = viewModel::onPurchasePriceChange,
        onStatusChange = viewModel::onStatusChange,
        onSave = viewModel::save,
        onErrorShown = viewModel::reset,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * Adding a film by hand (ROADMAP Task 13 Phase B), in Edit film's layout since #214: the shared
 * [FilmFormContent] cards under a compact header, and Cancel/Save pinned to the bottom edge.
 *
 * Stateless: the field values are [form] and the save lifecycle is [uiState], so tests drive it with
 * fabricated state and fake callbacks, per AGENTS.md section 7. The values live in
 * [AddMovieViewModel] (see its `form` KDoc for what that costs), because the same form is rendered
 * by the TMDB review and Edit film from the same [FilmFormState].
 */
@Composable
fun AddMovieScreen(
    uiState: AddMovieUiState,
    form: FilmFormState,
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
    val errorState = uiState as? AddMovieUiState.Error
    LaunchedEffect(errorState) {
        if (errorState != null) {
            snackbarHostState.showSnackbar(errorState.message)
            onErrorShown()
        }
    }

    val isSaving = uiState is AddMovieUiState.Saving

    EditFormScaffold(
        title = stringResource(R.string.add_movie_title),
        onNavigateBack = onNavigateBack,
        formTag = TestTags.AddMovie.FORM,
        isSaving = isSaving,
        canSave = FilmFieldValidity(form).canSave,
        onCancel = onNavigateBack,
        onSave = onSave,
        cancelTag = TestTags.AddMovie.CANCEL_BUTTON,
        saveTag = TestTags.AddMovie.SAVE_BUTTON,
        snackbarHostState = snackbarHostState,
    ) {
        FilmFormContent(
            form = form,
            // Nothing is saved yet, so the header follows the title field rather than a stored one.
            header = {
                EditHeader(
                    kind = stringResource(R.string.movie_detail_kind),
                    year = null,
                    title = form.title.ifBlank { stringResource(R.string.add_movie_untitled) },
                    artwork = null,
                )
            },
            enabled = !isSaving,
            flagBlankTitle = false,
            onTitleChange = onTitleChange,
            onReleaseYearChange = onReleaseYearChange,
            onRuntimeChange = onRuntimeChange,
            onPurchasePriceChange = onPurchasePriceChange,
            onStatusChange = onStatusChange,
        )
    }
}
