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
     * The navigation bar half, added with #99: the media list's last card must clear the bar.
     *
     * A different mechanism from the test above, on the same screen. The FAB is placed by
     * `Scaffold`'s own `contentWindowInsets`, and `Scaffold`'s *default* is already `systemBars` --
     * so the FAB clears the navigation bar whatever this screen does, and a test aimed at it would
     * assert nothing. What #99 actually put at risk here is the list: its `Box` deliberately
     * excepts the bottom inset and hands it to `contentPadding`, so the last card is the thing that
     * ends up under the bar if that hand-off is wrong.
     *
     * Which is why the fixture below carries [BOOK_COUNT] books rather than the two it started
     * with. With two, the list did not reach the bottom of the screen, the last card was nowhere
     * near the bar, and this test passed with the padding deleted -- a green no-op of exactly the
     * kind AGENTS.md section 7 and this harness's own KDoc warn about. Caught by falsification,
     * not by reading it.
     */
    @Test
    fun withTheNavigationBarShowing_theLastCardStaysAboveIt() {
        composeRule.assertNoInteractiveNodeIsBehindTheNavigationBar(expectedTags = TAGS) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        LibraryScreen(
            // Enough books to overflow the screen, all matching the query, so the list is both
            // filtered (the PR #95 scenario the keyboard test needs) and long enough to have a
            // card at the bottom edge (what the navigation bar test needs).
            uiState =
                LibraryUiState(
                    media = List(BOOK_COUNT) { book("id-$it", "Alpha Title $it") },
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
        /**
         * Enough cards to run past the bottom of the test display, so the list has something at
         * the navigation bar to be wrong about. Comfortably more than needed rather than tuned to
         * a screen height, which would make the test depend on the Robolectric device profile.
         */
        const val BOOK_COUNT = 20

        /** Named here rather than centrally so a dropped tag fails the screen that owns it. */
        val TAGS =
            listOf(
                TestTags.Library.ADD_BUTTON,
                TestTags.Library.SEARCH_FIELD,
                TestTags.Library.MEDIA_LIST,
            )
    }
}
