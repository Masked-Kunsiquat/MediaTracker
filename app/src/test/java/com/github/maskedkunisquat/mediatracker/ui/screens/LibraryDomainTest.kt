package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.features.media.data.MediaWithDetails
import com.hub.media.ui.LibraryUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

/**
 * The library inside a domain (#126): its title, its back arrow to the hub, a + that adds that kind
 * directly, and the top-bar actions each domain keeps. Every "absent" check sits beside a "present"
 * one, so none of them passes just because the bar failed to draw.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
class LibraryDomainTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val calls = mutableListOf<String>()

    private fun setContent(
        domain: MediaType?,
        uiState: LibraryUiState = LibraryUiState(),
    ) {
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                LibraryScreen(
                    uiState = uiState,
                    coverStorageDir = "unused",
                    domain = domain,
                    onNavigateBack = { calls += "back" },
                    onNavigateToAddBook = { calls += "addBook" },
                    onNavigateToAddMovie = { calls += "addMovie" },
                    onNavigateToAddTVShow = { calls += "addShow" },
                    onMediaClick = { _, _ -> },
                    onNavigateToStats = { calls += "stats" },
                    onNavigateToUpNext = { calls += "upNext" },
                    onStatusFilterChange = {},
                    onSearchQueryChange = {},
                )
            }
        }
    }

    private fun string(id: Int) = context.getString(id)

    private fun book() =
        MediaWithDetails.Book(
            item =
                MediaItemEntity(
                    id = "id-a",
                    type = MediaType.BOOK,
                    title = "Dune",
                    releaseYear = null,
                    purchasePrice = null,
                    createdAt = Instant.fromEpochMilliseconds(0),
                    coverImageHash = null,
                ),
            details = null,
        )

    private val stats get() = string(R.string.stats_content_description)
    private val upNext get() = string(R.string.up_next_content_description)
    private val settings get() = string(R.string.settings_content_description)

    @Test
    fun aDomain_isTitled_andItsAddButton_addsThatKindDirectly() {
        setContent(MediaType.MOVIE)

        composeRule.onNodeWithText(string(R.string.library_title_films)).assertExists()
        composeRule.onNodeWithTag(TestTags.Library.ADD_BUTTON).performClick()

        assertEquals(listOf("addMovie"), calls)
        // No "what do you want to add?" menu inside a domain.
        composeRule.onNodeWithText(string(R.string.add_book_menu_item)).assertDoesNotExist()
    }

    @Test
    fun allMedia_isTitledSo_andKeepsTheAddMenu() {
        setContent(null)

        composeRule.onNodeWithText(string(R.string.hub_all_media)).assertExists()
        composeRule.onNodeWithTag(TestTags.Library.ADD_BUTTON).performClick()

        composeRule.onNodeWithText(string(R.string.add_book_menu_item)).assertExists()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun books_offerStats_notUpNext() {
        setContent(MediaType.BOOK)

        composeRule.onNodeWithContentDescription(stats).assertExists()
        composeRule.onNodeWithContentDescription(upNext).assertDoesNotExist()
    }

    @Test
    fun shows_offerUpNext_andStats() {
        setContent(MediaType.TV_SHOW)

        composeRule.onNodeWithContentDescription(upNext).assertExists()
        // Every domain has its own Stats tab since #217.
        composeRule.onNodeWithContentDescription(stats).assertExists()
    }

    @Test
    fun films_offerStats_notUpNext() {
        setContent(MediaType.MOVIE)

        // Every domain has its own Stats tab since #217.
        composeRule.onNodeWithContentDescription(stats).assertExists()
        composeRule.onNodeWithContentDescription(upNext).assertDoesNotExist()
    }

    @Test
    fun allMedia_offersStatsAndUpNext_andNoLibraryOffersSettings() {
        setContent(null)

        composeRule.onNodeWithContentDescription(stats).assertExists()
        composeRule.onNodeWithContentDescription(upNext).assertExists()
        // Settings lives on the hub now.
        composeRule.onNodeWithContentDescription(settings).assertDoesNotExist()
    }

    @Test
    fun back_returnsToTheHub() {
        setContent(MediaType.BOOK)

        composeRule.onNodeWithContentDescription(string(R.string.navigate_back)).performClick()

        assertEquals(listOf("back"), calls)
    }

    @Test
    fun theSearchPlaceholder_namesTheDomain() {
        // An empty library shows its empty state instead of the search field, so give it one book.
        setContent(MediaType.BOOK, LibraryUiState(media = listOf(book())))

        composeRule.onNodeWithText(string(R.string.library_search_placeholder_books)).assertExists()
    }

    @Test
    fun anEmptyDomain_saysThatKindIsEmpty_notTheWholeLibrary() {
        setContent(MediaType.MOVIE)

        composeRule.onNodeWithText(string(R.string.library_empty_title_films)).assertExists()
        composeRule.onNodeWithText(string(R.string.library_empty_title)).assertDoesNotExist()
    }

    @Test
    fun anEmptyAllMedia_stillSaysTheLibraryIsEmpty() {
        setContent(null)

        composeRule.onNodeWithText(string(R.string.library_empty_title)).assertExists()
    }

    @Test
    fun whileLoading_noEmptyMessageFlashes() {
        // Opening a card used to show "No films yet" for a beat before the films arrived.
        setContent(MediaType.MOVIE, LibraryUiState(isLoading = true))

        // Positive control: the screen itself is drawn.
        composeRule.onNodeWithText(string(R.string.library_title_films)).assertExists()
        composeRule.onNodeWithText(string(R.string.library_empty_title_films)).assertDoesNotExist()
    }
}
