package com.github.maskedkunisquat.mediatracker.ui.screens

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.StateFlow
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.AddBookViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.features.books.domain.MIN_SEARCH_QUERY_LENGTH
import com.hub.media.features.books.network.BookSearchResult
import com.hub.media.ui.AddBookUiState
import com.hub.media.ui.AddBookViewModel
import com.hub.media.ui.AddSearchState
import com.hub.media.ui.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "AddBookScreen"

/**
 * Route-level composable for the add-book screen.
 * Connects the [AddBookViewModel] to the stateless [AddBookScreen] and handles navigation.
 *
 * When the submission succeeds ([AddBookUiState.Success]), this composable:
 * 1. Calls [onNavigateToLibrary] to navigate back to the library.
 * 2. Calls [viewModel.reset()] to clear the success state for next use.
 *
 * @param appContainer The dependency container for creating ViewModels.
 * @param onNavigateBack Callback to navigate back (back button or success).
 * @param onNavigateToLibrary Callback to navigate to library after success.
 */
@Composable
fun AddBookScreenRoute(
    appContainer: AppContainer,
    onNavigateBack: () -> Unit,
    onNavigateToLibrary: () -> Unit,
) {
    val viewModel: AddBookViewModel =
        viewModel(
            factory = AddBookViewModelFactory(appContainer),
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // When the submission succeeds, navigate back to library, clear search, and reset the ViewModel.
    LaunchedEffect(uiState) {
        if (uiState is AddBookUiState.Success) {
            viewModel.clearSearch()
            onNavigateToLibrary()
            viewModel.reset()
        }
    }

    AddBookScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onSubmitIsbn = { isbn -> viewModel.addBook(isbn) },
        onSearchQueryChange = { query -> viewModel.search(query) },
        onSelectSearchResult = { result -> viewModel.selectSearchResult(result) },
        searchQuery = viewModel.searchQuery,
        searchResults = viewModel.searchResults,
        searchState = viewModel.searchState,
        onClearSearch = { viewModel.clearSearch() },
    )
}

/**
 * Stateless add-book screen composable (AGENTS.md §5 State Hoisting).
 *
 * Displays two tabs:
 * 1. **Search tab**: Title/author search field with a dropdown of results. Selecting a result
 *    resolves it to an ISBN and initiates ingestion.
 * 2. **ISBN tab**: Direct ISBN entry field with submit button (existing functionality).
 *
 * Both tabs render the terminal [AddBookUiState]:
 * - [AddBookUiState.Idle]: input enabled, buttons active.
 * - [AddBookUiState.Loading]: CircularProgressIndicator, input disabled, buttons disabled.
 * - [AddBookUiState.Error]: error message displayed, input remains enabled for retry.
 * - [AddBookUiState.Success]: This state is NOT rendered here — the route-level composable
 *   should navigate before this screen is re-rendered.
 *
 * The TopAppBar has a back navigation icon.
 *
 * @param uiState [AddBookUiState] representing the current state of the add submission.
 * @param onNavigateBack Called when the back icon is pressed.
 * @param onSubmitIsbn Called with the ISBN value when the submit button is pressed (ISBN tab).
 * @param onSearchQueryChange Called when the search field text changes (Search tab).
 * @param onSelectSearchResult Called when a search result is selected (Search tab).
 * @param searchQuery The current search query [StateFlow].
 * @param searchResults The list of search results [StateFlow].
 * @param searchState The state of the search process [StateFlow].
 * @param onClearSearch Called to clear the search field and results.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBookScreen(
    uiState: AddBookUiState,
    onNavigateBack: () -> Unit,
    onSubmitIsbn: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit = {},
    onSelectSearchResult: (BookSearchResult) -> Unit = {},
    searchQuery: StateFlow<String>? = null,
    searchResults: StateFlow<List<BookSearchResult>>? = null,
    searchState: StateFlow<AddSearchState>? = null,
    onClearSearch: () -> Unit = {},
) {
    var isbnInput by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Add Book") },
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
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            // Tab row for Search and ISBN modes
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Search") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("ISBN") },
                )
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
            ) {
                when (selectedTab) {
                    0 -> SearchTabContent(
                        uiState = uiState,
                        searchQuery = searchQuery,
                        searchResults = searchResults,
                        searchState = searchState,
                        onSearchQueryChange = onSearchQueryChange,
                        onSelectSearchResult = onSelectSearchResult,
                        onClearSearch = onClearSearch,
                    )
                    else -> IsbnTabContent(
                        uiState = uiState,
                        isbnInput = isbnInput,
                        onIsbnChange = { newValue ->
                            isbnInput =
                                newValue
                                    .filter { char: Char ->
                                        char.isDigit() || char.uppercaseChar() == 'X'
                                    }.take(13)
                        },
                        onSubmitIsbn = onSubmitIsbn,
                    )
                }
            }
        }
    }
}

/**
 * Search tab content: search field with results dropdown (ROADMAP Task 9 Phase B2).
 */
@Composable
private fun SearchTabContent(
    uiState: AddBookUiState,
    searchQuery: StateFlow<String>?,
    searchResults: StateFlow<List<BookSearchResult>>?,
    searchState: StateFlow<AddSearchState>?,
    onSearchQueryChange: (String) -> Unit,
    onSelectSearchResult: (BookSearchResult) -> Unit,
    onClearSearch: () -> Unit,
) {
    val currentSearchQuery by searchQuery?.collectAsStateWithLifecycle() ?: remember { mutableStateOf("") }
    val currentSearchResults by searchResults?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyList()) }
    val currentSearchState by searchState?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(AddSearchState.Idle) }
    val isAddLoading = uiState is AddBookUiState.Loading

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Search input field
        Box(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = currentSearchQuery,
                onValueChange = onSearchQueryChange,
                label = { Text("Search by title or author") },
                placeholder = { Text("Type at least 3 characters") },
                enabled = !isAddLoading,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    if (currentSearchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                onClearSearch()
                            },
                            enabled = !isAddLoading,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search",
                            )
                        }
                    }
                },
            )
        }

        // Helpful text based on search state
        HelpfulSearchText(
            query = currentSearchQuery,
            searchState = currentSearchState,
            isAddLoading = isAddLoading,
        )

        // Results list or state message
        SearchResultsSection(
            query = currentSearchQuery,
            results = currentSearchResults,
            searchState = currentSearchState,
            isAddLoading = isAddLoading,
            onSelectResult = onSelectSearchResult,
        )
    }
}

/**
 * ISO tab content: ISBN entry field and submit button (unchanged from before).
 */
@Composable
private fun IsbnTabContent(
    uiState: AddBookUiState,
    isbnInput: String,
    onIsbnChange: (String) -> Unit,
    onSubmitIsbn: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val isLoading = uiState is AddBookUiState.Loading
        val isError = uiState is AddBookUiState.Error

        OutlinedTextField(
            value = isbnInput,
            onValueChange = onIsbnChange,
            label = { Text("ISBN") },
            placeholder = { Text("e.g., 978-0-13-110362-7") },
            enabled = !isLoading,
            isError = isError,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (isError) {
            Text(
                text = (uiState as AddBookUiState.Error).message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (isLoading) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        Button(
            onClick = { onSubmitIsbn(isbnInput) },
            enabled = !isLoading && isbnInput.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add Book")
        }
    }
}

/**
 * Displays helpful text based on search state (too short, searching, no results, error, etc.).
 */
@Composable
private fun HelpfulSearchText(
    query: String,
    searchState: AddSearchState,
    isAddLoading: Boolean,
) {
    val textColor =
        when {
            isAddLoading -> MaterialTheme.colorScheme.onSurfaceVariant
            searchState is AddSearchState.Error -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    val text =
        when {
            query.isEmpty() -> "Search by title or author (min $MIN_SEARCH_QUERY_LENGTH chars)"
            query.length < MIN_SEARCH_QUERY_LENGTH -> "Keep typing... (${MIN_SEARCH_QUERY_LENGTH - query.length} more chars)"
            searchState is AddSearchState.Searching -> "Searching..."
            searchState is AddSearchState.NoResults -> "No books found"
            searchState is AddSearchState.Error -> searchState.message
            else -> ""
        }

    if (text.isNotEmpty()) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Displays search results as a scrollable list, or loading/error state.
 */
@Composable
private fun SearchResultsSection(
    query: String,
    results: List<BookSearchResult>,
    searchState: AddSearchState,
    isAddLoading: Boolean,
    onSelectResult: (BookSearchResult) -> Unit,
) {
    // Only show results/loading when query is long enough
    if (query.length < MIN_SEARCH_QUERY_LENGTH) {
        return
    }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(250.dp),
    ) {
        when {
            searchState is AddSearchState.Searching -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(250.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            results.isNotEmpty() -> {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = results,
                        key = { result -> result.workKey ?: result.title },
                    ) { result ->
                        SearchResultRow(
                            result = result,
                            enabled = !isAddLoading,
                            onSelect = onSelectResult,
                        )
                    }
                }
            }

            searchState is AddSearchState.Error -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = searchState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * One row in the search results list.
 */
@Composable
private fun SearchResultRow(
    result: BookSearchResult,
    enabled: Boolean,
    onSelect: (BookSearchResult) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = enabled) { onSelect(result) }
                .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail
        SearchResultThumbnail(
            coverThumbnailUrl = result.coverThumbnailUrl,
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(4.dp)),
        )

        // Title + author + metadata
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .align(Alignment.Top),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (result.authors.isNotEmpty()) {
                Text(
                    text = result.authors.first(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Year and edition count
            val metadata =
                buildString {
                    result.firstPublishYear?.let { year ->
                        append("($year)")
                    }
                    result.editionCount?.let { count ->
                        if (isNotEmpty()) append(" ")
                        append("$count editions")
                    }
                }
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Loads and displays a thumbnail for a search result.
 * Falls back to a placeholder if the URL is null or loading fails.
 */
@Composable
private fun SearchResultThumbnail(
    coverThumbnailUrl: String?,
    modifier: Modifier = Modifier,
) {
    if (coverThumbnailUrl.isNullOrBlank()) {
        Box(
            modifier =
                modifier.background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("📖", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val imageBitmap: ImageBitmap? =
        produceState<ImageBitmap?>(
            initialValue = null,
            coverThumbnailUrl,
        ) {
            value =
                withContext(Dispatchers.IO) {
                    try {
                        val url = java.net.URL(coverThumbnailUrl)
                        val connection = url.openConnection() as java.net.HttpURLConnection
                        connection.connectTimeout = 5000
                        connection.readTimeout = 5000
                        val inputStream = connection.inputStream
                        BitmapFactory
                            .decodeStream(inputStream)
                            ?.asImageBitmap()
                    } catch (e: IOException) {
                        Log.w(TAG, "Failed to load search result thumbnail: $coverThumbnailUrl", e)
                        null
                    } catch (e: Exception) {
                        Log.w(TAG, "Unexpected error loading thumbnail: $coverThumbnailUrl", e)
                        null
                    }
                }
        }.value

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = "Book cover",
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier =
                modifier.background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("📖", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * Preview of the ISBN tab in idle state.
 */
@Preview(showBackground = true)
@Composable
private fun AddBookScreenIsbnPreview() {
    MediaTrackerTheme {
        AddBookScreen(
            uiState = AddBookUiState.Idle,
            onNavigateBack = {},
            onSubmitIsbn = {},
        )
    }
}

/**
 * Preview of the ISBN tab in loading state.
 */
@Preview(showBackground = true)
@Composable
private fun AddBookScreenIsbnLoadingPreview() {
    MediaTrackerTheme {
        AddBookScreen(
            uiState = AddBookUiState.Loading,
            onNavigateBack = {},
            onSubmitIsbn = {},
        )
    }
}

/**
 * Preview of the ISBN tab in error state.
 */
@Preview(showBackground = true)
@Composable
private fun AddBookScreenIsbnErrorPreview() {
    MediaTrackerTheme {
        AddBookScreen(
            uiState = AddBookUiState.Error("Invalid ISBN format. Please check and try again."),
            onNavigateBack = {},
            onSubmitIsbn = {},
        )
    }
}

/**
 * Preview of the search tab with empty query.
 */
@Preview(showBackground = true)
@Composable
private fun SearchTabEmptyPreview() {
    MediaTrackerTheme {
        SearchTabContent(
            uiState = AddBookUiState.Idle,
            searchQuery = null,
            searchResults = null,
            searchState = null,
            onSearchQueryChange = {},
            onSelectSearchResult = {},
            onClearSearch = {},
        )
    }
}

/**
 * Preview of the search tab with sample results.
 */
@Preview(showBackground = true)
@Composable
private fun SearchTabWithResultsPreview() {
    val sampleResults =
        listOf(
            BookSearchResult(
                title = "The Hobbit",
                authors = listOf("J.R.R. Tolkien"),
                firstPublishYear = 1937,
                editionCount = 500,
                coverThumbnailUrl = null,
                provider = com.hub.media.core.database.entities.IdentifierProvider.OPEN_LIBRARY,
                workKey = "/works/OL27482W",
                coverEditionKey = "OL51711263M",
            ),
            BookSearchResult(
                title = "The Lord of the Rings",
                authors = listOf("J.R.R. Tolkien", "Alan Lee"),
                firstPublishYear = 1954,
                editionCount = 800,
                coverThumbnailUrl = null,
                provider = com.hub.media.core.database.entities.IdentifierProvider.OPEN_LIBRARY,
                workKey = "/works/OL27448W",
                coverEditionKey = "OL51711254M",
            ),
        )

    MediaTrackerTheme {
        SearchTabContent(
            uiState = AddBookUiState.Idle,
            searchQuery =
                MutableStateFlow("The Hobbit"), // Use MutableStateFlow for preview
            searchResults = MutableStateFlow(sampleResults),
            searchState = MutableStateFlow(AddSearchState.Idle),
            onSearchQueryChange = {},
            onSelectSearchResult = {},
            onClearSearch = {},
        )
    }
}
