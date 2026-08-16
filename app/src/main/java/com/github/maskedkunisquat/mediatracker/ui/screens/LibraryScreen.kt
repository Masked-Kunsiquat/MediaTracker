package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
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
import com.github.maskedkunisquat.mediatracker.ui.components.BOOK_COVER_ASPECT_RATIO
import com.github.maskedkunisquat.mediatracker.ui.components.CoverImage
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.features.books.data.BookWithDetails
import com.hub.media.ui.AppContainer
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
 * @param onNavigateToBookDetail Callback invoked with a book's id when its card is tapped, to
 *   navigate to the book detail screen (Task4 Phase C).
 * @param onNavigateToStats Callback to navigate to the stats screen (ROADMAP Task 5 Phase C),
 *   wired to the TopAppBar's stats icon.
 * @param onNavigateToSettings Callback to navigate to the Settings screen (ROADMAP Task 7 Phase
 *   B), wired to the TopAppBar's settings icon.
 */
@Composable
fun LibraryScreenRoute(
    appContainer: AppContainer,
    coverStorageDir: String,
    onNavigateToAddBook: () -> Unit,
    onNavigateToBookDetail: (String) -> Unit,
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
        onBookClick = onNavigateToBookDetail,
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
 * Displays a list of all books in the library, or an empty-state message if the library
 * is empty. Each book is rendered as a card with cover thumbnail, title, and release year.
 * A FloatingActionButton navigates to the add-book screen. Tapping a card opens the book
 * detail screen, which is also where deletion now lives (Task4 Phase E) -- see
 * `BookDetailScreen`'s delete icon and confirmation dialog.
 *
 * @param uiState [LibraryUiState] containing the list of books, status filter, and isEmpty flag.
 * @param coverStorageDir Absolute path to the cover image storage directory.
 * @param onNavigateToAddBook Called when the FAB is pressed.
 * @param onBookClick Called with the book ID when a card is tapped, to open the book detail
 *   screen.
 * @param onNavigateToStats Called when the TopAppBar's stats icon is tapped, to open the stats
 *   screen (ROADMAP Task 5 Phase C).
 * @param onNavigateToSettings Called when the TopAppBar's settings icon is tapped, to open the
 *   Settings screen (ROADMAP Task 7 Phase B).
 * @param onStatusFilterChange Called with the newly selected filter (`null` for "All") when a
 *   filter chip is tapped (ROADMAP Task 6 Phase C).
 * @param onSearchQueryChange Called with the new search text on every edit of the search field
 *   (ROADMAP Task 9 Phase A) — filters [uiState.filteredBooks] by title or author, composing with
 *   [onStatusFilterChange]'s filter as an intersection (see [LibraryUiState.filteredBooks]'s KDoc).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    coverStorageDir: String,
    onNavigateToAddBook: () -> Unit,
    onBookClick: (String) -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onStatusFilterChange: (ReadingStatus?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleSelection: (String) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    onDeleteErrorShown: (Long) -> Unit = {},
) {
    var showBulkDeleteConfirmation by remember { mutableStateOf(false) }
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
            FloatingActionButton(onClick = onNavigateToAddBook) {
                Text("+", style = MaterialTheme.typography.headlineLarge)
            }
        },
    ) { innerPadding ->
        if (showBulkDeleteConfirmation) {
            BulkDeleteConfirmationDialog(
                titles = uiState.selectedBooks.map { it.mediaItem.title },
                onConfirm = {
                    showBulkDeleteConfirmation = false
                    onDeleteSelected()
                },
                onDismiss = { showBulkDeleteConfirmation = false },
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
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
                        text = "Your library is empty",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = "Add your first book to get started",
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
                    val filteredBooks = uiState.filteredBooks
                    if (filteredBooks.isEmpty()) {
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
                                    .fillMaxWidth()
                                    .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                filteredBooks,
                                key = { it.mediaItem.id },
                            ) { book ->
                                BookCard(
                                    book = book,
                                    coverStorageDir = coverStorageDir,
                                    onClick = { onBookClick(book.mediaItem.id) },
                                    selectionMode = uiState.isSelectionMode,
                                    selected = book.mediaItem.id in uiState.selectedIds,
                                    onToggleSelection = { onToggleSelection(book.mediaItem.id) },
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
 * [uiState.filteredBooks][LibraryUiState.filteredBooks] by title or author as the user types --
 * see that property's KDoc for the exact match rule and how it composes with [StatusFilterRow].
 * No debounce/minimum-length gate here (unlike the *external*-provider type-ahead search elsewhere
 * in ROADMAP Task 9): this filters an already-in-memory list with no network/DB round-trip per
 * keystroke, so there is nothing expensive to throttle.
 *
 * A trailing clear [IconButton] appears only once [query] is non-empty, mirroring the standard
 * Material search-field affordance.
 */
/**
 * The contextual app bar shown while a selection is active (ROADMAP Task 14 Phase B).
 *
 * The count is the whole selection, not just the part the current filter happens to show. Scoping
 * it to the visible subset made the number change as filters changed, which read as the selection
 * being silently lost -- see [com.hub.media.ui.LibraryUiState.selectedBooks].
 */

/**
 * Confirmation for a bulk delete (ROADMAP Task 14 Phase B), naming the books it will remove.
 *
 * Listing them is what makes deleting the *whole* selection safe rather than alarming. A filter can
 * hide a selected book, so a count alone would ask the user to confirm removing things they cannot
 * currently see; the titles put them back in front of you at the moment it matters. That is also
 * why the list is not capped at some small number without saying so -- a silent truncation would
 * recreate exactly the problem it exists to solve.
 *
 * Long selections stay usable by scrolling rather than by hiding: [MAX_LISTED_TITLES] are named and
 * any remainder is stated explicitly as a count.
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
                .padding(horizontal = 8.dp, vertical = 4.dp),
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
 * Status filter row (ROADMAP Task 6 Phase C): a scrollable row of Material 3 [FilterChip]s --
 * "All" plus one chip per [ReadingStatus] -- chosen over a dropdown since the option count is
 * small (five total) and fixed, so every option can stay visible and one-tap-selectable without
 * an extra open/close step a dropdown would add; [FilterChip]'s built-in selected/unselected
 * visual state also needs no extra styling to show which filter is active.
 */
@Composable
private fun StatusFilterRow(
    selected: ReadingStatus?,
    onSelectedChange: (ReadingStatus?) -> Unit,
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
        items(ReadingStatus.entries.toList()) { status ->
            FilterChip(
                selected = selected == status,
                onClick = { onSelectedChange(status) },
                label = { Text(status.displayLabel()) },
            )
        }
    }
}

/**
 * A card displaying a single book.
 * Shows the cover thumbnail (left), title (top), and release year (bottom).
 * Tapping anywhere on the row calls [onClick] to open the book detail screen (where single-book
 * deletion lives -- Task4 Phase E).
 *
 * ### Selection (ROADMAP Task 14 Phase B)
 * Long-press enters selection mode. While [selectionMode] is active a plain tap toggles selection
 * instead of navigating -- deliberately, because a mode where tapping still opened a book would
 * make selecting several in a row an exercise in precision, and because navigating away mid
 * selection is almost never what was meant. Long-press keeps working while selecting, so the
 * gesture that started the mode is not suddenly inert.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: BookWithDetails,
    coverStorageDir: String,
    onClick: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelection: () -> Unit = {},
) {
    val mediaItem = book.mediaItem
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
        // Cover image (thumbnail). Width is bounded by fillMaxWidth(0.2f); height was previously
        // unconstrained, so a cover's actual pixel aspect dictated row height and rows went
        // ragged. aspectRatio(BOOK_COVER_ASPECT_RATIO) now pins every cover to the same 2:3
        // footprint -- Crop is kept (not Fit) since a uniform card-grid look is the goal here,
        // unlike the detail screen header (see BookHeader) where nothing should be cropped.
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
            )
        }

        // Book info (title, year, status)
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
            val authors = book.details?.authors
            if (!authors.isNullOrBlank()) {
                // Degrades cleanly when absent (ROADMAP Task 9 Phase A): most pre-existing books
                // have no author on record yet (schema v5's MIGRATION_4_5 backfills NULL, honestly,
                // rather than fabricating one) -- this row is simply omitted rather than showing a
                // placeholder, the same pattern releaseYear/status already use below.
                Text(
                    text = authors,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (mediaItem.releaseYear != null) {
                Text(
                    text = "Released: ${mediaItem.releaseYear}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val status = book.details?.status
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
                    books =
                        listOf(
                            BookWithDetails(
                                mediaItem =
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
                            BookWithDetails(
                                mediaItem =
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
            onBookClick = {},
            onNavigateToStats = {},
            onNavigateToSettings = {},
            onStatusFilterChange = {},
            onSearchQueryChange = {},
        )
    }
}
