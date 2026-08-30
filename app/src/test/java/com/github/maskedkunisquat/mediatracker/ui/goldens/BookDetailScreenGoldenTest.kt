package com.github.maskedkunisquat.mediatracker.ui.goldens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.screens.BookDetailScreen
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingSessionEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.features.books.timer.ReadingTimerState
import com.hub.media.ui.BookDetailUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Instant

/**
 * Goldens for book detail -- recorded immediately **before** #81 decomposes the file, and for that
 * reason rather than for coverage's sake.
 *
 * ### Why this screen was not in #102's original eight
 *
 * #102 deliberately capped the set at roughly eight canonical surfaces, on the argument that a PR
 * carrying ten images gets reviewed while one carrying forty-five gets waved through. Book detail
 * lost its place to the screens #99 had actually moved. That was reasonable then and is wrong now:
 * `BookDetailScreen.kt` is 3,255 lines and 45 composables, and #81 is about to break it into
 * several files. A decomposition is exactly the change that should be **visually invisible**, and
 * "should be" is not evidence. These images are what turn it into "was".
 *
 * ### One image, and what is deliberately left out
 *
 * This records the Details tab: header, cover box, progress, the reading timer, and the metadata
 * card whose ISBN row carries the tap-to-copy behaviour.
 *
 * The Reading history tab is **not** recorded, and neither are the session dialogs, for the same
 * reason in two different shapes. The history tab is reached through `selectedTabIndex`, which is
 * `remember` state private to `BookDetailContent`; photographing it would mean either exposing an
 * initial-tab parameter on a production composable purely so a test could reach it, or tapping the
 * tab inside `captureGolden`'s `alsoAssert` and relying on the fact that the capture currently
 * happens after that callback. The first changes shipping code to suit a test. The second builds in
 * a silent failure -- reorder `captureGolden` later and this becomes a second picture of the
 * Details tab that still passes, which is precisely the golden-that-agrees-with-anything trap #102
 * was written against. The dialogs are separate windows, and an image of a form proves far less
 * about it than driving it does.
 *
 * Both are covered instead by the parse-migration tests landing alongside this, which is the better
 * instrument for them: they assert behaviour rather than pixels.
 *
 * ### The paired assertion, and how it was falsified
 *
 * Per #102 rule 1 the image is paired with an assertion that does not live in it; per rule 2 that
 * assertion was made to fail before being kept. It asserts the book's title and its author row --
 * falsified by changing the fixture title, which failed as required, and by clearing `authors`,
 * which failed the second matcher. The author row matters specifically because it comes from
 * `BookDetailsEntity`, not the media item, so a decomposition that drops the details argument on
 * the way through would still render a titled screen.
 *
 * Text matchers rather than tags, per AGENTS.md: this screen carries no `testTag`, and adding tags
 * to it on the way past would change the very layout these images exist to hold still.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookDetailScreenGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bookDetail() {
        composeRule.captureGolden(
            name = "book-detail",
            alsoAssert = { assertTextIsShown("The Great Gatsby", "F. Scott Fitzgerald") },
        ) { Fixture() }
    }

    /**
     * The fixture is a book mid-read with two logged sessions, not a fresh one.
     *
     * An empty book detail screen is mostly empty space, and an empty-state picture cannot show a
     * progress bar collapsing, a timeline row overflowing, or a stat badge losing its alignment --
     * which is the whole class of regression a decomposition could introduce. The second session
     * carries a note and the first does not, so the timeline renders both of its row shapes.
     */
    @Composable
    private fun Fixture() {
        BookDetailScreen(
            uiState =
                BookDetailUiState.Ready(
                    book = GOLDEN_BOOK,
                    details = GOLDEN_DETAILS,
                    sessions = GOLDEN_SESSIONS,
                ),
            timerState = ReadingTimerState.Idle,
            elapsedSeconds = 0,
            coverStorageDir = "/fake/path",
            onNavigateBack = {},
            onDeleteBook = {},
            onStartReading = {},
            onPauseReading = {},
            onResumeReading = {},
            onStopReading = {},
            onSaveSession = { _, _, _, _ -> },
            onDiscardPendingSession = {},
            onLogManualSession = { _, _, _, _, _, _ -> },
            onDeleteSession = {},
            onEditSession = { _, _, _, _, _, _, _ -> },
            onEditBook = {},
            onStatusChange = {},
            onRefetchCover = {},
        )
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private val GOLDEN_BOOK =
    MediaItemEntity(
        id = "book-1",
        type = MediaType.BOOK,
        title = "The Great Gatsby",
        releaseYear = 1925,
        purchasePrice = 9.99,
        createdAt = Instant.fromEpochMilliseconds(0),
        coverImageHash = null,
    )

@OptIn(kotlin.time.ExperimentalTime::class)
private val GOLDEN_DETAILS =
    BookDetailsEntity(
        mediaId = "book-1",
        isbn = "9780743273565",
        format = BookFormat.PHYSICAL,
        totalPages = 180,
        // READING, not the TO_READ default: the fixture has two logged sessions and renders
        // "Page 78 / 180", so a "To read" chip in the image would be a contradiction a reviewer
        // has to squint past -- and a golden a reviewer distrusts is not doing its job.
        status = ReadingStatus.READING,
        authors = "F. Scott Fitzgerald",
    )

@OptIn(kotlin.time.ExperimentalTime::class)
private val GOLDEN_SESSIONS =
    listOf(
        ReadingSessionEntity(
            id = "session-2",
            mediaId = "book-1",
            timestampStart = Instant.fromEpochMilliseconds(1_700_000_000_000),
            timestampEnd = Instant.fromEpochMilliseconds(1_700_001_800_000),
            durationSeconds = 1_800,
            startUnit = 42.0,
            endUnit = 78.0,
            deltaPages = 36,
            notes = "Great chapter on the green light.",
        ),
        ReadingSessionEntity(
            id = "session-1",
            mediaId = "book-1",
            timestampStart = Instant.fromEpochMilliseconds(1_699_900_000_000),
            timestampEnd = Instant.fromEpochMilliseconds(1_699_901_500_000),
            durationSeconds = 1_500,
            startUnit = 0.0,
            endUnit = 42.0,
            deltaPages = 42,
            notes = null,
        ),
    )
