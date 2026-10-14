@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import com.github.maskedkunisquat.mediatracker.ui.components.DetailArtwork
import com.github.maskedkunisquat.mediatracker.ui.components.DetailHeader
import com.github.maskedkunisquat.mediatracker.ui.components.DetailStatus
import com.github.maskedkunisquat.mediatracker.ui.components.DetailStatusChip
import com.github.maskedkunisquat.mediatracker.ui.components.DetailSynopsis
import com.github.maskedkunisquat.mediatracker.ui.components.FilmWatchedDialog
import com.github.maskedkunisquat.mediatracker.ui.components.WatchedDatePickerDialog
import com.github.maskedkunisquat.mediatracker.ui.insets.scrollingContentPadding
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.WatchLogEntity
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.ui.AppContainer
import com.hub.media.ui.MovieDetailUiState
import com.hub.media.ui.MovieDetailViewModel
import kotlin.time.Instant

/**
 * Route wrapper: owns the [MovieDetailViewModel] and leaves the screen once the movie is gone.
 */
@Composable
fun MovieDetailScreenRoute(
    appContainer: AppContainer,
    coverStorageDir: String,
    movieId: String,
    onNavigateBack: () -> Unit,
    onNavigateToEditMovie: () -> Unit,
) {
    val viewModel: MovieDetailViewModel =
        viewModel(factory = MovieDetailViewModelFactory(appContainer, movieId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ready = uiState as? MovieDetailUiState.Ready
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.up_next_undo)

    // Watch history (#192). Viewing deletes merge into one Undo; leaving Watched offers its own.
    val viewingDeletedMessage = stringResource(R.string.viewing_deleted_snackbar)
    val viewingUndo = ready?.viewingUndo
    LaunchedEffect(viewingUndo?.id) {
        if (viewingUndo != null) {
            val result =
                snackbarHostState.showSnackbar(
                    viewingDeletedMessage,
                    undoLabel,
                    duration = SnackbarDuration.Short,
                )
            if (result ==
                SnackbarResult.ActionPerformed
            ) {
                viewModel.undoViewing()
            } else {
                viewModel.consumeViewingUndo(viewingUndo.id)
            }
        }
    }
    val statusUndo = ready?.statusUndo
    val movedMessage =
        statusUndo?.let {
            stringResource(
                R.string.film_status_moved_snackbar,
                it.current.displayLabel(),
            )
        }
    LaunchedEffect(statusUndo?.id) {
        if (statusUndo != null && movedMessage != null) {
            val result = snackbarHostState.showSnackbar(movedMessage, undoLabel, duration = SnackbarDuration.Short)
            if (result ==
                SnackbarResult.ActionPerformed
            ) {
                viewModel.undoStatus()
            } else {
                viewModel.consumeStatusUndo(statusUndo.id)
            }
        }
    }

    val title =
        ready
            ?.movie
            ?.item
            ?.title
            .orEmpty()
    if (ready?.pendingWatched == true) {
        FilmWatchedDialog(
            title = stringResource(R.string.film_watched_title, title),
            onConfirm = viewModel::confirmWatched,
            onDismiss = viewModel::dismissWatched,
        )
    }
    if (ready?.pendingWatchedAgain == true) {
        FilmWatchedDialog(
            title = stringResource(R.string.film_watched_again_title, title),
            onConfirm = viewModel::confirmWatchedAgain,
            onDismiss = viewModel::dismissWatchedAgain,
        )
    }
    // By id, and saveable: the picker survives rotation, and a viewing deleted meanwhile closes it.
    var editingViewingId by rememberSaveable { mutableStateOf<String?>(null) }
    ready?.viewings?.firstOrNull { it.id == editingViewingId }?.let { log ->
        WatchedDatePickerDialog(
            initialPickMillis = localDateToUtcMidnightMillis(instantToLocalDateTime(log.watchedAt).toLocalDate()),
            confirmLabel = stringResource(R.string.film_watched_pick_confirm),
            onPicked = {
                viewModel.editViewingDate(log, it)
                editingViewingId = null
            },
            onDismiss = { editingViewingId = null },
        )
    }

    MovieDetailScreen(
        uiState = uiState,
        coverStorageDir = coverStorageDir,
        onStatusChange = viewModel::requestStatus,
        onDelete = viewModel::deleteMovie,
        onErrorShown = viewModel::consumeError,
        onNavigateBack = onNavigateBack,
        onNavigateToEditMovie = onNavigateToEditMovie,
        onWatchedAgain = viewModel::requestWatchedAgain,
        onEditViewing = { editingViewingId = it.id },
        onDeleteViewing = viewModel::deleteViewing,
        snackbarHostState = snackbarHostState,
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
    coverStorageDir: String,
    onStatusChange: (WatchStatus) -> Unit,
    onDelete: () -> Unit,
    onErrorShown: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToEditMovie: () -> Unit = {},
    onWatchedAgain: () -> Unit,
    onEditViewing: (WatchLogEntity) -> Unit,
    onDeleteViewing: (WatchLogEntity) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
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
                // Empty: the title now lives in DetailHeader below, since it no longer needs the
                // top bar to be announced as a heading -- DetailHeader marks it one directly.
                title = {},
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
                        IconButton(onClick = onNavigateToEditMovie) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.edit_movie_content_description),
                            )
                        }
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
            // This screen used to have no scrolling container, and its comment explained why #99's
            // padding move did not apply: nothing here could overflow. **That stopped being true
            // when this branch added a 220dp poster above the status controls.** A short viewport or
            // a large font scale can now push "Abandoned" off the bottom, and without a scroll it
            // would be unreachable -- the same class of failure as a control behind the navigation
            // bar, which #95 already cost this project once.
            //
            // Scrolling brings #99's rule with it: insets as contentPadding rather than padding(),
            // so the content passes under the bars while the last row still clears them.
            // #141: this container owns the 16dp side margin for everything in it. The shared blocks
            // carry vertical padding only, so a screen that already pads its container (TV's
            // LazyColumn contentPadding) does not end up with 32dp.
            modifier =
                Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(scrollingContentPadding(innerPadding, PaddingValues(horizontal = 16.dp))),
        ) {
            when (uiState) {
                is MovieDetailUiState.Loading ->
                    CircularProgressIndicator(
                        modifier =
                            Modifier
                                .padding(vertical = 16.dp)
                                .align(Alignment.CenterHorizontally),
                    )

                // Rendered rather than left blank even though the effect above pops the screen:
                // the pop is not instantaneous, and an empty frame in between reads as a crash.
                is MovieDetailUiState.NotFound ->
                    Text(
                        text = stringResource(R.string.movie_detail_not_found),
                        modifier = Modifier.padding(vertical = 16.dp),
                    )

                is MovieDetailUiState.Ready -> {
                    val movie = uiState.movie
                    val details = movie.details
                    DetailHeader(
                        kind = stringResource(R.string.movie_detail_kind),
                        year = movie.item.releaseYear,
                        title = movie.item.title,
                        subline = formatRuntime(details?.runtimeMinutes),
                        rating = movie.item.communityRating,
                        // Only when there is one. CoverImage draws a placeholder for a null hash,
                        // which is right in the library where every row needs the same shape -- but
                        // at this size it is a large empty panel, and a film entered by hand will
                        // never have artwork, so it would be permanent. Caught by looking at the
                        // re-recorded golden rather than by a test.
                        artwork =
                            movie.item.coverImageHash?.let { hash ->
                                DetailArtwork(
                                    coverStorageDir = coverStorageDir,
                                    coverImageHash = hash,
                                    mediaType = MediaType.MOVIE,
                                )
                            },
                        statusNote = watchedNote(details?.status, details?.watchedAt),
                    ) {
                        DetailStatusChip(movieStatusControl(details?.status, onStatusChange))
                    }
                    DetailSynopsis(text = movie.item.synopsis)
                    MovieHistorySection(
                        viewings = uiState.viewings,
                        isWatched = details?.status == WatchStatus.WATCHED,
                        onWatchedAgain = onWatchedAgain,
                        onEditViewing = onEditViewing,
                        onDeleteViewing = onDeleteViewing,
                    )
                }
            }
        }
    }
}

/**
 * "1h 56m", "56m" under an hour, or [R.string.movie_detail_runtime_unknown] for `null` --
 * [MovieDetailsEntity.runtimeMinutes][com.hub.media.core.database.entities.MovieDetailsEntity.runtimeMinutes]'s
 * KDoc on why `null` and `0` are different facts and must not share a rendering.
 */
@Composable
private fun formatRuntime(minutes: Int?): String {
    if (minutes == null) return stringResource(R.string.movie_detail_runtime_unknown)
    val hours = minutes / 60
    val remainder = minutes % 60
    return if (hours > 0) {
        stringResource(R.string.movie_detail_runtime_hours_minutes, hours, remainder)
    } else {
        stringResource(R.string.movie_detail_runtime_minutes_only, remainder)
    }
}

/**
 * "Watched <date>" for a WATCHED film with a recorded [watchedAt], `null` otherwise -- an
 * ABANDONED-then-WATCHLISTed film keeps a stale [watchedAt] in the data (see
 * [MovieDetailsEntity.watchedAt][com.hub.media.core.database.entities.MovieDetailsEntity.watchedAt]),
 * so [status] is checked too rather than trusting the date alone. Uses the same
 * [DATE_ONLY_FORMATTER] Book's detail screen formats dates with.
 */
@Composable
private fun watchedNote(
    status: WatchStatus?,
    watchedAt: Instant?,
): String? {
    if (status != WatchStatus.WATCHED || watchedAt == null) return null
    val date = DATE_ONLY_FORMATTER.format(instantToLocalDateTime(watchedAt))
    return stringResource(R.string.movie_detail_watched_note, date)
}

/**
 * Film's status mapper (#141 step 2): editable over [WatchStatus.entries], unchanged in look and
 * behaviour from the [StatusDropdownChip] this screen wired directly before the per-domain model
 * existed -- a film's stored status is the real answer, so it stays a four-way picker.
 */
@Composable
private fun movieStatusControl(
    status: WatchStatus?,
    onStatusChange: (WatchStatus) -> Unit,
): DetailStatus.Editable<WatchStatus> =
    DetailStatus.Editable(
        value = status ?: WatchStatus.WATCHLIST,
        options = WatchStatus.entries,
        label = { it.displayLabel() },
        onSelect = onStatusChange,
        onClickLabel = stringResource(R.string.detail_status_change_action_label),
    )
