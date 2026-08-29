@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingSessionEntity
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.features.books.timer.ReadingTimerResult
import com.hub.media.features.books.timer.ReadingTimerState
import com.hub.media.ui.BookDetailUiState
import kotlin.time.Instant

private val PREVIEW_BOOK =
    MediaItemEntity(
        id = "book-1",
        type = MediaType.BOOK,
        title = "The Great Gatsby",
        releaseYear = 1925,
        purchasePrice = 9.99,
        createdAt = Instant.fromEpochMilliseconds(0),
        coverImageHash = null,
    )

private val PREVIEW_DETAILS =
    BookDetailsEntity(
        mediaId = "book-1",
        isbn = "9780743273565",
        format = BookFormat.PHYSICAL,
        totalPages = 180,
    )

private val PREVIEW_SESSIONS =
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

/**
 * Preview session history that additionally includes a null-[ReadingSessionEntity.durationSeconds]
 * entry (a backlogged manual session with no recorded duration, schema v2) on its own third day, so
 * the timeline's "Duration unknown" [StatBadge] path is covered by a preview -- see
 * [ReadingHistoryTabUnknownDurationPreview].
 */
private val PREVIEW_SESSIONS_WITH_UNKNOWN_DURATION =
    PREVIEW_SESSIONS +
        ReadingSessionEntity(
            id = "session-0",
            mediaId = "book-1",
            timestampStart = Instant.fromEpochMilliseconds(1_699_800_000_000),
            timestampEnd = Instant.fromEpochMilliseconds(1_699_800_000_000),
            durationSeconds = null,
            startUnit = 0.0,
            endUnit = 0.0,
            deltaPages = null,
            notes = null,
        )

/** Preview of the book detail screen with an established session history. */
@Preview(showBackground = true)
@Composable
private fun BookDetailScreenReadyPreview() {
    MediaTrackerTheme {
        BookDetailScreen(
            uiState =
                BookDetailUiState.Ready(
                    book = PREVIEW_BOOK,
                    details = PREVIEW_DETAILS,
                    sessions = PREVIEW_SESSIONS,
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

/** Preview of the book detail screen with a finished timer run awaiting save. */
@Preview(showBackground = true)
@Composable
private fun BookDetailScreenPendingSessionPreview() {
    MediaTrackerTheme {
        BookDetailScreen(
            uiState =
                BookDetailUiState.Ready(
                    book = PREVIEW_BOOK,
                    details = PREVIEW_DETAILS,
                    sessions = PREVIEW_SESSIONS,
                    pendingSession =
                        ReadingTimerResult(
                            timestampStart = Instant.fromEpochMilliseconds(1_700_100_000_000),
                            timestampEnd = Instant.fromEpochMilliseconds(1_700_101_200_000),
                            durationSeconds = 1_200,
                        ),
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

/** Preview of the book detail screen while the initial book/session data is still loading. */
@Preview(showBackground = true)
@Composable
private fun BookDetailScreenLoadingPreview() {
    MediaTrackerTheme {
        BookDetailScreen(
            uiState = BookDetailUiState.Loading,
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

/**
 * Preview of the Details tab's content in isolation (ROADMAP Task 6 Phase D -- previews now cover
 * both tabs, alongside [BookDetailScreenReadyPreview] above which renders the whole screen
 * defaulted to this same tab). Also covers: no cover ([PREVIEW_BOOK.coverImageHash] is null), the
 * timer card, and the [ProgressSection]/[MetadataCard] split from the books-polish revamp.
 */
@Preview(showBackground = true)
@Composable
private fun DetailsTabPreview() {
    MediaTrackerTheme {
        DetailsTab(
            book = PREVIEW_BOOK,
            details = PREVIEW_DETAILS,
            currentProgress = 78.0,
            coverStorageDir = "/fake/path",
            isRefetchingCover = false,
            timerState = ReadingTimerState.Idle,
            elapsedSeconds = 0,
            onStartReading = {},
            onPauseReading = {},
            onResumeReading = {},
            onStopReading = {},
            onStatusChange = {},
            onCopyIsbn = {},
            onRefetchCover = {},
        )
    }
}

/** Dark-theme counterpart of [DetailsTabPreview], same data. */
@Preview(showBackground = true)
@Composable
private fun DetailsTabDarkPreview() {
    MediaTrackerTheme(darkTheme = true, dynamicColor = false) {
        DetailsTab(
            book = PREVIEW_BOOK,
            details = PREVIEW_DETAILS,
            currentProgress = 78.0,
            coverStorageDir = "/fake/path",
            isRefetchingCover = false,
            timerState = ReadingTimerState.Idle,
            elapsedSeconds = 0,
            onStartReading = {},
            onPauseReading = {},
            onResumeReading = {},
            onStopReading = {},
            onStatusChange = {},
            onCopyIsbn = {},
            onRefetchCover = {},
        )
    }
}

/**
 * Awkward-state preview: an unusually long title (heading-block wrap/overflow), a book explicitly
 * [TrackingMode.PAGES] with no known [BookDetailsEntity.totalPages] (bare "Page 142" progress text,
 * no progress bar -- see [progressFraction]'s KDoc -- and [R.string.detail_value_unknown] in
 * [MetadataCard]'s total-pages row).
 */
@Preview(showBackground = true)
@Composable
private fun DetailsTabLongTitleUnknownTotalPagesPreview() {
    MediaTrackerTheme {
        DetailsTab(
            book =
                PREVIEW_BOOK.copy(
                    title =
                        "The Extraordinarily Long and Overly Descriptive Title of a Book That " +
                            "Simply Refuses to Fit on a Single Line, Volume One",
                ),
            details = PREVIEW_DETAILS.copy(totalPages = null, trackingMode = TrackingMode.PAGES),
            currentProgress = 142.0,
            coverStorageDir = "/fake/path",
            isRefetchingCover = false,
            timerState = ReadingTimerState.Idle,
            elapsedSeconds = 0,
            onStartReading = {},
            onPauseReading = {},
            onResumeReading = {},
            onStopReading = {},
            onStatusChange = {},
            onCopyIsbn = {},
            onRefetchCover = {},
        )
    }
}

/** Awkward-state preview: no session ever logged, so [ProgressSection] shows its "not started" message. */
@Preview(showBackground = true)
@Composable
private fun DetailsTabNoProgressPreview() {
    MediaTrackerTheme {
        DetailsTab(
            book = PREVIEW_BOOK,
            details = PREVIEW_DETAILS,
            currentProgress = null,
            coverStorageDir = "/fake/path",
            isRefetchingCover = false,
            timerState = ReadingTimerState.Idle,
            elapsedSeconds = 0,
            onStartReading = {},
            onPauseReading = {},
            onResumeReading = {},
            onStopReading = {},
            onStatusChange = {},
            onCopyIsbn = {},
            onRefetchCover = {},
        )
    }
}

/**
 * Preview of the Reading history tab's content in isolation (ROADMAP Task 6 Phase D). No longer
 * includes the timer card (books-polish pass moved it to [DetailsTabPreview]/[DetailsTab]); now
 * renders as the books-polish-pass timeline (see [buildTimelineEntries]/[TimelineRow]).
 */
@Preview(showBackground = true)
@Composable
private fun ReadingHistoryTabPreview() {
    MediaTrackerTheme {
        ReadingHistoryTab(
            sessions = PREVIEW_SESSIONS,
            onLogManuallyClick = {},
            onEditSessionClick = {},
            onDeleteSessionClick = {},
        )
    }
}

/** Dark-theme counterpart of [ReadingHistoryTabPreview], same data. */
@Preview(showBackground = true)
@Composable
private fun ReadingHistoryTabDarkPreview() {
    MediaTrackerTheme(darkTheme = true, dynamicColor = false) {
        ReadingHistoryTab(
            sessions = PREVIEW_SESSIONS,
            onLogManuallyClick = {},
            onEditSessionClick = {},
            onDeleteSessionClick = {},
        )
    }
}

/** Awkward-state preview: no sessions logged yet, so the timeline shows its empty state instead. */
@Preview(showBackground = true)
@Composable
private fun ReadingHistoryTabEmptyPreview() {
    MediaTrackerTheme {
        ReadingHistoryTab(
            sessions = emptyList(),
            onLogManuallyClick = {},
            onEditSessionClick = {},
            onDeleteSessionClick = {},
        )
    }
}

/**
 * Awkward-state preview: [PREVIEW_SESSIONS_WITH_UNKNOWN_DURATION] includes a session with a null
 * [ReadingSessionEntity.durationSeconds] (schema v2 backlogged manual entry) -- verifies the
 * timeline's duration [StatBadge] renders "Duration unknown" rather than a misleading "0:00:00".
 */
@Preview(showBackground = true)
@Composable
private fun ReadingHistoryTabUnknownDurationPreview() {
    MediaTrackerTheme {
        ReadingHistoryTab(
            sessions = PREVIEW_SESSIONS_WITH_UNKNOWN_DURATION,
            onLogManuallyClick = {},
            onEditSessionClick = {},
            onDeleteSessionClick = {},
        )
    }
}

// --- ManualSessionDialog / PendingSessionDialog previews (ROADMAP Task 7 Phase E) ---
// Both are `private` composables, so their previews live in this same file rather than beside
// EditBookScreen/SettingsScreen's own preview blocks.

/** Preview of [ManualSessionDialog] in create mode, page-tracked book (light theme). */
@Preview(showBackground = true)
@Composable
private fun ManualSessionDialogPageModePreview() {
    MediaTrackerTheme {
        ManualSessionDialog(
            currentProgress = 42.0,
            trackingMode = TrackingMode.PAGES,
            sessionToEdit = null,
            onSave = { _, _, _, _, _, _ -> },
            onDismiss = {},
        )
    }
}

/** Dark-theme counterpart of [ManualSessionDialogPageModePreview], same data. */
@Preview(showBackground = true)
@Composable
private fun ManualSessionDialogPageModeDarkPreview() {
    MediaTrackerTheme(darkTheme = true, dynamicColor = false) {
        ManualSessionDialog(
            currentProgress = 42.0,
            trackingMode = TrackingMode.PAGES,
            sessionToEdit = null,
            onSave = { _, _, _, _, _, _ -> },
            onDismiss = {},
        )
    }
}

/**
 * Preview of [ManualSessionDialog] in create mode, percent-tracked book -- covers the manual
 * pages-read field ([TrackingMode.PERCENT] shows it; [TrackingMode.PAGES] derives it instead, see
 * [ManualSessionDialogPageModePreview]) and the "%" position-field suffix.
 */
@Preview(showBackground = true)
@Composable
private fun ManualSessionDialogPercentModePreview() {
    MediaTrackerTheme {
        ManualSessionDialog(
            currentProgress = 37.0,
            trackingMode = TrackingMode.PERCENT,
            sessionToEdit = null,
            onSave = { _, _, _, _, _, _ -> },
            onDismiss = {},
        )
    }
}

/**
 * Preview of [ManualSessionDialog] in edit mode, prefilled from [PREVIEW_SESSIONS]' first entry --
 * covers the "Create vs. edit" prefill path (date/time/duration/positions/notes all seeded from the
 * session being edited) described in this dialog's KDoc.
 */
@Preview(showBackground = true)
@Composable
private fun ManualSessionDialogEditModePreview() {
    MediaTrackerTheme {
        ManualSessionDialog(
            currentProgress = 78.0,
            trackingMode = TrackingMode.PAGES,
            sessionToEdit = PREVIEW_SESSIONS.first(),
            onSave = { _, _, _, _, _, _ -> },
            onDismiss = {},
        )
    }
}

/**
 * Awkward-state preview of [ManualSessionDialog]: an out-of-range/unparseable duration
 * (`durationText` seeded past [MAX_MANUAL_DURATION_MINUTES]) so the duration field's `isError` +
 * [supportingText] path renders, and Save is disabled -- see the class KDoc's "Numeric field
 * validation" section.
 */
@Preview(showBackground = true)
@Composable
private fun ManualSessionDialogValidationErrorPreview() {
    MediaTrackerTheme {
        ManualSessionDialog(
            currentProgress = 42.0,
            trackingMode = TrackingMode.PAGES,
            sessionToEdit = PREVIEW_SESSIONS.first().copy(durationSeconds = (MAX_MANUAL_DURATION_MINUTES + 1) * 60),
            onSave = { _, _, _, _, _, _ -> },
            onDismiss = {},
        )
    }
}

/** Preview of [PendingSessionDialog] for a just-finished timer run (light theme). */
@Preview(showBackground = true)
@Composable
private fun PendingSessionDialogPreview() {
    MediaTrackerTheme {
        PendingSessionDialog(
            pendingSession =
                ReadingTimerResult(
                    timestampStart = Instant.fromEpochMilliseconds(1_700_100_000_000),
                    timestampEnd = Instant.fromEpochMilliseconds(1_700_101_200_000),
                    durationSeconds = 1_200,
                ),
            errorMessage = null,
            currentProgress = 42.0,
            trackingMode = TrackingMode.PAGES,
            onSave = { _, _, _, _ -> },
            onDiscard = {},
        )
    }
}

/** Dark-theme counterpart of [PendingSessionDialogPreview], same data. */
@Preview(showBackground = true)
@Composable
private fun PendingSessionDialogDarkPreview() {
    MediaTrackerTheme(darkTheme = true, dynamicColor = false) {
        PendingSessionDialog(
            pendingSession =
                ReadingTimerResult(
                    timestampStart = Instant.fromEpochMilliseconds(1_700_100_000_000),
                    timestampEnd = Instant.fromEpochMilliseconds(1_700_101_200_000),
                    durationSeconds = 1_200,
                ),
            errorMessage = null,
            currentProgress = 42.0,
            trackingMode = TrackingMode.PAGES,
            onSave = { _, _, _, _ -> },
            onDiscard = {},
        )
    }
}

/**
 * Awkward-state preview of [PendingSessionDialog]: a failed save leaves the dialog open with
 * [errorMessage] shown above the bottom bar and the pending session intact for retry -- see this
 * dialog's KDoc.
 */
@Preview(showBackground = true)
@Composable
private fun PendingSessionDialogErrorPreview() {
    MediaTrackerTheme {
        PendingSessionDialog(
            pendingSession =
                ReadingTimerResult(
                    timestampStart = Instant.fromEpochMilliseconds(1_700_100_000_000),
                    timestampEnd = Instant.fromEpochMilliseconds(1_700_101_200_000),
                    durationSeconds = 1_200,
                ),
            errorMessage = "Failed to save session. Please try again.",
            currentProgress = 42.0,
            trackingMode = TrackingMode.PERCENT,
            onSave = { _, _, _, _ -> },
            onDiscard = {},
        )
    }
}
