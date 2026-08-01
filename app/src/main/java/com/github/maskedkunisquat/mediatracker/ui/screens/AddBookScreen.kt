package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.AddBookViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.ui.AddBookUiState
import com.hub.media.ui.AddBookViewModel
import com.hub.media.ui.AppContainer
import kotlin.time.ExperimentalTime

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
    val viewModel: AddBookViewModel = viewModel(
        factory = AddBookViewModelFactory(appContainer),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // When the submission succeeds, navigate back to library and reset the ViewModel.
    LaunchedEffect(uiState) {
        if (uiState is AddBookUiState.Success) {
            onNavigateToLibrary()
            viewModel.reset()
        }
    }

    AddBookScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onSubmitIsbn = { isbn -> viewModel.addBook(isbn) },
    )
}

/**
 * Stateless add-book screen composable (AGENTS.md §5 State Hoisting).
 *
 * Displays a text field for ISBN entry and a submit button. The input accepts digits and 'X' only
 * (via inputFilter/keyboardType). Renders the current [AddBookUiState]:
 * - [AddBookUiState.Idle]: empty state, input enabled, submit button active.
 * - [AddBookUiState.Loading]: CircularProgressIndicator, input disabled, submit button disabled.
 * - [AddBookUiState.Error]: error message displayed as supporting text, input remains enabled
 *   for retry.
 * - [AddBookUiState.Success]: This state is NOT rendered here — the route-level composable
 *   should navigate before this screen is re-rendered.
 *
 * The TopAppBar has a back navigation icon.
 *
 * @param uiState [AddBookUiState] representing the current state of the submission.
 * @param onNavigateBack Called when the back icon is pressed.
 * @param onSubmitIsbn Called with the ISBN value when the submit button is pressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBookScreen(
    uiState: AddBookUiState,
    onNavigateBack: () -> Unit,
    onSubmitIsbn: (String) -> Unit,
) {
    var isbnInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Add Book by ISBN") },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val isLoading = uiState is AddBookUiState.Loading
                val isError = uiState is AddBookUiState.Error

                OutlinedTextField(
                    value = isbnInput,
                    onValueChange = { newValue: String ->
                        // Accept only digits and 'X' (for ISBN-10 checksum)
                        isbnInput = newValue.filter { char: Char ->
                            char.isDigit() || char.uppercaseChar() == 'X'
                        }.take(13)  // ISBN can be max 13 digits
                    },
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
                        modifier = Modifier
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
    }
}

/**
 * Preview of the add-book screen in its idle state.
 */
@Preview(showBackground = true)
@Composable
private fun AddBookScreenPreview() {
    MediaTrackerTheme {
        AddBookScreen(
            uiState = AddBookUiState.Idle,
            onNavigateBack = {},
            onSubmitIsbn = {},
        )
    }
}

/**
 * Preview of the add-book screen in loading state.
 */
@Preview(showBackground = true)
@Composable
private fun AddBookScreenLoadingPreview() {
    MediaTrackerTheme {
        AddBookScreen(
            uiState = AddBookUiState.Loading,
            onNavigateBack = {},
            onSubmitIsbn = {},
        )
    }
}

/**
 * Preview of the add-book screen in error state.
 */
@Preview(showBackground = true)
@Composable
private fun AddBookScreenErrorPreview() {
    MediaTrackerTheme {
        AddBookScreen(
            uiState = AddBookUiState.Error("Invalid ISBN format. Please check and try again."),
            onNavigateBack = {},
            onSubmitIsbn = {},
        )
    }
}

/**
 * Preview of the add-book screen in success state (not normally rendered, for testing purposes).
 */
@Preview(showBackground = true)
@Composable
private fun AddBookScreenSuccessPreview() {
    MediaTrackerTheme {
        AddBookScreen(
            uiState = AddBookUiState.Success("book-123"),
            onNavigateBack = {},
            onSubmitIsbn = {},
        )
    }
}
