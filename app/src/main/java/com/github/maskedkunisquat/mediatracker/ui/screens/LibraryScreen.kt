package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.LibraryViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.components.BOOK_COVER_ASPECT_RATIO
import com.github.maskedkunisquat.mediatracker.ui.components.CoverImage
import com.github.maskedkunisquat.mediatracker.ui.insets.barPaddingBelowContent
import com.github.maskedkunisquat.mediatracker.ui.insets.exceptBottom
import com.github.maskedkunisquat.mediatracker.ui.insets.plus
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.dao.TVProgressRow
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.features.media.data.MediaWithDetails
import com.hub.media.ui.AppContainer
import com.hub.media.ui.LibraryStatusFilter
import com.hub.media.ui.LibraryUiState
import com.hub.media.ui.LibraryViewModel
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Route-level composable for the library screen.
 * Connects the [LibraryViewModel] to the stateless [LibraryScreen] and handles navigation.
 *
 * @param appContainer The dependency container for creating ViewModels.
 * @param coverStorageDir Absolute path to the cover image storage directory.
 * @param onNavigateToAddBook Callback to navigate to the add-book screen.
 * @param onNavigateToAddMovie Callback to navigate to the manual add-movie screen.
 * @param onNavigateToAddTVShow Callback to navigate to the manual add-show screen (Task 13 Phase C).
 * @param onNavigateToMediaDetail Callback invoked with a media item's id when its card is tapped.
 * @param onNavigateToStats Callback to navigate to the stats screen (ROADMAP Task 5 Phase C).
 * @param onNavigateToSettings Callback to navigate to the Settings screen (ROADMAP Task 7 Phase B).
 */
@Composable
fun LibraryScreenRoute(
    appContainer: AppContainer,
    coverStorageDir: String,
    onNavigateToAddBook: () -> Unit,
    onNavigateToAddMovie: () -> Unit,
    onNavigateToAddTVShow: () -> Unit,
    onNavigateToMediaDetail: (String, MediaType) -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val viewModel: LibraryViewModel =
        viewModel(
            factory = LibraryViewModelFactory(appContainer),
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LibraryScreen(
        uiState = uiState,
        coverStorageDir = coverStorageDir,
        onNavigateToAddBook = onNavigateToAddBook,
        onNavigateToAddMovie = onNavigateToAddMovie,
        onNavigateToAddTVShow = onNavigateToAddTVShow,
        onMediaClick = onNavigateToMediaDetail,
        onNavigateToStats = onNavigateToStats,
        onNavigateToSettings = onNavigateToSettings,
        onStatusFilterChange = viewModel::setStatusFilter,
        onSearchQueryChange = viewModel::setSearchQuery,
        onToggleSelection = viewModel::toggleSelection,
        onClearSelection = viewModel::clearSelection,
        onDeleteSelected = viewModel::deleteSelected,
        onDeleteErrorShown = viewModel::consumeDeleteError,
    )
}

/**
 * Stateless library screen composable (AGENTS.md §5 State Hoisting).
 *
 * Displays a list of all media items in the library. Consolidated from book-only version per Issue #67.
 *
 * @param uiState [LibraryUiState] containing the list of media, status filter, and isEmpty flag.
 * @param coverStorageDir Absolute path to the cover image storage directory.
 * @param onNavigateToAddBook Called when the add-book action is chosen.
 * @param onNavigateToAddMovie Called when the add-movie action is chosen.
 * @param onNavigateToAddTVShow Called when the add-show action is chosen.
 * @param onMediaClick Called with the media ID **and its type** when a card is tapped. The type is
 *   what lets the caller pick the right detail destination; passing the id alone routed every tap
 *   to Book Detail regardless of type (ROADMAP Task 13 Phase B).
 * @param onNavigateToStats Called when the TopAppBar's stats icon is tapped.
 * @param onNavigateToSettings Called when the TopAppBar's settings icon is tapped.
 * @param onStatusFilterChange Called with the newly selected filter (`null` for "All").
 * @param onSearchQueryChange Called with the new search text — filters [uiState.filteredMedia].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    coverStorageDir: String,
    onNavigateToAddBook: () -> Unit,
    onNavigateToAddMovie: () -> Unit,
    onNavigateToAddTVShow: () -> Unit,
    onMediaClick: (String, MediaType) -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onStatusFilterChange: (LibraryStatusFilter?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleSelection: (String) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    onDeleteErrorShown: (Long) -> Unit = {},
) {
    var showBulkDeleteConfirmation by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // A failed delete previously left the books present, the selection intact, and nothing on
    // screen -- which from the user's side is identical to the Delete button doing nothing. Shown
    // once and then acknowledged, so it reports an event rather than a state the screen sticks in.
    LaunchedEffect(uiState.deleteError?.id) {
        val event = uiState.deleteError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(event.message)
        onDeleteErrorShown(event.id)
    }
    Scaffold(
        // Scaffold's default contentWindowInsets does NOT include the IME, so the keyboard would
        // cover the search field on any device that reports IME insets rather than resizing the
        // window. Passing safeDrawing puts the IME into innerPadding; the content consumes it
        // below so nothing double-pads.
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // Selection swaps the whole bar rather than adding actions to it. The library's own
            // actions (stats, settings) are navigations away, which is precisely what someone
            // part-way through choosing books should not be one mis-tap from doing.
            if (uiState.isSelectionMode) {
                SelectionTopBar(
                    selectedCount = uiState.selectedIds.size,
                    onClose = onClearSelection,
                    onDelete = { showBulkDeleteConfirmation = true },
                )
                return@Scaffold
            }
            CenterAlignedTopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = onNavigateToStats) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = stringResource(R.string.stats_content_description),
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_content_description),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // The FAB now picks a media type rather than going straight to add-book. A menu rather
            // than a second FAB because the library is one list -- two permanent buttons would
            // imply two places to land.
            Box {
                FloatingActionButton(
                    onClick = { showAddMenu = true },
                    modifier = Modifier.testTag(TestTags.Library.ADD_BUTTON),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.add_media_content_description),
                    )
                }
                DropdownMenu(
                    expanded = showAddMenu,
                    onDismissRequest = { showAddMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_book_menu_item)) },
                        onClick = {
                            showAddMenu = false
                            onNavigateToAddBook()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_movie_title)) },
                        onClick = {
                            showAddMenu = false
                            onNavigateToAddMovie()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_tv_show_title)) },
                        onClick = {
                            showAddMenu = false
                            onNavigateToAddTVShow()
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        if (showBulkDeleteConfirmation) {
            BulkDeleteConfirmationDialog(
                titles = uiState.selectedMedia.map { it.item.title },
                onConfirm = {
                    showBulkDeleteConfirmation = false
                    onDeleteSelected()
                },
                onDismiss = { showBulkDeleteConfirmation = false },
            )
        }
        Box(
            // Three insets, three different homes, because this screen is part pinned and part
            // scrolling:
            //   - the keyboard shrinks the viewport, as ever (imePadding, outside everything);
            //   - the top app bar and the horizontal cutout are real padding here, because the
            //     search field and filter row below are pinned and must clear them;
            //   - the navigation bar is *not* applied here. It belongs to the media list as
            //     contentPadding, so cards scroll under the bar instead of stopping above it.
            //     Applying it here is what would put the list back on top of the bar.
            modifier =
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .consumeWindowInsets(innerPadding)
                    .padding(innerPadding.exceptBottom()),
        ) {
            if (uiState.isEmpty) {
                // Empty state
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.library_empty_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = stringResource(R.string.library_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    LibrarySearchField(
                        query = uiState.searchQuery,
                        onQueryChange = onSearchQueryChange,
                    )
                    StatusFilterRow(
                        selected = uiState.statusFilter,
                        onSelectedChange = onStatusFilterChange,
                    )
                    val filteredMedia = uiState.filteredMedia
                    if (filteredMedia.isEmpty()) {
                        // The status filter and/or search query narrowed the (non-empty) library
                        // down to nothing -- distinct from the whole-library-empty case above
                        // (uiState.isEmpty). One shared message covers both filters (and their
                        // combination) rather than trying to distinguish which one is responsible.
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.library_filtered_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .testTag(TestTags.Library.MEDIA_LIST),
                            // barPaddingBelowContent rather than barPaddingForScrollingContent:
                            // the Box above already applied the horizontal inset once, for the
                            // pinned search field, and applying it again would indent the cards
                            // twice as far as the field above them.
                            contentPadding = PaddingValues(8.dp).plus(barPaddingBelowContent()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                filteredMedia,
                                key = { it.item.id },
                            ) { media ->
                                MediaCard(
                                    media = media,
                                    coverStorageDir = coverStorageDir,
                                    tvProgress = uiState.tvProgress[media.item.id],
                                    onClick = { onMediaClick(media.item.id, media.item.type) },
                                    selectionMode = uiState.isSelectionMode,
                                    selected = media.item.id in uiState.selectedIds,
                                    onToggleSelection = { onToggleSelection(media.item.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Local library search field (ROADMAP Task 9 Phase A): a plain [OutlinedTextField] filtering
 * [uiState.filteredMedia][LibraryUiState.filteredMedia] by title or creator as the user types.
 */

/**
 * The contextual app bar shown while a selection is active (ROADMAP Task 14 Phase B).
 */

/**
 * Confirmation for a bulk delete (ROADMAP Task 14 Phase B), naming the items it will remove.
 */
@Composable
private fun BulkDeleteConfirmationDialog(
    titles: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(pluralStringResource(R.plurals.library_bulk_delete_title, titles.size, titles.size))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.library_bulk_delete_message))
                titles.take(MAX_LISTED_TITLES).forEach { title ->
                    Text(
                        text = "• $title",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (titles.size > MAX_LISTED_TITLES) {
                    Text(
                        text =
                            stringResource(
                                R.string.library_bulk_delete_more,
                                titles.size - MAX_LISTED_TITLES,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.library_bulk_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.library_bulk_delete_cancel))
            }
        },
    )
}

/** How many titles the confirmation names before falling back to "and N more". */
private const val MAX_LISTED_TITLES = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.library_selection_count, selectedCount)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.library_selection_close),
                )
            }
        },
        actions = {
            IconButton(onClick = onDelete, enabled = selectedCount > 0) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.library_selection_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
    )
}

@Composable
private fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag(TestTags.Library.SEARCH_FIELD),
        placeholder = { Text(stringResource(R.string.library_search_placeholder)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = stringResource(R.string.library_search_content_description),
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.library_search_clear_content_description),
                    )
                }
            }
        },
        singleLine = true,
    )
}

/**
 * Status filter row (ROADMAP Task 6 Phase C).
 */
@Composable
private fun StatusFilterRow(
    selected: LibraryStatusFilter?,
    onSelectedChange: (LibraryStatusFilter?) -> Unit,
) {
    LazyRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelectedChange(null) },
                label = { Text(stringResource(R.string.library_filter_all)) },
            )
        }
        items(LibraryStatusFilter.entries.toList()) { status ->
            FilterChip(
                selected = selected == status,
                onClick = { onSelectedChange(status) },
                label = { Text(status.filterLabel()) },
            )
        }
    }
}

/**
 * A card displaying a single media item.
 * Consolidated from `BookCard` per Issue #67.
 *
 * @param tvProgress This show's episode counts, or `null` for a non-show and for a show with no
 *   episodes yet -- the library's progress map has no entry for one, since it is grouped by
 *   `mediaId`. Both cases render no progress line at all, which is right: "0 / 0 episodes" on a
 *   show nobody has quick-filled reads as a bug rather than as an empty show.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MediaCard(
    media: MediaWithDetails,
    coverStorageDir: String,
    tvProgress: TVProgressRow?,
    onClick: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelection: () -> Unit = {},
) {
    val mediaItem = media.item
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                ).combinedClickable(
                    onClick = { if (selectionMode) onToggleSelection() else onClick() },
                    onLongClick = onToggleSelection,
                ).semantics { if (selected) this.selected = true }
                .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(0.2f)
                    .padding(4.dp)
                    .aspectRatio(BOOK_COVER_ASPECT_RATIO),
        ) {
            CoverImage(
                coverDir = coverStorageDir,
                coverImageHash = mediaItem.coverImageHash,
                modifier = Modifier.fillMaxSize(),
                mediaType = mediaItem.type,
            )
        }

        // Media info (title, creator, year, status)
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = mediaItem.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val creator =
                when (media) {
                    is MediaWithDetails.Book -> media.details?.authors
                    is MediaWithDetails.Movie,
                    is MediaWithDetails.TVShow,
                    -> null
                }
            if (!creator.isNullOrBlank()) {
                Text(
                    text = creator,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val releaseYear = mediaItem.releaseYear
            if (releaseYear != null) {
                // Formatted rather than concatenated: these strings used to end in a trailing
                // space, which XML strips, so the card rendered "Year:2016".
                val label =
                    when (mediaItem.type) {
                        MediaType.BOOK ->
                            stringResource(R.string.library_released_label, releaseYear)
                        MediaType.MOVIE, MediaType.TV_SHOW ->
                            stringResource(R.string.library_year_label, releaseYear)
                    }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tvProgress != null && tvProgress.totalEpisodes > 0) {
                // The same derived counts the status chip places this show by -- see
                // LibraryStatusFilter.ofShow. Nothing here is read from a stored column.
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.tv_show_detail_progress,
                            tvProgress.totalEpisodes,
                            tvProgress.watchedEpisodes,
                            tvProgress.totalEpisodes,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val status =
                when (media) {
                    is MediaWithDetails.Book -> media.details?.status
                    is MediaWithDetails.Movie,
                    is MediaWithDetails.TVShow,
                    -> null
                }
            if (status != null) {
                Text(
                    text = stringResource(R.string.status_prefix, status.displayLabel()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Preview of the library screen with sample data.
 */
@Preview(showBackground = true)
@OptIn(ExperimentalTime::class)
@Composable
private fun LibraryScreenPreview() {
    MediaTrackerTheme {
        LibraryScreen(
            uiState =
                LibraryUiState(
                    media =
                        listOf(
                            MediaWithDetails.Book(
                                item =
                                    MediaItemEntity(
                                        id = "1",
                                        type = MediaType.BOOK,
                                        title = "The Great Gatsby",
                                        releaseYear = 1925,
                                        purchasePrice = 9.99,
                                        createdAt = Instant.fromEpochMilliseconds(0),
                                        coverImageHash = null,
                                    ),
                                details = null,
                            ),
                            MediaWithDetails.Book(
                                item =
                                    MediaItemEntity(
                                        id = "2",
                                        type = MediaType.BOOK,
                                        title = "To Kill a Mockingbird",
                                        releaseYear = 1960,
                                        purchasePrice = 10.99,
                                        createdAt = Instant.fromEpochMilliseconds(0),
                                        coverImageHash = null,
                                    ),
                                details = null,
                            ),
                        ),
                    isEmpty = false,
                ),
            coverStorageDir = "/fake/path",
            onNavigateToAddBook = {},
            onNavigateToAddMovie = {},
            onNavigateToAddTVShow = {},
            onMediaClick = { _, _ -> },
            onNavigateToStats = {},
            onNavigateToSettings = {},
            onStatusFilterChange = {},
            onSearchQueryChange = {},
        )
    }
}
