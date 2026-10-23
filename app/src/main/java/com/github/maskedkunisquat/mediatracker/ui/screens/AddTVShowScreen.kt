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
import com.github.maskedkunisquat.mediatracker.ui.AddTVShowViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.components.EditFormScaffold
import com.github.maskedkunisquat.mediatracker.ui.components.EditHeader
import com.github.maskedkunisquat.mediatracker.ui.components.SeasonQuickFillRows
import com.github.maskedkunisquat.mediatracker.ui.components.ShowFieldValidity
import com.github.maskedkunisquat.mediatracker.ui.components.ShowFormContent
import com.hub.media.ui.AddTVShowUiState
import com.hub.media.ui.AddTVShowViewModel
import com.hub.media.ui.AppContainer

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
 * Manual TV show entry with season quick-fill (ROADMAP Task 13 Phase C), in Edit show's layout
 * since #214: the shared [ShowFormContent] cards, with the quick-fill rows as its Seasons card, under
 * a compact header, and Cancel/Save pinned to the bottom edge.
 *
 * Stateless: the values are [AddTVShowUiState.form] and every edit leaves through a callback, so a
 * test can drive it with fabricated state. The values live in [AddTVShowViewModel] because the
 * season list is a dynamic collection `rememberSaveable` cannot carry without hand-written
 * bookkeeping; see its KDoc.
 */
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
    val form = uiState.form

    EditFormScaffold(
        title = stringResource(R.string.add_tv_show_title),
        onNavigateBack = onNavigateBack,
        formTag = TestTags.AddTVShow.FORM,
        isSaving = uiState.isSaving,
        canSave = ShowFieldValidity(form).canSave,
        onCancel = onNavigateBack,
        onSave = onSave,
        cancelTag = TestTags.AddTVShow.CANCEL_BUTTON,
        saveTag = TestTags.AddTVShow.SAVE_BUTTON,
        snackbarHostState = snackbarHostState,
    ) {
        ShowFormContent(
            form = form,
            // Nothing is saved yet, so the header follows the title field rather than a stored one.
            header = {
                EditHeader(
                    kind = stringResource(R.string.tv_show_detail_kind),
                    year = null,
                    title = form.title.ifBlank { stringResource(R.string.add_tv_show_untitled) },
                    artwork = null,
                )
            },
            enabled = !uiState.isSaving,
            flagBlankTitle = false,
            onTitleChange = onTitleChange,
            onReleaseYearChange = onReleaseYearChange,
            onTotalSeasonsChange = onTotalSeasonsChange,
            onPurchasePriceChange = onPurchasePriceChange,
            seasons = {
                SeasonQuickFillRows(
                    rows = form.seasonRows,
                    enabled = !uiState.isSaving,
                    onAdd = onAddSeasonRow,
                    onRemove = onRemoveSeasonRow,
                    onSeasonNumberChange = onSeasonNumberChange,
                    onEpisodeCountChange = onEpisodeCountChange,
                )
            },
        )
    }
}
