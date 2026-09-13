package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingSessionEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.features.books.timer.ReadingTimerState
import com.hub.media.ui.BookDetailUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Behavioural tests for the Book Detail screen (#141), pinning the chrome/header/status/tab
 * surface a shared detail-screen scaffold would absorb, before that refactor moves it.
 *
 * Deliberately does not touch the reading-timer or session-editing flows -- see #141's task
 * scope. Those are `BookDetailContent`'s own concern, not the header/chrome this class pins, and
 * `BookDetailScreenGoldenTest` already exercises the timer card's idle rendering.
 */
@OptIn(ExperimentalTime::class)
class BookDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun book(
        id: String = "book-1",
        title: String = "The Great Gatsby",
        releaseYear: Int? = 1925,
    ) = MediaItemEntity(
        id = id,
        type = MediaType.BOOK,
        title = title,
        releaseYear = releaseYear,
        purchasePrice = null,
        createdAt = Instant.fromEpochMilliseconds(0),
        coverImageHash = null,
    )

    private fun details(
        mediaId: String = "book-1",
        status: ReadingStatus = ReadingStatus.READING,
        authors: String? = "F. Scott Fitzgerald",
        isbn: String? = "9780743273565",
        totalPages: Int? = 180,
    ) = BookDetailsEntity(
        mediaId = mediaId,
        isbn = isbn,
        format = BookFormat.PHYSICAL,
        totalPages = totalPages,
        status = status,
        authors = authors,
    )

    private fun session(
        id: String = "session-1",
        startUnit: Double = 0.0,
        endUnit: Double = 78.0,
    ) = ReadingSessionEntity(
        id = id,
        mediaId = "book-1",
        timestampStart = Instant.fromEpochMilliseconds(1_700_000_000_000),
        timestampEnd = Instant.fromEpochMilliseconds(1_700_001_800_000),
        durationSeconds = 1_800,
        startUnit = startUnit,
        endUnit = endUnit,
        deltaPages = (endUnit - startUnit).toInt(),
        notes = null,
    )

    private fun setContent(
        uiState: BookDetailUiState,
        onNavigateBack: () -> Unit = {},
        onDeleteBook: () -> Unit = {},
        onStatusChange: (ReadingStatus) -> Unit = {},
        onEditBook: () -> Unit = {},
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                BookDetailScreen(
                    uiState = uiState,
                    timerState = ReadingTimerState.Idle,
                    elapsedSeconds = 0,
                    // See MovieDetailScreenTest: no artwork is asserted here either.
                    coverStorageDir = NO_COVERS,
                    onNavigateBack = onNavigateBack,
                    onDeleteBook = onDeleteBook,
                    onStartReading = {},
                    onPauseReading = {},
                    onResumeReading = {},
                    onStopReading = {},
                    onSaveSession = { _, _, _, _ -> },
                    onDiscardPendingSession = {},
                    onLogManualSession = { _, _, _, _, _, _ -> },
                    onDeleteSession = {},
                    onEditSession = { _, _, _, _, _, _, _ -> },
                    onEditBook = onEditBook,
                    onStatusChange = onStatusChange,
                    onRefetchCover = {},
                )
            }
        }
    }

    // --- Chrome: title, back, edit/delete actions ---

    @Test
    fun ready_showsTitleInTopBarAndHeader_unlikeMovieAndTV() {
        // Book's own divergence: BookHeader (BookDetailsTab.kt) repeats the title as a headline
        // below the cover, in addition to the CenterAlignedTopAppBar's title -- Movie/TV render
        // the title only once, in their TopAppBar. onNodeWithText would fail here with "found 2
        // nodes"; that failure is itself the behaviour being pinned.
        setContent(BookDetailUiState.Ready(book = book(), details = details()))

        composeRule.onAllNodesWithText("The Great Gatsby").assertCountEquals(2)
    }

    @Test
    fun backIcon_invokesOnNavigateBack() {
        var backCount = 0
        setContent(BookDetailUiState.Ready(book = book(), details = details()), onNavigateBack = { backCount++ })

        composeRule.onNodeWithContentDescription(context.getString(R.string.navigate_back)).performClick()

        assertEquals(1, backCount)
    }

    @Test
    fun ready_showsEditAction_andInvokesOnEditBook() {
        var edits = 0
        setContent(BookDetailUiState.Ready(book = book(), details = details()), onEditBook = { edits++ })

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.edit_book_content_description))
            .performClick()

        assertEquals(1, edits)
    }

    @Test
    fun loading_hidesEditAndDeleteActions() {
        setContent(BookDetailUiState.Loading)

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.edit_book_content_description))
            .assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.delete_book_content_description))
            .assertDoesNotExist()
    }

    @Test
    fun notFound_hidesEditAndDeleteActions() {
        setContent(BookDetailUiState.NotFound)

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.edit_book_content_description))
            .assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.delete_book_content_description))
            .assertDoesNotExist()
    }

    @Test
    fun loading_showsProgressIndicator() {
        setContent(BookDetailUiState.Loading)

        composeRule.onNode(isProgressIndicator).assertExists()
    }

    /**
     * Book's own divergence: unlike Movie/TV, whose stateless screen itself pops on NotFound (a
     * `LaunchedEffect(uiState)` inside the composable), Book's navigate-back-on-NotFound logic
     * lives one level up in `BookDetailScreenRoute` -- the stateless `BookDetailScreen` tested here
     * simply renders nothing for NotFound and never calls [onNavigateBack] itself.
     */
    @Test
    fun notFound_rendersNothingAndDoesNotInvokeOnNavigateBack() {
        var backCount = 0
        setContent(BookDetailUiState.NotFound, onNavigateBack = { backCount++ })

        assertEquals(0, backCount)
        composeRule.onNodeWithText("The Great Gatsby").assertDoesNotExist()
    }

    // --- Delete: Book's own dialog, wired differently from Movie/TV's shared-label trick ---

    @Test
    fun deleteAction_showsConfirmationWithBookTitle_andDoesNotDeleteYet() {
        var deletes = 0
        setContent(
            BookDetailUiState.Ready(book = book(title = "Dune"), details = details()),
            onDeleteBook = { deletes++ },
        )

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.delete_book_content_description))
            .performClick()

        // Unlike Movie/TV's dialogs, Book's confirm button is labelled plain "Delete"
        // (delete_button), not a reuse of the icon's own content description -- so the body text
        // alone, which interpolates the book's title, is enough to find the dialog unambiguously.
        val confirmBody = context.getString(R.string.delete_book_body, "Dune")
        composeRule.onNodeWithText(confirmBody).assertIsDisplayed()
        assertEquals("tapping delete must ask, not act", 0, deletes)
    }

    @Test
    fun confirmingTheDeleteDialog_invokesOnDeleteBook() {
        var deletes = 0
        setContent(BookDetailUiState.Ready(book = book(), details = details()), onDeleteBook = { deletes++ })

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.delete_book_content_description))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.delete_button)).performClick()

        assertEquals(1, deletes)
    }

    @Test
    fun cancellingTheDeleteDialog_dismissesWithoutInvokingOnDeleteBook() {
        var deletes = 0
        setContent(BookDetailUiState.Ready(book = book(), details = details()), onDeleteBook = { deletes++ })

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.delete_book_content_description))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.cancel_button)).performClick()

        assertEquals(0, deletes)
        val confirmBody = context.getString(R.string.delete_book_body, "The Great Gatsby")
        composeRule.onNodeWithText(confirmBody).assertDoesNotExist()
    }

    // --- Header rows: year, progress ---

    @Test
    fun ready_rendersPublishedYear() {
        setContent(BookDetailUiState.Ready(book = book(releaseYear = 1925), details = details()))

        composeRule.onNodeWithText(context.getString(R.string.detail_published_year, 1925)).assertIsDisplayed()
    }

    @Test
    fun releaseYearNull_rendersNoPublishedYearRow() {
        setContent(BookDetailUiState.Ready(book = book(releaseYear = null), details = details()))

        composeRule.onNodeWithText("Published", substring = true).assertDoesNotExist()
    }

    @Test
    fun noSessionsLogged_rendersProgressNotStartedText() {
        setContent(BookDetailUiState.Ready(book = book(), details = details(), sessions = emptyList()))

        composeRule.onNodeWithText(context.getString(R.string.detail_progress_not_started)).assertIsDisplayed()
    }

    @Test
    fun withProgress_rendersProgressSection() {
        // Light check per #141's task scope: only that the progress section renders something
        // other than the "not started" copy once a session exists -- not the exact formatted
        // fraction, which belongs to formatProgress's own unit coverage.
        setContent(
            BookDetailUiState.Ready(book = book(), details = details(totalPages = 180), sessions = listOf(session())),
        )

        composeRule.onNodeWithText(context.getString(R.string.detail_progress_not_started)).assertDoesNotExist()
    }

    // --- Status control: Book's StatusChip + dropdown ---

    @Test
    fun tappingAStatusOption_invokesOnStatusChangeWithThatStatus() {
        var captured: ReadingStatus? = null
        setContent(
            BookDetailUiState.Ready(book = book(), details = details(status = ReadingStatus.READING)),
            onStatusChange = { captured = it },
        )

        val chipLabel =
            context.getString(R.string.status_prefix, context.getString(R.string.reading_status_reading))
        composeRule.onNodeWithText(chipLabel).performClick()
        composeRule.onNodeWithText(context.getString(R.string.reading_status_finished)).performClick()

        assertEquals(ReadingStatus.FINISHED, captured)
    }

    // --- Tab switching: Details <-> Reading history ---

    @Test
    fun tappingReadingHistoryTab_showsLogSessionAffordance_andDetailsTabHidesTheStatusChip() {
        setContent(BookDetailUiState.Ready(book = book(), details = details()))

        val chipLabel =
            context.getString(R.string.status_prefix, context.getString(R.string.reading_status_reading))
        composeRule.onNodeWithText(chipLabel).assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.tab_reading_history)).performClick()

        composeRule.onNodeWithText(context.getString(R.string.log_session_manually)).assertIsDisplayed()
        composeRule.onNodeWithText(chipLabel).assertDoesNotExist()

        composeRule.onNodeWithText(context.getString(R.string.tab_details)).performClick()

        composeRule.onNodeWithText(chipLabel).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.log_session_manually)).assertDoesNotExist()
    }

    // --- Artwork: Book's own divergence from Movie/TV ---

    @Test
    fun coverImageHashNull_stillRendersThePlaceholder_unlikeMovieAndTV() {
        // BookDetailsTab's InteractiveCoverBox calls CoverImage unconditionally -- unlike
        // MovieDetailScreen/TVShowDetailScreen, which skip CoverImage entirely (via `?.let`) for a
        // null hash, Book's cover box is always present and CoverImage's own null-hash branch
        // renders the placeholder synchronously. A shared scaffold has to pick one of these.
        setContent(BookDetailUiState.Ready(book = book(), details = details()))

        composeRule.onNodeWithText(BOOK_COVER_PLACEHOLDER_EMOJI).assertIsDisplayed()
    }

    private companion object {
        /** See `MovieDetailScreenTest`: this class asserts text and controls, never real artwork. */
        const val NO_COVERS = "no-covers-in-this-fixture"

        /** [MediaType.BOOK]'s placeholder glyph in `CoverImage`'s `CoverPlaceholder` (its default). */
        const val BOOK_COVER_PLACEHOLDER_EMOJI = "📖"
    }
}

/**
 * Matches a node carrying [SemanticsProperties.ProgressBarRangeInfo] -- see
 * `MovieDetailScreenTest`'s copy of this matcher for why it exists rather than a
 * contentDescription/testTag lookup.
 */
private val isProgressIndicator =
    SemanticsMatcher("has ProgressBarRangeInfo") {
        it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null
    }
