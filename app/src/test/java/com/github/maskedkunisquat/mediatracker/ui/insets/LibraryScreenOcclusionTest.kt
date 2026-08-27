package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.screens.LibraryScreen
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.features.media.data.MediaWithDetails
import com.hub.media.ui.LibraryUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

/**
 * The regression guard for PR #95: searching the library must not put the add button under the
 * keyboard.
 *
 * This is the case the bug actually shipped in, so it is the case the harness is proved against —
 * strip `contentWindowInsets` from `LibraryScreen`'s `Scaffold` and this test must fail. AGENTS.md
 * section 7 requires exactly that check before a guard is believed, and this repository has twice
 * paid for skipping it: a double-tap test that could not create its own concurrency, and an IME
 * grep that passed for the wrong reason and printed nothing.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
class LibraryScreenOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withTheKeyboardUp_theAddButtonAndSearchFieldStayReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheKeyboard(expectedTags = TAGS) { Fixture() }
    }

    /**
     * The navigation bar half, added with #99 -- protects the add-book FAB, the control PR #95
     * actually stranded.
     *
     * The FAB is positioned by `Scaffold`'s own `contentWindowInsets = WindowInsets.safeDrawing`,
     * not by anything this screen's content `Box` does -- that `Box` explicitly excepts the bottom
     * inset (see its own comment) and hands it to the media list's `contentPadding` instead, so a
     * card scrolls under the bar rather than stopping above it. The FAB and the list's last card
     * are therefore protected by two independent mechanisms sharing one screen: stripping
     * `contentWindowInsets` strands the FAB without touching the list's own padding, and stripping
     * the list's `barPadding` strands its last card without moving the FAB.
     */
    @Test
    fun withTheNavigationBarShowing_theAddButtonStaysReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheNavigationBar(expectedTags = TAGS) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        LibraryScreen(
            // A populated library rather than an empty one: the empty state has no list to
            // push the FAB around, so it would pass whether or not the inset were handled.
            uiState =
                LibraryUiState(
                    media = listOf(book("id-a", "Alpha Title"), book("id-b", "Bravo Title")),
                    searchQuery = "Alpha",
                ),
            coverStorageDir = "unused",
            onNavigateToAddBook = {},
            onNavigateToAddMovie = {},
            onNavigateToAddTVShow = {},
            onMediaClick = { _, _ -> },
            onNavigateToStats = {},
            onNavigateToSettings = {},
            onStatusFilterChange = {},
            onSearchQueryChange = {},
        )
    }

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

    private companion object {
        /** Named here rather than centrally so a dropped tag fails the screen that owns it. */
        val TAGS =
            listOf(
                TestTags.Library.ADD_BUTTON,
                TestTags.Library.SEARCH_FIELD,
                TestTags.Library.MEDIA_LIST,
            )
    }
}
