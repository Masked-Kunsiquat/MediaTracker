package com.github.maskedkunisquat.mediatracker.ui.goldens

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
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Instant

/**
 * Goldens for the library, the screen the app opens on.
 *
 * Recorded in three variants, and the reason for each is the reason it is worth a reviewer's time:
 *
 * - **Light**, the baseline. What #99 changed here is that the list draws behind the navigation bar
 *   while its last card still clears it, and the search field above it stays pinned. That is a
 *   spacing relationship, which is what a golden reads well and an invariant reads poorly.
 * - **Dark**, because this screen renders cards over a surface with a tinted status chip, and a
 *   contrast mistake in dark mode is invisible to every bounds assertion in the repository.
 * - **Large font scale**, because the card layout puts a title, a year and a status chip on rows
 *   that have to reflow rather than clip. `MIN_FILL_FRACTION` cannot see clipping; a picture can.
 *
 * Each is paired with a tag assertion (#102 rule 1) rather than standing on the image alone. The
 * pairing is falsified rather than assumed — see the class comment on falsification below.
 *
 * ### Falsification record (#102 rule 2)
 *
 * The paired assertion here fails when the control it names is removed: deleting the
 * `TestTags.Library.SEARCH_FIELD` tag from `LibraryScreen` fails all three tests with the message
 * from `assertTagsExist`, not with an image diff. That is the property being bought — a golden
 * re-recorded by reflex still cannot make these tests vacuous.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
// Robolectric's legacy graphics pipeline returns null from Bitmap.createBitmap, so the first
// pixel-capture attempt here died inside Compose's vector painter with a NullPointerException.
// Native graphics is what makes drawing real, and it is set per-class rather than in
// robolectric.properties so the occlusion lane keeps the cheaper pipeline it does not need to
// draw for.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryScreenGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun library() {
        composeRule.captureGolden(
            name = "library",
            alsoAssert = { assertTagsExist(*TAGS) },
        ) { Fixture() }
    }

    @Test
    fun libraryDark() {
        composeRule.captureGolden(
            name = "library-dark",
            theme = Theme.DARK,
            alsoAssert = { assertTagsExist(*TAGS) },
        ) { Fixture() }
    }

    @Test
    fun libraryLargeFont() {
        composeRule.captureGolden(
            name = "library-large-font",
            fontScale = LARGE_FONT_SCALE,
            alsoAssert = { assertTagsExist(*TAGS) },
        ) { Fixture() }
    }

    /**
     * A populated, unfiltered library — the state the app actually opens in.
     *
     * Deliberately not the filtered state the occlusion tests use. That fixture exists to reproduce
     * PR #95's keyboard bug; this one exists to be a fair picture of the screen, and a permanent
     * search query in every golden would make each one a picture of an edge case.
     */
    @Composable
    private fun Fixture() {
        LibraryScreen(
            uiState = LibraryUiState(media = BOOKS.mapIndexed { i, title -> book("id-$i", title) }),
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
                releaseYear = 1969,
                purchasePrice = null,
                createdAt = Instant.fromEpochMilliseconds(0),
                coverImageHash = null,
            ),
        details = null,
    )

    private companion object {
        /**
         * Real titles rather than "Alpha Title 1..20".
         *
         * A golden is read by a human, and generated placeholder text of uniform width hides
         * exactly the defect this variant set exists to catch: a title that wraps, elides or pushes
         * its status chip off the row. Varied natural lengths, and enough of them to fill the
         * display and run under the navigation bar.
         */
        val BOOKS =
            listOf(
                "The Left Hand of Darkness",
                "Piranesi",
                "A Memory Called Empire",
                "The Dispossessed",
                "Gödel, Escher, Bach: An Eternal Golden Braid",
                "Solaris",
                "The Fifth Season",
                "Ancillary Justice",
                "Roadside Picnic",
                "The Master and Margarita",
                "Blindsight",
                "The City & the City",
            )

        /** 2.0 is the top of Android's accessibility font-size range on current versions. */
        const val LARGE_FONT_SCALE = 2.0f

        val TAGS =
            arrayOf(
                TestTags.Library.ADD_BUTTON,
                TestTags.Library.SEARCH_FIELD,
                TestTags.Library.MEDIA_LIST,
            )
    }
}
