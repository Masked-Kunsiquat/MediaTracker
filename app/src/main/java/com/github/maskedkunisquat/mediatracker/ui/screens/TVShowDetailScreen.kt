package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TVShowDetailViewModelFactory
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.ui.AppContainer
import com.hub.media.ui.SeasonGroup
import com.hub.media.ui.TVShowDetailUiState
import com.hub.media.ui.TVShowDetailViewModel

/**
 * Route wrapper: owns the [TVShowDetailViewModel] and leaves the screen once the show is gone.
 */
@Composable
fun TVShowDetailScreenRoute(
    appContainer: AppContainer,
    showId: String,
    onNavigateBack: () -> Unit,
) {
    val viewModel: TVShowDetailViewModel =
        viewModel(factory = TVShowDetailViewModelFactory(appContainer, showId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    TVShowDetailScreen(
        uiState = uiState,
        onEpisodeWatchedChange = viewModel::setEpisodeWatched,
        onSeasonWatchedChange = viewModel::setSeasonWatched,
        onAddSeason = viewModel::addSeason,
        onAbandonedChange = viewModel::setAbandoned,
        onDelete = viewModel::deleteShow,
        onErrorShown = viewModel::consumeError,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * Stateless TV show detail screen (ROADMAP Task 13 Phase C), driven entirely by [uiState] and
 * callbacks so an instrumented test can exercise it without a database -- the TV counterpart of
 * [MovieDetailScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TVShowDetailScreen(
    uiState: TVShowDetailUiState,
    onEpisodeWatchedChange: (String, Boolean) -> Unit,
    onSeasonWatchedChange: (Int, Boolean) -> Unit,
    onAddSeason: (Int, Int) -> Unit,
    onAbandonedChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onErrorShown: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    var showAddSeasonDialog by rememberSaveable { mutableStateOf(false) }

    val errorMessage = (uiState as? TVShowDetailUiState.Ready)?.errorMessage
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(errorMessage)
            onErrorShown()
        }
    }

    // Leaving on NotFound is what makes delete work without the screen having to sequence it: the
    // row vanishes, the flow re-emits NotFound, and this pops. Mirrors MovieDetailScreen.
    LaunchedEffect(uiState) {
        if (uiState is TVShowDetailUiState.NotFound) onNavigateBack()
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.tv_show_detail_delete)) },
            text = { Text(stringResource(R.string.tv_show_detail_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    onDelete()
                }) { Text(stringResource(R.string.tv_show_detail_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            },
        )
    }

    if (showAddSeasonDialog) {
        AddSeasonDialog(
            onDismiss = { showAddSeasonDialog = false },
            onConfirm = { seasonNumber, episodeCount ->
                showAddSeasonDialog = false
                onAddSeason(seasonNumber, episodeCount)
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        (uiState as? TVShowDetailUiState.Ready)
                            ?.show
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
                    if (uiState is TVShowDetailUiState.Ready) {
                        IconButton(onClick = { showDeleteConfirmation = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.tv_show_detail_delete),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        // Lazy, not a scrolling Column: quick-fill exists to create episode rows in bulk (up to
        // TVMetadataValidation.MAX_EPISODE_COUNT of them per season), and a Column would compose
        // every one of them on entry whether or not it is on screen.
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (uiState) {
                is TVShowDetailUiState.Loading ->
                    item {
                        CircularProgressIndicator(modifier = Modifier.fillMaxWidth().wrapContentWidth())
                    }

                // Rendered rather than left blank even though the effect above pops the screen: the
                // pop is not instantaneous, and an empty frame in between reads as a crash.
                is TVShowDetailUiState.NotFound ->
                    item { Text(stringResource(R.string.tv_show_detail_not_found)) }

                is TVShowDetailUiState.Ready -> {
                    item {
                        uiState.show.item.releaseYear?.let { year ->
                            Text(
                                text = stringResource(R.string.library_year_label, year),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        // Parameterized, not concatenated -- see AddMovieScreen's note on the
                        // trailing-space bug concatenation caused there. Quantity keyed on total.
                        Text(
                            text =
                                pluralStringResource(
                                    R.plurals.tv_show_detail_progress,
                                    uiState.totalEpisodes,
                                    uiState.watchedEpisodes,
                                    uiState.totalEpisodes,
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        OutlinedButton(
                            onClick = { onAbandonedChange(!uiState.isAbandoned) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (uiState.isAbandoned) {
                                    stringResource(R.string.tv_show_detail_resume_button)
                                } else {
                                    stringResource(R.string.tv_show_detail_abandon_button)
                                },
                            )
                        }
                    }

                    if (uiState.seasons.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.tv_show_detail_no_seasons),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        // Each season contributes a header item plus one item per episode, rather
                        // than one item holding the whole season: a 500-episode season inside a
                        // single item would compose all of it at once, which is the cost this
                        // LazyColumn exists to avoid.
                        uiState.seasons.forEach { season ->
                            item(key = "season-${season.seasonNumber}") {
                                SeasonHeader(
                                    season = season,
                                    onSeasonWatchedChange = onSeasonWatchedChange,
                                )
                            }
                            items(
                                items = season.episodes,
                                key = { episode -> episode.id },
                            ) { episode ->
                                EpisodeRow(
                                    episode = episode,
                                    seasonNumber = season.seasonNumber,
                                    onWatchedChange = onEpisodeWatchedChange,
                                )
                            }
                        }
                    }

                    item {
                        Button(
                            onClick = { showAddSeasonDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.tv_show_detail_add_season))
                        }
                    }
                }
            }
        }
    }
}

/**
 * One season's header: which season it is, its watched/total count, and the bulk mark/clear
 * control. The season's episodes are emitted as sibling items by the caller's LazyColumn rather
 * than nested inside this composable -- see the note there.
 */
@Composable
private fun SeasonHeader(
    season: SeasonGroup,
    onSeasonWatchedChange: (Int, Boolean) -> Unit,
) {
    val total = season.episodes.size
    val allWatched = total > 0 && season.watchedCount == total

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text =
                stringResource(
                    R.string.tv_show_detail_season_header,
                    season.seasonNumber,
                    season.watchedCount,
                    total,
                ),
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = { onSeasonWatchedChange(season.seasonNumber, !allWatched) }) {
            Text(
                if (allWatched) {
                    stringResource(R.string.tv_show_detail_clear_season)
                } else {
                    stringResource(R.string.tv_show_detail_mark_season_watched)
                },
            )
        }
    }
}

/**
 * One tickable episode row. A `null` [EpisodeEntity.title] shows the episode number instead --
 * quick-filled rows legitimately have no title yet, per that entity's KDoc, and that is normal,
 * not an error state.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@Composable
private fun EpisodeRow(
    episode: EpisodeEntity,
    seasonNumber: Int,
    onWatchedChange: (String, Boolean) -> Unit,
) {
    val label = episode.title ?: stringResource(R.string.tv_show_detail_episode_number_label, episode.episodeNumber)
    val watched = episode.watchedAt != null
    val checkboxDescription =
        stringResource(R.string.tv_show_detail_episode_content_description, seasonNumber, episode.episodeNumber)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = watched,
            onCheckedChange = { onWatchedChange(episode.id, it) },
            modifier = Modifier.semantics { contentDescription = checkboxDescription },
        )
        Text(label)
    }
}

/** A simple two-field dialog for quick-filling a new season onto the show. */
@Composable
private fun AddSeasonDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    var seasonNumber by rememberSaveable { mutableStateOf("") }
    var episodeCount by rememberSaveable { mutableStateOf("") }

    val seasonNumberValue = seasonNumber.toIntOrNull()
    val episodeCountValue = episodeCount.toIntOrNull()
    val canConfirm = seasonNumberValue != null && episodeCountValue != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tv_show_detail_add_season)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = seasonNumber,
                    onValueChange = { seasonNumber = it.filterIntegerInput() },
                    label = { Text(stringResource(R.string.tv_season_number_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = episodeCount,
                    onValueChange = { episodeCount = it.filterIntegerInput() },
                    label = { Text(stringResource(R.string.tv_episode_count_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val season = seasonNumberValue
                    val episodes = episodeCountValue
                    if (season != null && episodes != null) onConfirm(season, episodes)
                },
                enabled = canConfirm,
            ) { Text(stringResource(R.string.tv_show_detail_add_season)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
        },
    )
}
