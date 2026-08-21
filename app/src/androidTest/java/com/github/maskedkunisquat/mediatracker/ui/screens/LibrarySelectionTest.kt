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
import com.hub.media.features.media.data.MediaWithDetails
import com.hub.media.ui.LibraryUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.time.Instant

/**
 * Behavioural tests for library multi-select (ROADMAP Task 14 Phase B).
 * Consolidated from book-only version per Issue #67.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class LibrarySelectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun book(
        id: String,
        title: String,
    ) = MediaWithDetails.Book(
        item =
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

    private val mediaList = listOf(book("id-a", "Alpha Title"), book("id-b", "Bravo Title"))

    private fun setContent(
        uiState: LibraryUiState,
        onToggleSelection: (String) -> Unit = {},
        onClearSelection: () -> Unit = {},
        onDeleteSelected: () -> Unit = {},
        onMediaClick: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                LibraryScreen(
                    uiState = uiState,
                    coverStorageDir = "unused",
                    onNavigateToAddBook = {},
                    onMediaClick = onMediaClick,
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
    fun longPressingACard_invokesTheSelectionToggleForThatItem() {
        val toggled = mutableListOf<String>()
        setContent(LibraryUiState(media = mediaList), onToggleSelection = { toggled += it })

        composeRule.onNodeWithText("Alpha Title").performTouchInput { longClick() }

        assertEquals(listOf("id-a"), toggled)
    }

    @Test
    fun tappingACard_whileSelecting_togglesInsteadOfOpeningTheItem() {
        val toggled = mutableListOf<String>()
        val opened = mutableListOf<String>()
        setContent(
            LibraryUiState(media = mediaList, selectedIds = setOf("id-a")),
            onToggleSelection = { toggled += it },
            onMediaClick = { opened += it },
        )

        composeRule.onNodeWithText("Bravo Title").performClick()

        assertEquals(listOf("id-b"), toggled)
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun tappingACard_whenNotSelecting_stillOpensTheItem() {
        val opened = mutableListOf<String>()
        setContent(LibraryUiState(media = mediaList), onMediaClick = { opened += it })

        composeRule.onNodeWithText("Alpha Title").performClick()

        assertEquals(listOf("id-a"), opened)
    }

    @Test
    fun selectionMode_showsTheContextualBarWithTheVisibleSelectedCount() {
        setContent(LibraryUiState(media = mediaList, selectedIds = setOf("id-a", "id-b")))

        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
    }

    @Test
    fun closingTheContextualBar_invokesClearSelection() {
        var cleared = 0
        setContent(
            LibraryUiState(media = mediaList, selectedIds = setOf("id-a")),
            onClearSelection = { cleared++ },
        )

        composeRule.onNodeWithContentDescription("Leave selection").performClick()

        assertEquals(1, cleared)
    }

    @Test
    fun deleteAction_showsAConfirmationAndDoesNotDeleteYet() {
        var deletes = 0
        setContent(
            LibraryUiState(media = mediaList, selectedIds = setOf("id-a")),
            onDeleteSelected = { deletes++ },
        )

        composeRule.onNodeWithContentDescription("Delete selected").performClick()

        composeRule.onNodeWithText("Delete 1 item?").assertIsDisplayed()
        assertEquals("tapping delete must ask, not act", 0, deletes)
    }

    @Test
    fun confirmingTheDialog_invokesTheDelete() {
        var deletes = 0
        setContent(
            LibraryUiState(media = mediaList, selectedIds = setOf("id-a")),
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
            LibraryUiState(media = mediaList, selectedIds = setOf("id-a")),
            onDeleteSelected = { deletes++ },
        )

        composeRule.onNodeWithContentDescription("Delete selected").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(0, deletes)
        composeRule.onNodeWithText("Delete 1 item?").assertDoesNotExist()
    }

    @Test
    fun contextualBarCount_reflectsTheWholeSelectionNotJustWhatTheFilterShows() {
        val filtered =
            LibraryUiState(
                media = mediaList,
                selectedIds = setOf("id-a", "id-b"),
                statusFilter = ReadingStatus.READING,
            )
        setContent(filtered)

        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
    }

    @Test
    fun confirmationDialog_namesEveryItemItWillDelete() {
        setContent(LibraryUiState(media = mediaList, selectedIds = setOf("id-a", "id-b")))

        composeRule.onNodeWithContentDescription("Delete selected").performClick()

        composeRule.onNodeWithText("• Alpha Title", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("• Bravo Title", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun confirmationDialog_forOneItem_readsAsSingular() {
        setContent(LibraryUiState(media = mediaList, selectedIds = setOf("id-a")))

        composeRule.onNodeWithContentDescription("Delete selected").performClick()

        composeRule.onNodeWithText("Delete 1 item?").assertIsDisplayed()
    }
}
