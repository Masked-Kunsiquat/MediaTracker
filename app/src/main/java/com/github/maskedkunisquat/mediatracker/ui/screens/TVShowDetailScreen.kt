package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TVShowDetailViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.insets.scrollingContentPadding
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.ui.AppContainer
import com.hub.media.ui.SeasonGroup
import com.hub.media.ui.TVShowDetailUiState
import com.hub.media.ui.TVShowDetailViewModel
import com.hub.media.ui.filterIntegerInput
import com.hub.media.ui.parseRequiredInt

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
        onSetSeasonLength = viewModel::setSeasonLength,
        onRemoveSeason = viewModel::removeSeason,
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
@OptIn(ExperimentalMaterial3Api::class, kotlin.time.ExperimentalTime::class)
@Composable
fun TVShowDetailScreen(
    uiState: TVShowDetailUiState,
    onEpisodeWatchedChange: (String, Boolean) -> Unit,
    onSeasonWatchedChange: (Int, Boolean) -> Unit,
    onSetSeasonLength: (Int, Int) -> Unit,
    onRemoveSeason: (Int) -> Unit,
    onAbandonedChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onErrorShown: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    // null seasonNumber (with showAddSeasonDialog true) means "add a new season", both fields
    // blank. A non-null editSeasonNumber means "change this season's length" -- the season field
    // is then pre-filled and locked, per SeasonLengthDialog's KDoc.
    var showAddSeasonDialog by rememberSaveable { mutableStateOf(false) }
    var editSeasonNumber by rememberSaveable { mutableStateOf<Int?>(null) }
    // A shrink is destructive (see TVShowRepository.setSeasonLength's KDoc), so it is never applied
    // straight from SeasonLengthDialog's confirm -- it lands here first, and only reaches
    // onSetSeasonLength if the user confirms the cost shown below.
    var pendingShrinkSeasonNumber by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingShrinkNewCount by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingRemoveSeasonNumber by rememberSaveable { mutableStateOf<Int?>(null) }

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

    // Same SeasonGroup the rest of the screen already has -- looked up fresh on every use rather
    // than captured when the dialog opened, so pre-filled length and the cost shown below always
    // match the show's current state, not a stale snapshot from when the menu was tapped.
    val readySeasons = (uiState as? TVShowDetailUiState.Ready)?.seasons.orEmpty()

    if (showAddSeasonDialog || editSeasonNumber != null) {
        val editingSeason = editSeasonNumber
        val currentLength = readySeasons.firstOrNull { it.seasonNumber == editingSeason }?.episodes?.size
        SeasonLengthDialog(
            seasonNumber = editingSeason,
            initialEpisodeCount = currentLength,
            onDismiss = {
                showAddSeasonDialog = false
                editSeasonNumber = null
            },
            onConfirm = { seasonNumber, episodeCount ->
                showAddSeasonDialog = false
                editSeasonNumber = null
                val existingLength = readySeasons.firstOrNull { it.seasonNumber == seasonNumber }?.episodes?.size
                if (existingLength != null && episodeCount < existingLength) {
                    // Only a real shrink prompts -- growing or re-entering the same count is not
                    // destructive and applies immediately below.
                    pendingShrinkSeasonNumber = seasonNumber
                    pendingShrinkNewCount = episodeCount
                } else {
                    onSetSeasonLength(seasonNumber, episodeCount)
                }
            },
        )
    }

    val shrinkSeasonNumber = pendingShrinkSeasonNumber
    val shrinkNewCount = pendingShrinkNewCount
    if (shrinkSeasonNumber != null && shrinkNewCount != null) {
        val shrinkingSeason = readySeasons.firstOrNull { it.seasonNumber == shrinkSeasonNumber }
        if (shrinkingSeason != null) {
            // The cost of this shrink, computed here from the SeasonGroup already on screen -- no
            // repository call. Matches TVShowRepository.setSeasonLength's own definition of what a
            // shrink removes: every episode numbered above the new count.
            val removedEpisodes = shrinkingSeason.episodes.filter { it.episodeNumber > shrinkNewCount }
            val removedWatchedCount = removedEpisodes.count { it.watchedAt != null }
            AlertDialog(
                onDismissRequest = {
                    pendingShrinkSeasonNumber = null
                    pendingShrinkNewCount = null
                },
                title = {
                    Text(
                        pluralStringResource(
                            R.plurals.tv_show_detail_shrink_season_title,
                            removedEpisodes.size,
                            removedEpisodes.size,
                        ),
                    )
                },
                text = {
                    // "0 of them are marked watched" reads like a bug, so the zero case says only
                    // that the removal is permanent -- which is still true and still worth saying.
                    Text(
                        if (removedWatchedCount == 0) {
                            stringResource(R.string.tv_show_detail_removal_undone_warning)
                        } else {
                            pluralStringResource(
                                R.plurals.tv_show_detail_episodes_watched_warning,
                                removedWatchedCount,
                                removedWatchedCount,
                            )
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingShrinkSeasonNumber = null
                        pendingShrinkNewCount = null
                        onSetSeasonLength(shrinkSeasonNumber, shrinkNewCount)
                    }) { Text(stringResource(R.string.tv_show_detail_shrink_season_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pendingShrinkSeasonNumber = null
                        pendingShrinkNewCount = null
                    }) { Text(stringResource(R.string.cancel_button)) }
                },
            )
        }
    }

    val removeSeasonNumber = pendingRemoveSeasonNumber
    if (removeSeasonNumber != null) {
        val seasonToRemove = readySeasons.firstOrNull { it.seasonNumber == removeSeasonNumber }
        if (seasonToRemove != null) {
            AlertDialog(
                onDismissRequest = { pendingRemoveSeasonNumber = null },
                title = { Text(stringResource(R.string.tv_show_detail_remove_season_title, removeSeasonNumber)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            pluralStringResource(
                                R.plurals.tv_show_detail_episode_removal_count,
                                seasonToRemove.episodes.size,
                                seasonToRemove.episodes.size,
                            ),
                        )
                        Text(
                            if (seasonToRemove.watchedCount == 0) {
                                stringResource(R.string.tv_show_detail_removal_undone_warning)
                            } else {
                                pluralStringResource(
                                    R.plurals.tv_show_detail_episodes_watched_warning,
                                    seasonToRemove.watchedCount,
                                    seasonToRemove.watchedCount,
                                )
                            },
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingRemoveSeasonNumber = null
                        onRemoveSeason(removeSeasonNumber)
                    }) { Text(stringResource(R.string.tv_show_detail_remove_season)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemoveSeasonNumber = null }) {
                        Text(stringResource(R.string.cancel_button))
                    }
                },
            )
        }
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
            modifier = Modifier.fillMaxSize().consumeWindowInsets(innerPadding),
            // As contentPadding rather than padding(): the season list passes under the top app
            // bar and the navigation bar, and its first and last rows still clear them.
            contentPadding = scrollingContentPadding(innerPadding, PaddingValues(16.dp)),
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
                                    onEditSeasonLength = { editSeasonNumber = season.seasonNumber },
                                    onRemoveSeason = { pendingRemoveSeasonNumber = season.seasonNumber },
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
 * One season's header: which season it is, its watched/total count, the bulk mark/clear control,
 * and an overflow menu for changing the season's length or removing it outright. The season's
 * episodes are emitted as sibling items by the caller's LazyColumn rather than nested inside this
 * composable -- see the note there.
 */
@Composable
private fun SeasonHeader(
    season: SeasonGroup,
    onSeasonWatchedChange: (Int, Boolean) -> Unit,
    onEditSeasonLength: () -> Unit,
    onRemoveSeason: () -> Unit,
) {
    val total = season.episodes.size
    val allWatched = total > 0 && season.watchedCount == total
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // weight(fill = false) lets the header shrink instead of shoving the trailing controls off
        // the right edge. Without it a two-digit episode count ("8 / 10") is wide enough to push
        // the overflow IconButton past the container, where it is clipped -- taking its content
        // description with it -- and overlaps the watched button, so taps aimed at the season menu
        // mark the whole season watched instead.
        Text(
            text =
                stringResource(
                    R.string.tv_show_detail_season_header,
                    season.seasonNumber,
                    season.watchedCount,
                    total,
                ),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onSeasonWatchedChange(season.seasonNumber, !allWatched) }) {
                Text(
                    if (allWatched) {
                        stringResource(R.string.tv_show_detail_clear_season)
                    } else {
                        stringResource(R.string.tv_show_detail_mark_season_watched)
                    },
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription =
                            stringResource(
                                R.string.tv_show_detail_season_menu_content_description,
                                season.seasonNumber,
                            ),
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tv_show_detail_change_episode_count)) },
                        onClick = {
                            showMenu = false
                            onEditSeasonLength()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tv_show_detail_remove_season)) },
                        onClick = {
                            showMenu = false
                            onRemoveSeason()
                        },
                    )
                }
            }
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

/**
 * A two-field dialog for setting a season's length -- shared by the "Add season" button (opened
 * with [seasonNumber] `null`, both fields blank) and each season's "Change episode count" menu
 * item (opened with [seasonNumber] non-null and [initialEpisodeCount] pre-filled).
 *
 * When [seasonNumber] is non-null the season field is pre-filled and disabled rather than merely
 * pre-filled: changing which season a length change applies to mid-dialog is a different intent
 * from the one this dialog was opened for, and letting it happen would attach the entered episode
 * count -- validated against the *original* season's current length by the caller -- to a season
 * it was never checked against. [onConfirm] always reports a concrete season number and episode
 * count; whether that is destructive (a shrink) is decided by the caller, which is why this dialog
 * never itself asks for confirmation.
 *
 * edge-to-edge-exempt: this file's only text fields are the two below, and they live in an
 * [AlertDialog] rendered as a sibling of the screen's `Scaffold` rather than inside its content.
 * An AlertDialog is its own window and repositions itself for the keyboard, so passing
 * `contentWindowInsets` to that Scaffold would pad content that is not affected. Verified on a
 * device: with the keyboard open these fields move from y=931 to y=499 and the buttons from
 * y=1454 to y=1022, all clear of it.
 */
@Composable
private fun SeasonLengthDialog(
    seasonNumber: Int?,
    initialEpisodeCount: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val isEditingExisting = seasonNumber != null
    var seasonNumberText by rememberSaveable { mutableStateOf(seasonNumber?.toString().orEmpty()) }
    var episodeCount by rememberSaveable { mutableStateOf(initialEpisodeCount?.toString().orEmpty()) }

    val seasonNumberValue = seasonNumber ?: parseRequiredInt(seasonNumberText)
    val episodeCountValue = parseRequiredInt(episodeCount)
    val canConfirm = seasonNumberValue != null && episodeCountValue != null
    val titleAndConfirmLabel =
        if (isEditingExisting) {
            R.string.tv_show_detail_change_episode_count
        } else {
            R.string.tv_show_detail_add_season
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleAndConfirmLabel)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = seasonNumberText,
                    onValueChange = { seasonNumberText = it.filterIntegerInput() },
                    label = { Text(stringResource(R.string.tv_season_number_label)) },
                    enabled = !isEditingExisting,
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
            ) {
                Text(
                    if (isEditingExisting) {
                        stringResource(R.string.save_button)
                    } else {
                        stringResource(titleAndConfirmLabel)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
        },
    )
}
