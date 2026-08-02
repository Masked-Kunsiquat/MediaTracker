package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.LibraryViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.components.CoverImage
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.ui.AppContainer
import com.hub.media.ui.LibraryUiState
import com.hub.media.ui.LibraryViewModel
import kotlin.time.Instant
import kotlin.time.ExperimentalTime

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
 */
@Composable
fun LibraryScreenRoute(
    appContainer: AppContainer,
    coverStorageDir: String,
    onNavigateToAddBook: () -> Unit,
    onNavigateToBookDetail: (String) -> Unit,
    onNavigateToStats: () -> Unit,
) {
    val viewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModelFactory(appContainer),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LibraryScreen(
        uiState = uiState,
        coverStorageDir = coverStorageDir,
        onNavigateToAddBook = onNavigateToAddBook,
        onBookClick = onNavigateToBookDetail,
        onNavigateToStats = onNavigateToStats,
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
 * @param uiState [LibraryUiState] containing the list of books and isEmpty flag.
 * @param coverStorageDir Absolute path to the cover image storage directory.
 * @param onNavigateToAddBook Called when the FAB is pressed.
 * @param onBookClick Called with the book ID when a card is tapped, to open the book detail
 *   screen.
 * @param onNavigateToStats Called when the TopAppBar's stats icon is tapped, to open the stats
 *   screen (ROADMAP Task 5 Phase C).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    coverStorageDir: String,
    onNavigateToAddBook: () -> Unit,
    onBookClick: (String) -> Unit,
    onNavigateToStats: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = onNavigateToStats) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = stringResource(R.string.stats_content_description),
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.isEmpty) {
                // Empty state
                Column(
                    modifier = Modifier
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
                // Book list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        uiState.books,
                        key = { it.id },
                    ) { book ->
                        BookCard(
                            book = book,
                            coverStorageDir = coverStorageDir,
                            onClick = { onBookClick(book.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A card displaying a single book.
 * Shows the cover thumbnail (left), title (top), and release year (bottom).
 * Tapping anywhere on the row calls [onClick] to open the book detail screen (where deletion
 * now lives -- Task4 Phase E).
 */
@Composable
private fun BookCard(
    book: MediaItemEntity,
    coverStorageDir: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Cover image (thumbnail)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.2f)
                .padding(4.dp),
        ) {
            CoverImage(
                coverDir = coverStorageDir,
                coverImageHash = book.coverImageHash,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Book info (title, year)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.releaseYear != null) {
                Text(
                    text = "Released: ${book.releaseYear}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            uiState = LibraryUiState(
                books = listOf(
                    MediaItemEntity(
                        id = "1",
                        type = MediaType.BOOK,
                        title = "The Great Gatsby",
                        releaseYear = 1925,
                        purchasePrice = 9.99,
                        createdAt = Instant.fromEpochMilliseconds(0),
                        coverImageHash = null,
                    ),
                    MediaItemEntity(
                        id = "2",
                        type = MediaType.BOOK,
                        title = "To Kill a Mockingbird",
                        releaseYear = 1960,
                        purchasePrice = 10.99,
                        createdAt = Instant.fromEpochMilliseconds(0),
                        coverImageHash = null,
                    ),
                ),
                isEmpty = false,
            ),
            coverStorageDir = "/fake/path",
            onNavigateToAddBook = {},
            onBookClick = {},
            onNavigateToStats = {},
        )
    }
}
