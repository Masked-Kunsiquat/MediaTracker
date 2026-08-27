package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.screens.AddBookScreen
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaType
import com.hub.media.features.media.network.MediaSearchResult
import com.hub.media.ui.AddBookUiState
import com.hub.media.ui.AddSearchState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * IME-occlusion guard for the Add Book screen's search tab.
 *
 * Drives the default (search) tab with a non-empty query and a populated results list rather than
 * the empty/idle state: an empty query renders no [androidx.compose.foundation.lazy.LazyColumn] of
 * results at all, which would leave nothing below the search field to strand and let this pass
 * regardless of inset handling, exactly as [LibraryScreenImeOcclusionTest]'s KDoc explains for its
 * own populated-vs-empty choice. The results sit in a scrolling `LazyColumn`, so they are
 * measured with that list scrolled to its end -- the highest position its last row can reach, and
 * therefore the one that decides whether the row is reachable at all. Asserted alongside the search
 * field and clear button, which sit outside the list and cannot be scrolled anywhere.
 */
@RunWith(RobolectricTestRunner::class)
class AddBookScreenImeOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Enough results to fill the screen, which a single one does not.
     *
     * The results `LazyColumn` only sets `fillMaxWidth`, so its height wraps its content: with one
     * result it is a short box near the top of the screen and *nothing on the screen reaches the
     * bottom at all*. Stripping `contentWindowInsets` then changes no measurable bound and the test
     * passes either way — which is what a first version of this fixture did, silently.
     *
     * Twenty is simply more than fits, so the list is constrained by its parent instead of by its
     * content and its viewport runs to the bottom of the container, where the keyboard is. That is
     * also what a real search returns; the one-result version was the unrealistic one.
     */
    private val sampleResults =
        List(20) { index ->
            MediaSearchResult(
                type = MediaType.BOOK,
                title = "The Hobbit, printing $index",
                authors = listOf("J.R.R. Tolkien"),
                provider = IdentifierProvider.OPEN_LIBRARY,
                workKey = "/works/OL27482W-$index",
            )
        }

    @Test
    fun withTheKeyboardUp_theSearchFieldAndClearButtonStayReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheKeyboard(
            // Named here rather than centrally so a dropped tag fails the screen that
            // owns it. ISBN_FIELD is on the other tab and is not composed by this fixture.
            expectedTags =
                listOf(
                    TestTags.AddBook.SEARCH_FIELD,
                    TestTags.AddBook.RESULTS,
                ),
        ) {
            AddBookScreen(
                uiState = AddBookUiState.Idle,
                searchQuery = "Hobbit",
                searchResults = sampleResults,
                searchState = AddSearchState.Idle,
                confirmationResult = null,
                onNavigateBack = {},
                onSubmitIsbn = {},
                onSearchQueryChange = {},
                onSelectSearchResult = {},
                onClearSearch = {},
                onConfirmSelection = {},
                onCancelSelection = {},
                onSelectEdition = {},
            )
        }
    }
}
