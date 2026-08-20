package com.github.maskedkunisquat.mediatracker.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.AddBookViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.warn
import com.hub.media.features.books.network.BookEditionSearchResult
import com.hub.media.features.media.domain.MIN_SEARCH_QUERY_LENGTH
import com.hub.media.features.media.network.MediaSearchResult
import com.hub.media.ui.AddBookUiState
import com.hub.media.ui.AddBookViewModel
import com.hub.media.ui.AddSearchErrorReason
import com.hub.media.ui.AddSearchState
import com.hub.media.ui.AppContainer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

private const val TAG = "AddBookScreen"

private const val TAB_SEARCH = 0
private const val TAB_ISBN = 1

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
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val confirmationResult by viewModel.confirmationResult.collectAsStateWithLifecycle()
    val editions by viewModel.editions.collectAsStateWithLifecycle()

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
        searchQuery = searchQuery,
        searchResults = searchResults,
        searchState = searchState,
        confirmationResult = confirmationResult,
        editions = editions,
        onNavigateBack = onNavigateBack,
        onSubmitIsbn = { isbn -> viewModel.addBook(isbn) },
        onSearchQueryChange = { query -> viewModel.search(query) },
        onSelectSearchResult = { result -> viewModel.selectSearchResult(result) },
        onClearSearch = { viewModel.clearSearch() },
        onConfirmSelection = { viewModel.confirmSelection() },
        onCancelSelection = { viewModel.cancelSelection() },
        onSelectEdition = { edition -> viewModel.selectEdition(edition) },
        httpClient = appContainer.httpClient,
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
    searchQuery: String,
    searchResults: List<MediaSearchResult>,
    searchState: AddSearchState,
    confirmationResult: MediaSearchResult?,
    editions: List<BookEditionSearchResult> = emptyList(),
    onNavigateBack: () -> Unit,
    onSubmitIsbn: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit = {},
    onSelectSearchResult: (MediaSearchResult) -> Unit = {},
    onClearSearch: () -> Unit = {},
    onConfirmSelection: () -> Unit = {},
    onCancelSelection: () -> Unit = {},
    onSelectEdition: (BookEditionSearchResult) -> Unit = {},
    httpClient: HttpClient? = null,
    logger: Logger = AppLogger,
) {
    var isbnInput by rememberSaveable { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_SEARCH) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.add_book_screen_title)) },
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
                    selected = selectedTab == TAB_SEARCH,
                    onClick = { selectedTab = TAB_SEARCH },
                    text = { Text(stringResource(R.string.add_book_tab_search)) },
                )
                Tab(
                    selected = selectedTab == TAB_ISBN,
                    onClick = { selectedTab = TAB_ISBN },
                    text = { Text(stringResource(R.string.add_book_tab_isbn)) },
                )
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
            ) {
                when (selectedTab) {
                    TAB_SEARCH ->
                        SearchTabContent(
                            uiState = uiState,
                            searchQuery = searchQuery,
                            searchResults = searchResults,
                            searchState = searchState,
                            onSearchQueryChange = onSearchQueryChange,
                            onSelectSearchResult = onSelectSearchResult,
                            onClearSearch = onClearSearch,
                            httpClient = httpClient,
                            logger = logger,
                        )
                    TAB_ISBN ->
                        IsbnTabContent(
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

        confirmationResult?.let { result ->
            val isEditionFlow =
                !result.workKey.isNullOrBlank() ||
                    searchState is AddSearchState.ResolvingEditions ||
                    editions.isNotEmpty()

            if (isEditionFlow) {
                EditionSelectionDialog(
                    work = result,
                    editions = editions,
                    isResolving = searchState is AddSearchState.ResolvingEditions,
                    onSelectEdition = onSelectEdition,
                    onCancel = onCancelSelection,
                    httpClient = httpClient,
                    logger = logger,
                )
            } else {
                AlertDialog(
                    onDismissRequest = onCancelSelection,
                    title = { Text(stringResource(R.string.add_book_search_confirm_title)) },
                    text = {
                        Text(
                            stringResource(
                                R.string.add_book_search_confirm_message,
                                result.title,
                            ),
                        )
                    },
                    confirmButton = {
                        Button(onClick = onConfirmSelection) {
                            Text(stringResource(R.string.add_book_search_confirm_button))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onCancelSelection) {
                            Text(stringResource(R.string.cancel_button))
                        }
                    },
                )
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
    searchQuery: String,
    searchResults: List<MediaSearchResult>,
    searchState: AddSearchState,
    onSearchQueryChange: (String) -> Unit,
    onSelectSearchResult: (MediaSearchResult) -> Unit,
    onClearSearch: () -> Unit,
    httpClient: HttpClient?,
    logger: Logger,
) {
    val isAddLoading = uiState is AddBookUiState.Loading

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Search input field
        Box(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                label = { Text(stringResource(R.string.add_book_search_label)) },
                placeholder = { Text(stringResource(R.string.add_book_search_placeholder, MIN_SEARCH_QUERY_LENGTH)) },
                enabled = !isAddLoading,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                onClearSearch()
                            },
                            enabled = !isAddLoading,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = stringResource(R.string.add_book_search_clear_content_description),
                            )
                        }
                    }
                },
            )
        }

        // Helpful text based on search state
        HelpfulSearchText(
            query = searchQuery,
            searchState = searchState,
            isAddLoading = isAddLoading,
        )

        // Results list or state message
        SearchResultsSection(
            query = searchQuery,
            results = searchResults,
            searchState = searchState,
            isAddLoading = isAddLoading,
            onSelectResult = onSelectSearchResult,
            httpClient = httpClient,
            logger = logger,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * ISBN tab content: ISBN entry field and submit button (unchanged from before).
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
            label = { Text(stringResource(R.string.add_book_isbn_label)) },
            placeholder = { Text(stringResource(R.string.add_book_isbn_placeholder)) },
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
            Text(stringResource(R.string.add_book_submit_button))
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
            query.trim().isEmpty() -> stringResource(R.string.add_book_search_hint_min_chars, MIN_SEARCH_QUERY_LENGTH)
            query.trim().length < MIN_SEARCH_QUERY_LENGTH -> {
                val remaining = MIN_SEARCH_QUERY_LENGTH - query.trim().length
                pluralStringResource(
                    R.plurals.add_book_search_hint_keep_typing,
                    remaining,
                    remaining,
                )
            }

            searchState is AddSearchState.Searching -> stringResource(R.string.add_book_search_hint_searching)
            searchState is AddSearchState.NoResults -> stringResource(R.string.add_book_search_hint_no_results)
            searchState is AddSearchState.Error -> {
                when (searchState.reason) {
                    AddSearchErrorReason.MissingEditionKey ->
                        stringResource(R.string.add_book_search_error_missing_edition_key)

                    AddSearchErrorReason.MissingIsbn ->
                        stringResource(R.string.add_book_search_error_missing_isbn)

                    is AddSearchErrorReason.Generic ->
                        stringResource(R.string.add_book_search_error_generic)
                }
            }

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
    results: List<MediaSearchResult>,
    searchState: AddSearchState,
    isAddLoading: Boolean,
    onSelectResult: (MediaSearchResult) -> Unit,
    httpClient: HttpClient?,
    logger: Logger,
    modifier: Modifier = Modifier,
) {
    // Only show results/loading when query is long enough
    if (query.trim().length < MIN_SEARCH_QUERY_LENGTH) {
        return
    }

    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        when {
            searchState is AddSearchState.Searching -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .fillMaxSize(),
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
                            ).padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(
                        items = results,
                        key = { index, result -> "${result.workKey ?: result.title}_$index" },
                    ) { index, result ->
                        SearchResultRow(
                            result = result,
                            enabled = !isAddLoading,
                            onSelect = onSelectResult,
                            httpClient = httpClient,
                            logger = logger,
                        )
                    }
                }
            }

            else -> Unit
        }
    }
}

/**
 * One row in the search results list.
 */
@Composable
private fun SearchResultRow(
    result: MediaSearchResult,
    enabled: Boolean,
    onSelect: (MediaSearchResult) -> Unit,
    httpClient: HttpClient?,
    logger: Logger,
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
            httpClient = httpClient,
            logger = logger,
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
                        append(stringResource(R.string.add_book_search_result_year_format, year))
                    }
                    result.editionCount?.let { count ->
                        if (isNotEmpty()) append(" ")
                        append(pluralStringResource(R.plurals.add_book_search_result_editions_format, count, count))
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
    httpClient: HttpClient?,
    logger: Logger,
    modifier: Modifier = Modifier,
) {
    if (coverThumbnailUrl.isNullOrBlank()) {
        ThumbnailPlaceholder(modifier = modifier)
        return
    }

    val imageBitmap: ImageBitmap? =
        produceState<ImageBitmap?>(
            initialValue = null,
            coverThumbnailUrl,
        ) {
            value =
                withContext(Dispatchers.IO) {
                    if (httpClient == null) {
                        logger.warn(TAG) { "No HttpClient provided for thumbnail loading: $coverThumbnailUrl" }
                        return@withContext null
                    }
                    try {
                        val response = httpClient.get(coverThumbnailUrl)
                        if (response.status.isSuccess()) {
                            val bytes = response.body<ByteArray>()
                            BitmapFactory
                                .decodeByteArray(bytes, 0, bytes.size)
                                ?.asImageBitmap()
                        } else {
                            logger.warn(
                                TAG,
                            ) { "Failed to load thumbnail: $coverThumbnailUrl (status ${response.status.value})" }
                            null
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logger.warn(
                            TAG,
                        ) { "Unexpected error loading thumbnail: $coverThumbnailUrl (${e::class.simpleName})" }
                        null
                    }
                }
        }.value

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = stringResource(R.string.add_book_search_thumbnail_content_description),
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        ThumbnailPlaceholder(modifier = modifier)
    }
}

/**
 * Placeholder displayed when a search result thumbnail is missing or loading.
 */
@Composable
private fun ThumbnailPlaceholder(modifier: Modifier = Modifier) {
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

/**
 * Preview of the ISBN tab in idle state.
 */
@Preview(showBackground = true)
@Composable
private fun AddBookScreenIsbnPreview() {
    MediaTrackerTheme {
        AddBookScreen(
            uiState = AddBookUiState.Idle,
            searchQuery = "",
            searchResults = emptyList(),
            searchState = AddSearchState.Idle,
            confirmationResult = null,
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
            searchQuery = "",
            searchResults = emptyList(),
            searchState = AddSearchState.Idle,
            confirmationResult = null,
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
            searchQuery = "",
            searchResults = emptyList(),
            searchState = AddSearchState.Idle,
            confirmationResult = null,
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
            searchQuery = "",
            searchResults = emptyList(),
            searchState = AddSearchState.Idle,
            onSearchQueryChange = {},
            onSelectSearchResult = {},
            onClearSearch = {},
            httpClient = null,
            logger = AppLogger,
        )
    }
}

/**
 * Dialog for selecting a specific edition of a work (GitHub Issue #63).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditionSelectionDialog(
    work: MediaSearchResult,
    editions: List<BookEditionSearchResult>,
    isResolving: Boolean,
    onSelectEdition: (BookEditionSearchResult) -> Unit,
    onCancel: () -> Unit,
    httpClient: HttpClient?,
    logger: Logger,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Column {
                Text(stringResource(R.string.add_book_search_select_edition_title))
                Text(
                    text = work.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 100.dp, maxHeight = 400.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isResolving) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            stringResource(R.string.add_book_search_resolving_editions_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else if (editions.isEmpty()) {
                    Text(
                        stringResource(R.string.add_book_search_no_editions_found_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(editions) { edition ->
                            EditionResultRow(
                                edition = edition,
                                onClick = { onSelectEdition(edition) },
                                httpClient = httpClient,
                                logger = logger,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel_button))
            }
        },
    )
}

/**
 * One row in the edition selection list.
 */
@Composable
private fun EditionResultRow(
    edition: BookEditionSearchResult,
    onClick: () -> Unit,
    httpClient: HttpClient?,
    logger: Logger,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onClick() }
                .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail
        SearchResultThumbnail(
            coverThumbnailUrl = edition.coverThumbnailUrl,
            httpClient = httpClient,
            logger = logger,
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(4.dp)),
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val metadata =
                buildString {
                    edition.publisher?.let { publisher ->
                        append(stringResource(R.string.add_book_search_edition_publisher_format, publisher))
                    }
                    edition.publishDate?.let { date ->
                        if (isNotEmpty()) append(" • ")
                        append(date)
                    }
                }
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = stringResource(R.string.add_book_search_edition_isbn_format, edition.isbn),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            edition.pageCount?.let { pages ->
                Text(
                    text = stringResource(R.string.add_book_search_edition_pages_format, pages),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
            MediaSearchResult(
                type = MediaType.BOOK,
                title = "The Hobbit",
                authors = listOf("J.R.R. Tolkien"),
                firstPublishYear = 1937,
                editionCount = 500,
                coverThumbnailUrl = null,
                provider = com.hub.media.core.database.entities.IdentifierProvider.OPEN_LIBRARY,
                workKey = "/works/OL27482W",
                coverEditionKey = "OL51711263M",
            ),
            MediaSearchResult(
                type = MediaType.BOOK,
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
            searchQuery = "The Hobbit",
            searchResults = sampleResults,
            searchState = AddSearchState.Idle,
            onSearchQueryChange = {},
            onSelectSearchResult = {},
            onClearSearch = {},
            httpClient = null,
            logger = AppLogger,
        )
    }
}
