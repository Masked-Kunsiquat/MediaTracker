package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.features.books.network.BookEditionSearchResult
import com.hub.media.features.books.network.BookSearchResult
import com.hub.media.ui.AddBookUiState
import com.hub.media.ui.AddSearchState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Behavioural tests for the Add Book screen (ROADMAP Task 9 Phase B2).
 *
 * Drives the stateless [AddBookScreen] with fake state and callbacks to verify that UI
 * interactions (typing, clicking tabs, selecting results) correctly invoke the ViewModel's
 * hooks.
 */
class AddBookScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val olSampleResult =
        BookSearchResult(
            title = "The Hobbit",
            authors = listOf("J.R.R. Tolkien"),
            provider = IdentifierProvider.OPEN_LIBRARY,
            workKey = "/works/OL27482W",
        )

    private val genericSampleResult =
        BookSearchResult(
            title = "Generic Book",
            authors = listOf("Some Author"),
            provider = IdentifierProvider.ISBN, // Or any non-OL provider if we had one
            coverEditionKey = "OL123M",
            workKey = null,
        )

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun setContent(
        uiState: AddBookUiState = AddBookUiState.Idle,
        searchQuery: String = "",
        searchResults: List<BookSearchResult> = emptyList(),
        searchState: AddSearchState = AddSearchState.Idle,
        confirmationResult: BookSearchResult? = null,
        editions: List<BookEditionSearchResult> = emptyList(),
        onSubmitIsbn: (String) -> Unit = {},
        onSearchQueryChange: (String) -> Unit = {},
        onSelectSearchResult: (BookSearchResult) -> Unit = {},
        onConfirmSelection: () -> Unit = {},
        onCancelSelection: () -> Unit = {},
        onSelectEdition: (BookEditionSearchResult) -> Unit = {},
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                AddBookScreen(
                    uiState = uiState,
                    searchQuery = searchQuery,
                    searchResults = searchResults,
                    searchState = searchState,
                    confirmationResult = confirmationResult,
                    editions = editions,
                    onNavigateBack = {},
                    onSubmitIsbn = onSubmitIsbn,
                    onSearchQueryChange = onSearchQueryChange,
                    onSelectSearchResult = onSelectSearchResult,
                    onClearSearch = {},
                    onConfirmSelection = onConfirmSelection,
                    onCancelSelection = onCancelSelection,
                    onSelectEdition = onSelectEdition,
                )
            }
        }
    }

    @Test
    fun typingInSearchField_invokesSearchQueryChange() {
        var capturedQuery = ""
        setContent(onSearchQueryChange = { capturedQuery = it })

        val label = context.getString(R.string.add_book_search_label)
        // Ambiguity check: TopAppBar might have "Add Book", Search Tab has "Search" label.
        // TextField label is "Search by title or author" (add_book_search_label)
        composeRule.onNode(hasText(label) and hasSetTextAction()).performTextInput("hobbit")

        assertEquals("hobbit", capturedQuery)
    }

    @Test
    fun tappingASearchResult_invokesSelectSearchResult() {
        var selected: BookSearchResult? = null
        setContent(
            searchQuery = "hobbit",
            searchResults = listOf(olSampleResult),
            onSelectSearchResult = { selected = it },
        )

        composeRule.onNodeWithText("The Hobbit").performClick()

        assertEquals(olSampleResult, selected)
    }

    @Test
    fun whenConfirmationResultIsPresent_showsConfirmationDialog() {
        setContent(confirmationResult = genericSampleResult)

        val title = context.getString(R.string.add_book_search_confirm_title)
        // Dialog title
        composeRule.onNodeWithText(title).assertIsDisplayed()
        // Message contains title
        composeRule.onNodeWithText("Generic Book", substring = true).assertIsDisplayed()
    }

    @Test
    fun confirmingTheDialog_invokesConfirmSelection() {
        var confirmed = 0
        setContent(confirmationResult = genericSampleResult, onConfirmSelection = { confirmed++ })

        val buttonText = context.getString(R.string.add_book_search_confirm_button)
        // Ambiguity: AppBar title is "Add Book" (add_book_screen_title), Dialog button is also "Add Book" (add_book_search_confirm_button)
        // Both use R.string.add_book_screen_title / add_book_search_confirm_button which might be the same string.
        composeRule.onNode(hasText(buttonText) and hasClickAction()).performClick()

        assertEquals(1, confirmed)
    }

    @Test
    fun cancellingTheDialog_invokesCancelSelection() {
        var cancelled = 0
        setContent(confirmationResult = genericSampleResult, onCancelSelection = { cancelled++ })

        val buttonText = context.getString(R.string.cancel_button)
        composeRule.onNodeWithText(buttonText).performClick()

        assertEquals(1, cancelled)
    }

    @Test
    fun switchingToIsbnTab_showsIsbnField() {
        setContent()

        // Initially in Search tab
        val searchLabel = context.getString(R.string.add_book_search_label)
        composeRule.onNode(hasText(searchLabel) and hasSetTextAction()).assertIsDisplayed()

        // Switch to ISBN tab
        val isbnTabText = context.getString(R.string.add_book_tab_isbn)
        composeRule.onNode(hasText(isbnTabText) and hasRole(Role.Tab)).performClick()

        val isbnLabel = context.getString(R.string.add_book_isbn_label)
        composeRule.onNode(hasText(isbnLabel) and hasSetTextAction()).assertIsDisplayed()
    }

    @Test
    fun submittingIsbn_invokesSubmitIsbn() {
        var submittedIsbn = ""
        setContent(onSubmitIsbn = { submittedIsbn = it })

        // Switch to ISBN tab
        val tabText = context.getString(R.string.add_book_tab_isbn)
        composeRule.onNode(hasText(tabText) and hasRole(Role.Tab)).performClick()

        val isbnLabel = context.getString(R.string.add_book_isbn_label)
        composeRule.onNode(hasText(isbnLabel) and hasSetTextAction()).performTextInput("9780547928227")

        val submitButton = context.getString(R.string.add_book_submit_button)
        composeRule.onNode(hasText(submitButton) and hasClickAction()).performClick()

        assertEquals("9780547928227", submittedIsbn)
    }

    @Test
    fun whenLoading_inputsAndButtonsAreDisabled() {
        setContent(uiState = AddBookUiState.Loading, searchQuery = "hobbit")

        val searchLabel = context.getString(R.string.add_book_search_label)
        // When disabled, hasSetTextAction might be false, so match by text and not-a-tab.
        composeRule
            .onNode(hasText(searchLabel) and SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
            .assertIsNotEnabled()

        // Switch to ISBN tab
        val tabText = context.getString(R.string.add_book_tab_isbn)
        composeRule.onNode(hasText(tabText) and hasRole(Role.Tab)).performClick()

        val isbnLabel = context.getString(R.string.add_book_isbn_label)
        composeRule
            .onNode(hasText(isbnLabel) and SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
            .assertIsNotEnabled()

        val submitButton = context.getString(R.string.add_book_submit_button)
        composeRule.onNode(hasText(submitButton) and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun whenError_displaysErrorMessage() {
        setContent(uiState = AddBookUiState.Error("Network Timeout"))

        // Switch to ISBN tab to see the error message below the field
        val tabText = context.getString(R.string.add_book_tab_isbn)
        composeRule.onNode(hasText(tabText) and hasRole(Role.Tab)).performClick()

        composeRule.onNodeWithText("Network Timeout").assertIsDisplayed()
    }

    @Test
    fun whenResolvingEditions_showsLoadingInEditionDialog() {
        setContent(
            confirmationResult = olSampleResult,
            searchState = AddSearchState.ResolvingEditions,
        )

        val title = context.getString(R.string.add_book_search_select_edition_title)
        composeRule.onNodeWithText(title).assertIsDisplayed()

        val loadingText = context.getString(R.string.add_book_search_resolving_editions_hint)
        composeRule.onNodeWithText(loadingText).assertIsDisplayed()
    }

    @Test
    fun whenEditionsArePresent_showsEditionList() {
        val sampleEdition =
            BookEditionSearchResult(
                title = "The Hobbit",
                publisher = "Allen & Unwin",
                publishDate = "1937",
                isbn = "9780547928227",
                pageCount = 310,
                coverThumbnailUrl = null,
                editionKey = "OL51711263M",
                provider = IdentifierProvider.OPEN_LIBRARY,
            )

        setContent(
            confirmationResult = olSampleResult,
            editions = listOf(sampleEdition),
        )

        val title = context.getString(R.string.add_book_search_select_edition_title)
        composeRule.onNodeWithText(title).assertIsDisplayed()

        // Verify edition details
        composeRule.onNodeWithText("Allen & Unwin", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("9780547928227", substring = true).assertIsDisplayed()
    }

    @Test
    fun selectingAnEdition_invokesSelectEdition() {
        val sampleEdition =
            BookEditionSearchResult(
                title = "The Hobbit",
                publisher = "Allen & Unwin",
                publishDate = "1937",
                isbn = "9780547928227",
                pageCount = 310,
                coverThumbnailUrl = null,
                editionKey = "OL51711263M",
                provider = IdentifierProvider.OPEN_LIBRARY,
            )
        var selected: BookEditionSearchResult? = null
        setContent(
            confirmationResult = olSampleResult,
            editions = listOf(sampleEdition),
            onSelectEdition = { selected = it },
        )

        composeRule.onNodeWithText("9780547928227", substring = true).performClick()

        assertEquals(sampleEdition, selected)
    }

    @Test
    fun whenNoEditionsFound_showsHintInEditionDialog() {
        setContent(
            confirmationResult = olSampleResult,
            editions = emptyList(),
            searchState = AddSearchState.Idle, // Not loading anymore
        )

        val hint = context.getString(R.string.add_book_search_no_editions_found_hint)
        composeRule.onNodeWithText(hint).assertIsDisplayed()
    }

    @Test
    fun searchClearButton_onlyShownWhenQueryIsNotEmpty() {
        var query by mutableStateOf("")
        composeRule.setContent {
            MediaTrackerTheme {
                AddBookScreen(
                    uiState = AddBookUiState.Idle,
                    searchQuery = query,
                    searchResults = emptyList(),
                    searchState = AddSearchState.Idle,
                    confirmationResult = null,
                    onNavigateBack = {},
                    onSubmitIsbn = {},
                    onSearchQueryChange = { query = it },
                    onSelectSearchResult = {},
                    onClearSearch = { query = "" },
                    onConfirmSelection = {},
                    onCancelSelection = {},
                )
            }
        }

        val clearDesc = context.getString(R.string.add_book_search_clear_content_description)
        composeRule.onNodeWithContentDescription(clearDesc).assertDoesNotExist()

        query = "h"
        composeRule.onNodeWithContentDescription(clearDesc).assertIsDisplayed()
    }
}
