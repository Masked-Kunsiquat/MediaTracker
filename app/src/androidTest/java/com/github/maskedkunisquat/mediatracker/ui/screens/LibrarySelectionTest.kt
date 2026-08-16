package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.features.books.data.BookWithDetails
import com.hub.media.ui.LibraryUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.time.Instant

/**
 * Behavioural tests for library multi-select (ROADMAP Task 14 Phase B).
 *
 * This is the destructive feature the Compose harness was sequenced ahead of, so the emphasis is on
 * the controls that delete things: that long-press reaches the toggle, that the confirmation's
 * confirm button reaches the delete, and that cancelling reaches nothing. A confirmation wired to a
 * stub renders perfectly and quietly destroys the wrong amount of data or none at all, and no
 * amount of ViewModel testing sees it.
 *
 * The stateless [LibraryScreen] is driven with fake callbacks; the selection *logic* (what survives
 * a filter, what a delete acts on) is unit-tested in `LibraryViewModelTest`, where it is far
 * cheaper to cover exhaustively.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class LibrarySelectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun book(
        id: String,
        title: String,
    ) = BookWithDetails(
        mediaItem =
            MediaItemEntity(
                id = id,
                type = MediaType.BOOK,
                title = title,
                releaseYear = null,
                purchasePrice = null,
                createdAt = Instant.fromEpochMilliseconds(0),
                coverImageHash = null,
            ),
        details = null,
    )

    private val books = listOf(book("id-a", "Alpha Title"), book("id-b", "Bravo Title"))

    private fun setContent(
        uiState: LibraryUiState,
        onToggleSelection: (String) -> Unit = {},
        onClearSelection: () -> Unit = {},
        onDeleteSelected: () -> Unit = {},
        onBookClick: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                LibraryScreen(
                    uiState = uiState,
                    coverStorageDir = "unused",
                    onNavigateToAddBook = {},
                    onBookClick = onBookClick,
                    onNavigateToStats = {},
                    onNavigateToSettings = {},
                    onStatusFilterChange = {},
                    onSearchQueryChange = {},
                    onToggleSelection = onToggleSelection,
                    onClearSelection = onClearSelection,
                    onDeleteSelected = onDeleteSelected,
                )
            }
        }
    }

    @Test
    fun longPressingACard_invokesTheSelectionToggleForThatBook() {
        val toggled = mutableListOf<String>()
        setContent(LibraryUiState(books = books), onToggleSelection = { toggled += it })

        composeRule.onNodeWithText("Alpha Title").performTouchInput { longClick() }

        assertEquals(listOf("id-a"), toggled)
    }

    @Test
    fun tappingACard_whileSelecting_togglesInsteadOfOpeningTheBook() {
        // The mode swap. If a tap still navigated, selecting several books in a row would be an
        // exercise in precision and one slip would leave the screen entirely.
        val toggled = mutableListOf<String>()
        val opened = mutableListOf<String>()
        setContent(
            LibraryUiState(books = books, selectedIds = setOf("id-a")),
            onToggleSelection = { toggled += it },
            onBookClick = { opened += it },
        )

        composeRule.onNodeWithText("Bravo Title").performClick()

        assertEquals(listOf("id-b"), toggled)
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun tappingACard_whenNotSelecting_stillOpensTheBook() {
        // Positive control for the test above: proves the tap path works at all, so that test's
        // empty navigation list means "suppressed" rather than "broken in both modes".
        val opened = mutableListOf<String>()
        setContent(LibraryUiState(books = books), onBookClick = { opened += it })

        composeRule.onNodeWithText("Alpha Title").performClick()

        assertEquals(listOf("id-a"), opened)
    }

    @Test
    fun selectionMode_showsTheContextualBarWithTheVisibleSelectedCount() {
        setContent(LibraryUiState(books = books, selectedIds = setOf("id-a", "id-b")))

        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
    }

    @Test
    fun closingTheContextualBar_invokesClearSelection() {
        var cleared = 0
        setContent(
            LibraryUiState(books = books, selectedIds = setOf("id-a")),
            onClearSelection = { cleared++ },
        )

        composeRule.onNodeWithContentDescription("Leave selection").performClick()

        assertEquals(1, cleared)
    }

    @Test
    fun deleteAction_showsAConfirmationAndDoesNotDeleteYet() {
        // Destructive actions must not fire on the first tap. This asserts both halves: the
        // confirmation appears, and nothing has been deleted at the point it does.
        var deletes = 0
        setContent(
            LibraryUiState(books = books, selectedIds = setOf("id-a")),
            onDeleteSelected = { deletes++ },
        )

        composeRule.onNodeWithContentDescription("Delete selected").performClick()

        composeRule.onNodeWithText("Delete 1 book?").assertIsDisplayed()
        assertEquals("tapping delete must ask, not act", 0, deletes)
    }

    @Test
    fun confirmingTheDialog_invokesTheDelete() {
        // The one that matters most: a confirm button wired to a stub looks identical to this.
        var deletes = 0
        setContent(
            LibraryUiState(books = books, selectedIds = setOf("id-a")),
            onDeleteSelected = { deletes++ },
        )

        composeRule.onNodeWithContentDescription("Delete selected").performClick()
        composeRule.onNodeWithText("Delete").performClick()

        assertEquals(1, deletes)
    }

    @Test
    fun cancellingTheDialog_deletesNothingAndDismisses() {
        var deletes = 0
        setContent(
            LibraryUiState(books = books, selectedIds = setOf("id-a")),
            onDeleteSelected = { deletes++ },
        )

        composeRule.onNodeWithContentDescription("Delete selected").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(0, deletes)
        composeRule.onNodeWithText("Delete 1 book?").assertDoesNotExist()
    }

    @Test
    fun contextualBarCount_reflectsTheWholeSelectionNotJustWhatTheFilterShows() {
        // The behaviour this replaced scoped the count to the visible subset, so it moved as the
        // filter moved and read as the selection being silently lost.
        val filtered =
            LibraryUiState(
                books = books,
                selectedIds = setOf("id-a", "id-b"),
                statusFilter = ReadingStatus.READING,
            )
        setContent(filtered)

        // Neither fake book has details, so neither matches a non-null status filter -- yet both
        // remain selected.
        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
    }

    @Test
    fun confirmationDialog_namesEveryBookItWillDelete() {
        // Listing the titles is what makes deleting the whole selection safe rather than alarming:
        // a filter can hide a selected book, and this puts it back in front of the user at the
        // moment it matters.
        setContent(LibraryUiState(books = books, selectedIds = setOf("id-a", "id-b")))

        composeRule.onNodeWithContentDescription("Delete selected").performClick()

        // Match the bulleted form: the library list still renders behind the dialog, so a bare
        // title matches two nodes and the assertion fails on ambiguity rather than absence.
        composeRule.onNodeWithText("• Alpha Title", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("• Bravo Title", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun confirmationDialog_forOneBook_readsAsSingular() {
        setContent(LibraryUiState(books = books, selectedIds = setOf("id-a")))

        composeRule.onNodeWithContentDescription("Delete selected").performClick()

        composeRule.onNodeWithText("Delete 1 book?").assertIsDisplayed()
    }
}
