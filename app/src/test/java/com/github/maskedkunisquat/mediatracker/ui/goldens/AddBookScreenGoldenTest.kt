package com.github.maskedkunisquat.mediatracker.ui.goldens

import androidx.compose.runtime.Composable
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
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline golden for add book, in its search-results state.
 *
 * One of the three screens #99 deliberately left on real padding rather than moving it inside a
 * scroller, and the golden is the record of why that was right: the results sit in a rounded,
 * tinted card, and a card drawing under the navigation bar has its corners clipped rather than its
 * content flowing past. That is an appearance judgement, so it is exactly the kind of decision that
 * should be pinned by a picture — the next person to "fix" this screen for consistency with the
 * others will see the corners change.
 *
 * Paired with a tag assertion (#102 rule 1), falsified by dropping `TestTags.AddBook.RESULTS`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddBookScreenGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun addBook() {
        composeRule.captureGolden(
            name = "add-book",
            alsoAssert = { assertTagsExist(TestTags.AddBook.SEARCH_FIELD, TestTags.AddBook.RESULTS) },
        ) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        AddBookScreen(
            uiState = AddBookUiState.Idle,
            searchQuery = "Tolkien",
            searchResults = RESULTS,
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

    private companion object {
        /**
         * Real titles of varied length rather than "printing 0..19".
         *
         * The occlusion fixture's uniform placeholders are right for measuring bounds and wrong for
         * a picture: identical widths hide a title that wraps or elides, which is the defect a
         * human reading this golden is best placed to catch.
         */
        val RESULTS =
            listOf(
                "The Hobbit",
                "The Fellowship of the Ring",
                "The Two Towers",
                "The Return of the King",
                "The Silmarillion",
                "Unfinished Tales of Numenor and Middle-earth",
                "The Children of Hurin",
                "Beren and Luthien",
                "The Fall of Gondolin",
                "The Book of Lost Tales, Part One",
            ).mapIndexed { index, title ->
                MediaSearchResult(
                    type = MediaType.BOOK,
                    title = title,
                    authors = listOf("J.R.R. Tolkien"),
                    provider = IdentifierProvider.OPEN_LIBRARY,
                    workKey = "/works/OL27482W-$index",
                )
            }
    }
}
