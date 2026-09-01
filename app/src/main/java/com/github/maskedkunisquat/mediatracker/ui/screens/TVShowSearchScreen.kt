package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TVShowSearchViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.insets.scrollingContentPadding
import com.hub.media.ui.AppContainer
import com.hub.media.ui.ShowSearchResult
import com.hub.media.ui.TVShowSearchUiState
import com.hub.media.ui.TVShowSearchViewModel

/**
 * Route wrapper for [TVShowSearchScreen] (ROADMAP Task 13 Phase D).
 *
 * Mirrors [AddTVShowScreenRoute], including the `reset()` before navigating: the save is
 * asynchronous, so the tapped row cannot know the new id at click time, and navigation is therefore
 * an effect of reaching a saved id rather than something the tap does directly.
 */
@Composable
fun TVShowSearchScreenRoute(
    appContainer: AppContainer,
    onNavigateBack: () -> Unit,
    onNavigateToManualEntry: () -> Unit,
    onShowAdded: (String) -> Unit,
) {
    val viewModel: TVShowSearchViewModel = viewModel(factory = TVShowSearchViewModelFactory(appContainer))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState) {
        val savedMediaId = uiState.savedMediaId
        if (savedMediaId != null) {
            viewModel.reset()
            onShowAdded(savedMediaId)
        }
    }

    TVShowSearchScreen(
        uiState = uiState,
        onQueryChange = viewModel::onQueryChange,
        onSearch = viewModel::search,
        onResultClick = viewModel::addShow,
        onDismissError = viewModel::dismissError,
        onNavigateBack = onNavigateBack,
        onNavigateToManualEntry = onNavigateToManualEntry,
    )
}

/**
 * Adds a TV show by looking it up on TMDB, with manual entry always one tap away.
 *
 * ### Why manual entry is on this screen rather than a sibling menu item
 * The app is offline-first and TMDB needs a credential the user supplies, so search is the path that
 * can be *unavailable* while manual entry never is. Making search the destination of "Add TV show"
 * and manual entry a permanent action on it means the fallback is visible at the moment it is
 * needed — including when the failure showing above it is "no credential is set". A sibling menu
 * entry would put the remedy on a screen the user has already left.
 *
 * Stateless: every value comes from [uiState] and every action is a callback, so the behaviour tests
 * drive it with fabricated state and no database. See AGENTS.md §7 on where a UI test lives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TVShowSearchScreen(
    uiState: TVShowSearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onResultClick: (Int) -> Unit,
    onDismissError: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToManualEntry: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Only an *add* failure goes to the snackbar. A failed add is transient -- the results are still
    // on screen and the row can be tapped again -- so a message that fades is right for it.
    //
    // A failed *search* is not transient: it leaves the results area empty, and that emptiness has to
    // explain itself. Showing it only in a snackbar meant the body fell through to "No shows matched
    // that search", which is confidently wrong -- nothing matched because the request never
    // succeeded. Caught on a device with no credential saved, where the snackbar named the real
    // problem while the body underneath contradicted it.
    LaunchedEffect(uiState.addError) {
        val addError = uiState.addError
        if (addError != null) {
            snackbarHostState.showSnackbar(addError)
            onDismissError()
        }
    }

    Scaffold(
        // safeDrawing rather than the default, which excludes the IME. Kept because it is correct
        // and because the `Compose IME insets` gate requires any Scaffold-plus-text-field screen to
        // declare one -- but deliberately **not** guarded by an occlusion test.
        //
        // One was written and then deleted: it passed with this line removed, which makes it a green
        // no-op of the kind AGENTS.md section 7 rejects. The reason is structural rather than a
        // fixture that needed more work -- the query field, its search button and the manual-entry
        // action are the top three nodes on the screen, so a keyboard rising from the bottom cannot
        // strand any of them, and the results below are a LazyColumn that scrolls. There is nothing
        // here for the rule to catch. If an interactive control is ever added *below* the list, add
        // the test back -- and prove it fails first.
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tv_show_search_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
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
                    .imePadding()
                    .consumeWindowInsets(innerPadding)
                    .padding(scrollingContentPadding(innerPadding, PaddingValues(16.dp))),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(R.string.tv_show_search_field_label)) },
                    singleLine = true,
                    // Search is an explicit action, never a keystroke -- see TVShowSearchViewModel's
                    // KDoc on why there is no debounce. The IME's own search key runs the same one.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions =
                        KeyboardActions(
                            onSearch = {
                                keyboard?.hide()
                                onSearch()
                            },
                        ),
                    modifier = Modifier.weight(1f).testTag(TestTags.TVShowSearch.QUERY_FIELD),
                )
                Button(
                    onClick = {
                        keyboard?.hide()
                        onSearch()
                    },
                    // Disabled on a blank query rather than allowed and then ignored: a button that
                    // does nothing when pressed reads as broken.
                    enabled = uiState.query.isNotBlank() && !uiState.isSearching,
                    modifier = Modifier.testTag(TestTags.TVShowSearch.SEARCH_BUTTON),
                ) {
                    Text(stringResource(R.string.tv_show_search_action))
                }
            }

            TextButton(
                onClick = onNavigateToManualEntry,
                modifier = Modifier.testTag(TestTags.TVShowSearch.MANUAL_ENTRY),
            ) {
                Text(stringResource(R.string.tv_show_search_manual_entry))
            }

            HorizontalDivider()

            when {
                uiState.isSearching ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                // The results area has four states, not two. Collapsing the first three into one
                // "nothing here" message is how a failed search ends up claiming the catalogue was
                // empty.
                uiState.searchError != null ->
                    Text(
                        text = uiState.searchError!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                !uiState.hasSearched ->
                    Text(
                        text = stringResource(R.string.tv_show_search_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                uiState.results.isEmpty() ->
                    Text(
                        text = stringResource(R.string.tv_show_search_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                else ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag(TestTags.TVShowSearch.RESULTS),
                    ) {
                        items(uiState.results, key = { it.tmdbId }) { result ->
                            SearchResultRow(
                                result = result,
                                // Only the row being added shows progress, and every row stops
                                // responding while one is in flight -- a second tap would otherwise
                                // produce a second show.
                                isAdding = uiState.addingTmdbId == result.tmdbId,
                                enabled = uiState.addingTmdbId == null,
                                onClick = { onResultClick(result.tmdbId) },
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    result: ShowSearchResult,
    isAdding: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(result.title) },
        supportingContent = {
            Text(result.year ?: stringResource(R.string.tv_show_search_unknown_year))
        },
        trailingContent = {
            if (isAdding) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
        },
        // enabled rather than removing the modifier, so the row keeps one clickable semantics node
        // whichever state it is in -- a node that appears and disappears is a node a matcher cannot
        // assert is disabled.
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    )
}
