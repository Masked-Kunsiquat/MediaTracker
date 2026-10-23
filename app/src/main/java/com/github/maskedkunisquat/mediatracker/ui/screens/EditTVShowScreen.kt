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
import com.github.maskedkunisquat.mediatracker.ui.EditTVShowViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.components.DetailArtwork
import com.github.maskedkunisquat.mediatracker.ui.components.EditFormBody
import com.github.maskedkunisquat.mediatracker.ui.components.EditHeader
import com.github.maskedkunisquat.mediatracker.ui.components.EditLoadFailed
import com.github.maskedkunisquat.mediatracker.ui.components.FormScreenMessage
import com.github.maskedkunisquat.mediatracker.ui.components.FormScreenScaffold
import com.github.maskedkunisquat.mediatracker.ui.components.ShowFieldValidity
import com.github.maskedkunisquat.mediatracker.ui.components.ShowFormContent
import com.hub.media.core.database.entities.MediaType
import com.hub.media.ui.AppContainer
import com.hub.media.ui.EditTVShowUiState
import com.hub.media.ui.EditTVShowViewModel

/** Route wrapper: owns the [EditTVShowViewModel] and leaves once the save lands or the show is gone. */
@Composable
fun EditTVShowScreenRoute(
    appContainer: AppContainer,
    showId: String,
    coverStorageDir: String,
    onNavigateBack: () -> Unit,
) {
    val viewModel: EditTVShowViewModel =
        viewModel(factory = EditTVShowViewModelFactory(appContainer, showId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val saved = (uiState as? EditTVShowUiState.Editing)?.saved == true
    LaunchedEffect(saved) {
        if (saved) onNavigateBack()
    }
    LaunchedEffect(uiState) {
        if (uiState is EditTVShowUiState.NotFound) onNavigateBack()
    }

    EditTVShowScreen(
        uiState = uiState,
        coverStorageDir = coverStorageDir,
        onTitleChange = viewModel::onTitleChange,
        onReleaseYearChange = viewModel::onReleaseYearChange,
        onTotalSeasonsChange = viewModel::onTotalSeasonsChange,
        onPurchasePriceChange = viewModel::onPurchasePriceChange,
        onSave = viewModel::save,
        onErrorShown = viewModel::consumeSaveError,
        onRetry = viewModel::retry,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * Stateless edit-show form (#173): title, release year, total seasons, purchase price.
 *
 * No status control -- abandoning is the detail screen's toggle -- and no per-season episode
 * counts, which stay in the season dialog. Since #174 it shares Edit Book's shape: a header,
 * Details and Purchase cards, and the pinned Cancel/Save bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTVShowScreen(
    uiState: EditTVShowUiState,
    coverStorageDir: String,
    onTitleChange: (String) -> Unit,
    onReleaseYearChange: (String) -> Unit,
    onTotalSeasonsChange: (String) -> Unit,
    onPurchasePriceChange: (String) -> Unit,
    onSave: () -> Unit,
    onErrorShown: () -> Unit,
    onRetry: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val form = uiState as? EditTVShowUiState.Editing

    LaunchedEffect(form?.saveError) {
        val message = form?.saveError
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onErrorShown()
        }
    }

    FormScreenScaffold(
        title = stringResource(R.string.edit_tv_show_title),
        onNavigateBack = onNavigateBack,
    ) { innerPadding ->
        if (form == null) {
            FormScreenMessage(innerPadding) {
                if (uiState is EditTVShowUiState.LoadFailed) {
                    EditLoadFailed(onRetry = onRetry, onNavigateBack = onNavigateBack)
                } else {
                    CircularProgressIndicator()
                }
            }
            return@FormScreenScaffold
        }

        EditFormBody(
            innerPadding = innerPadding,
            formTag = TestTags.EditTVShow.FORM,
            isSaving = form.isSaving,
            canSave = ShowFieldValidity(form.form).canSave,
            onCancel = onNavigateBack,
            onSave = onSave,
            cancelTag = TestTags.EditTVShow.CANCEL_BUTTON,
            saveTag = TestTags.EditTVShow.SAVE_BUTTON,
            snackbarHostState = snackbarHostState,
        ) {
            ShowFormContent(
                form = form.form,
                // The title as loaded, not the field: the header names the item while it is retyped.
                header = {
                    EditHeader(
                        kind = stringResource(R.string.tv_show_detail_kind),
                        year = form.savedYear,
                        title = form.savedTitle,
                        artwork = form.coverImageHash?.let { DetailArtwork(coverStorageDir, it, MediaType.TV_SHOW) },
                    )
                },
                enabled = !form.isSaving,
                flagBlankTitle = true,
                onTitleChange = onTitleChange,
                onReleaseYearChange = onReleaseYearChange,
                onTotalSeasonsChange = onTotalSeasonsChange,
                onPurchasePriceChange = onPurchasePriceChange,
            )
        }
    }
}
