package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.MovieDetailViewModelFactory
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.ui.AppContainer
import com.hub.media.ui.MovieDetailUiState
import com.hub.media.ui.MovieDetailViewModel

/**
 * Route wrapper: owns the [MovieDetailViewModel] and leaves the screen once the movie is gone.
 */
@Composable
fun MovieDetailScreenRoute(
    appContainer: AppContainer,
    movieId: String,
    onNavigateBack: () -> Unit,
) {
    val viewModel: MovieDetailViewModel =
        viewModel(factory = MovieDetailViewModelFactory(appContainer, movieId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    MovieDetailScreen(
        uiState = uiState,
        onStatusChange = viewModel::updateStatus,
        onDelete = viewModel::deleteMovie,
        onErrorShown = viewModel::consumeError,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * Stateless movie detail screen (ROADMAP Task 13 Phase B), driven entirely by [uiState] and
 * callbacks so an instrumented test can exercise it without a database.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    uiState: MovieDetailUiState,
    onStatusChange: (WatchStatus) -> Unit,
    onDelete: () -> Unit,
    onErrorShown: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }

    val errorMessage = (uiState as? MovieDetailUiState.Ready)?.errorMessage
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(errorMessage)
            onErrorShown()
        }
    }

    // Leaving on NotFound is what makes delete work without the screen having to sequence it: the
    // row vanishes, the flow re-emits NotFound, and this pops. It also covers the movie being
    // deleted from elsewhere while this screen is open.
    LaunchedEffect(uiState) {
        if (uiState is MovieDetailUiState.NotFound) onNavigateBack()
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.movie_detail_delete)) },
            text = { Text(stringResource(R.string.movie_detail_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    onDelete()
                }) { Text(stringResource(R.string.movie_detail_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        (uiState as? MovieDetailUiState.Ready)
                            ?.movie
                            ?.item
                            ?.title
                            .orEmpty(),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                actions = {
                    if (uiState is MovieDetailUiState.Ready) {
                        IconButton(onClick = { showDeleteConfirmation = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.movie_detail_delete),
                            )
                        }
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
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (uiState) {
                is MovieDetailUiState.Loading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))

                // Rendered rather than left blank even though the effect above pops the screen:
                // the pop is not instantaneous, and an empty frame in between reads as a crash.
                is MovieDetailUiState.NotFound ->
                    Text(stringResource(R.string.movie_detail_not_found))

                is MovieDetailUiState.Ready -> {
                    val movie = uiState.movie
                    movie.item.releaseYear?.let { year ->
                        Text(
                            text = stringResource(R.string.library_year_label, year),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Text(
                        text =
                            movie.details?.runtimeMinutes?.let {
                                stringResource(R.string.movie_detail_runtime, it)
                            } ?: stringResource(R.string.movie_detail_runtime_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.add_movie_status_label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WatchStatus.entries.forEach { option ->
                            FilterChip(
                                selected = movie.details?.status == option,
                                onClick = { onStatusChange(option) },
                                label = { Text(option.displayLabel()) },
                            )
                        }
                    }
                }
            }
        }
    }
}
