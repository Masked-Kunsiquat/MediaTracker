package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.UpNextViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.components.CoverImage
import com.github.maskedkunisquat.mediatracker.ui.insets.scrollingContentPadding
import com.hub.media.core.database.dao.UpNextRow
import com.hub.media.core.database.entities.MediaType
import com.hub.media.ui.AppContainer
import com.hub.media.ui.MarkedWatchedEvent
import com.hub.media.ui.UpNextUiState
import com.hub.media.ui.UpNextViewModel

/**
 * Route for the "Up next" screen (#89): wires [UpNextViewModel] to the stateless [UpNextScreen],
 * and owns the snackbar that turns a [MarkedWatchedEvent] into an Undo.
 */
@Composable
fun UpNextScreenRoute(
    appContainer: AppContainer,
    coverStorageDir: String,
    onOpenShow: (String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val viewModel: UpNextViewModel =
        viewModel(factory = remember(appContainer) { UpNextViewModelFactory(appContainer) })
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val event = uiState.markedEvent
    val message =
        event?.let {
            stringResource(R.string.up_next_marked_format, it.row.showTitle, it.row.seasonNumber, it.row.episodeNumber)
        }
    val undoLabel = stringResource(R.string.up_next_undo)
    LaunchedEffect(event?.id) {
        if (event != null && message != null) {
            val result = snackbarHostState.showSnackbar(message, undoLabel, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) viewModel.undo(event) else viewModel.consumeMarked(event.id)
        }
    }
    val error = uiState.errorMessage
    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar(error)
            viewModel.consumeError()
        }
    }

    UpNextScreen(
        uiState = uiState,
        coverStorageDir = coverStorageDir,
        snackbarHostState = snackbarHostState,
        onOpenShow = onOpenShow,
        onMarkWatched = viewModel::markWatched,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * The "Up next" queue (#89): the next episode of every started show, most recently watched first.
 * Stateless -- tapping a card opens the show, the check marks that episode watched, and the list
 * advances by itself because the query behind it is reactive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpNextScreen(
    uiState: UpNextUiState,
    coverStorageDir: String,
    snackbarHostState: SnackbarHostState,
    onOpenShow: (String) -> Unit,
    onMarkWatched: (UpNextRow) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.up_next_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .testTag(TestTags.UpNext.LIST),
            contentPadding = scrollingContentPadding(innerPadding, PaddingValues(16.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.items.isEmpty() && !uiState.isLoading) {
                item {
                    Text(
                        text = stringResource(R.string.up_next_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(uiState.items, key = { it.mediaId }) { row ->
                UpNextCard(
                    row = row,
                    coverStorageDir = coverStorageDir,
                    onOpen = { onOpenShow(row.mediaId) },
                    onMarkWatched = { onMarkWatched(row) },
                )
            }
        }
    }
}

private val POSTER_WIDTH = 48.dp
private val POSTER_HEIGHT = 72.dp

@Composable
private fun UpNextCard(
    row: UpNextRow,
    coverStorageDir: String,
    onOpen: () -> Unit,
    onMarkWatched: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Drawn only when there is one, the same rule MismatchReviewScreen follows: a
            // hand-entered show never gets artwork, and a permanent grey block says nothing.
            row.coverImageHash?.let { hash ->
                CoverImage(
                    coverDir = coverStorageDir,
                    coverImageHash = hash,
                    mediaType = MediaType.TV_SHOW,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = POSTER_WIDTH, height = POSTER_HEIGHT),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = row.showTitle, style = MaterialTheme.typography.titleMedium)
                Text(text = row.episodeLabel(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = pluralStringResource(R.plurals.up_next_remaining, row.remaining, row.remaining),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onMarkWatched) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription =
                        stringResource(
                            R.string.up_next_mark_watched_format,
                            row.showTitle,
                            row.seasonNumber,
                            row.episodeNumber,
                        ),
                )
            }
        }
    }
}

/** "S2 · E4 · Title", or "S2 · E4 · Episode 4" for a quick-filled episode with no title. */
@Composable
private fun UpNextRow.episodeLabel(): String {
    val title =
        episodeTitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.up_next_untitled_format, episodeNumber)
    return stringResource(R.string.up_next_episode_format, seasonNumber, episodeNumber, title)
}
