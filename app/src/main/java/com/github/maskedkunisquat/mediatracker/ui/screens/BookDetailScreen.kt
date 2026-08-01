@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.BookDetailViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.components.CoverImage
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingSessionEntity
import com.hub.media.features.books.timer.ReadingTimerResult
import com.hub.media.features.books.timer.ReadingTimerState
import com.hub.media.ui.AppContainer
import com.hub.media.ui.BookDetailUiState
import com.hub.media.ui.BookDetailViewModel
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Route-level composable for the book detail screen (ROADMAP Task4 Phase C).
 * Connects the [BookDetailViewModel] to the stateless [BookDetailScreen] and handles navigation.
 *
 * If [BookDetailUiState.NotFound] is emitted (the book was deleted, e.g. from the library screen,
 * while this screen was open), [onNavigateBack] fires automatically via [LaunchedEffect] since
 * there is nothing left to show.
 *
 * @param appContainer The dependency container for creating ViewModels.
 * @param coverStorageDir Absolute path to the cover image storage directory.
 * @param bookId The media id this screen was opened for; forwarded to [BookDetailViewModelFactory].
 * @param onNavigateBack Callback to navigate back (back button, or automatic on [BookDetailUiState.NotFound]).
 */
@Composable
fun BookDetailScreenRoute(
    appContainer: AppContainer,
    coverStorageDir: String,
    bookId: String,
    onNavigateBack: () -> Unit,
) {
    val viewModel: BookDetailViewModel = viewModel(
        factory = BookDetailViewModelFactory(appContainer, bookId),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val timerState by viewModel.timerState.collectAsStateWithLifecycle()
    val elapsedSeconds by viewModel.elapsedSeconds.collectAsStateWithLifecycle()

    val notFound = uiState is BookDetailUiState.NotFound
    LaunchedEffect(notFound) {
        if (notFound) {
            onNavigateBack()
        }
    }

    BookDetailScreen(
        uiState = uiState,
        timerState = timerState,
        elapsedSeconds = elapsedSeconds,
        coverStorageDir = coverStorageDir,
        onNavigateBack = onNavigateBack,
        onDeleteBook = viewModel::deleteBook,
        onStartReading = viewModel::startReading,
        onPauseReading = viewModel::pauseReading,
        onResumeReading = viewModel::resumeReading,
        onStopReading = viewModel::stopReading,
        onSaveSession = { startUnit, endUnit, deltaPages, notes ->
            viewModel.saveSession(startUnit, endUnit, deltaPages, notes)
        },
        onDiscardPendingSession = viewModel::discardPendingSession,
        onLogManualSession = { durationMinutes, timestampEnd, startUnit, endUnit, deltaPages, notes ->
            // Manual entry has no live timer to read timestamps from, so the dialog itself
            // collects a session date + end time (defaulting to today/now, so the common "I just
            // finished reading" case still takes zero extra taps) and derives timestampEnd from
            // that selection -- see ManualSessionDialog. timestampStart is then simply the
            // entered duration subtracted from timestampEnd, which supports backdating an entry
            // to an arbitrary past date/time as well as the zero-extra-tap "just now" case.
            //
            // durationMinutes is null when the duration field was left blank (schema v2, ROADMAP
            // Task 5 pre-phase -- backlogged manual sessions don't always have a known duration).
            // With no duration to subtract, timestampStart is set equal to timestampEnd: a
            // zero-length interval that anchors the session to its date without asserting a false
            // span -- see ManualSessionDialog's KDoc.
            val start = if (durationMinutes != null) timestampEnd - durationMinutes.minutes else timestampEnd
            viewModel.logManualSession(
                timestampStart = start,
                timestampEnd = timestampEnd,
                durationSeconds = durationMinutes?.let { it * 60 },
                startUnit = startUnit,
                endUnit = endUnit,
                deltaPages = deltaPages,
                notes = notes,
            )
        },
        onDeleteSession = viewModel::deleteSession,
    )
}

/**
 * Stateless book detail screen composable (AGENTS.md §5 State Hoisting).
 *
 * Renders [uiState]:
 * - [BookDetailUiState.Loading]: a centered [CircularProgressIndicator].
 * - [BookDetailUiState.NotFound]: nothing (the route wrapper navigates back before this would be
 *   visible for more than a frame).
 * - [BookDetailUiState.Ready]: cover + metadata header, timer card, manual-entry affordance, and
 *   session history -- see [BookDetailContent].
 *
 * @param uiState Current [BookDetailUiState].
 * @param timerState Current [ReadingTimerState], gating which timer buttons are shown.
 * @param elapsedSeconds Live elapsed seconds for the running/paused timer display.
 * @param coverStorageDir Absolute path to the cover image storage directory.
 * @param onNavigateBack Called when the back icon is pressed.
 * @param onDeleteBook Called after the delete-book confirmation dialog (opened from the TopAppBar
 *   delete icon, only shown for [BookDetailUiState.Ready]) is confirmed. Wired by
 *   [BookDetailScreenRoute] to [BookDetailViewModel.deleteBook], whose [Resource.Error][com.hub.media.core.util.Resource.Error]
 *   surfaces via [BookDetailUiState.Ready.errorMessage] the same way a failed
 *   [BookDetailViewModel.saveSession]/[BookDetailViewModel.deleteSession] does.
 * @param onStartReading Called to start a fresh timer run.
 * @param onPauseReading Called to pause the running timer.
 * @param onResumeReading Called to resume a paused timer.
 * @param onStopReading Called to stop the timer, producing a pending session.
 * @param onSaveSession Called with (startUnit, endUnit, deltaPages, notes) to persist the pending
 *   timer-backed session.
 * @param onDiscardPendingSession Called to abandon the pending timer-backed session.
 * @param onLogManualSession Called with (durationMinutes, timestampEnd, startUnit, endUnit,
 *   deltaPages, notes) from the manual-entry form; timestampEnd reflects the session date/time
 *   the user picked in the dialog (defaulting to now, but backdatable to a past date/time).
 *   durationMinutes is `null` when the duration field was left blank (schema v2, ROADMAP Task 5
 *   pre-phase) -- duration is optional for manual entries.
 * @param onDeleteSession Called with a session id after its delete is confirmed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    uiState: BookDetailUiState,
    timerState: ReadingTimerState,
    elapsedSeconds: Long,
    coverStorageDir: String,
    onNavigateBack: () -> Unit,
    onDeleteBook: () -> Unit,
    onStartReading: () -> Unit,
    onPauseReading: () -> Unit,
    onResumeReading: () -> Unit,
    onStopReading: () -> Unit,
    onSaveSession: (startUnit: Double, endUnit: Double, deltaPages: Int?, notes: String?) -> Unit,
    onDiscardPendingSession: () -> Unit,
    onLogManualSession: (
        durationMinutes: Long?,
        timestampEnd: Instant,
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int?,
        notes: String?,
    ) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    var showDeleteBookDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = if (uiState is BookDetailUiState.Ready) uiState.book.title else "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                actions = {
                    if (uiState is BookDetailUiState.Ready) {
                        IconButton(onClick = { showDeleteBookDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.delete_book_content_description),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (uiState) {
                is BookDetailUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is BookDetailUiState.NotFound -> {
                    // Nothing to render; the route wrapper navigates back on this state.
                }
                is BookDetailUiState.Ready -> {
                    BookDetailContent(
                        state = uiState,
                        timerState = timerState,
                        elapsedSeconds = elapsedSeconds,
                        coverStorageDir = coverStorageDir,
                        onStartReading = onStartReading,
                        onPauseReading = onPauseReading,
                        onResumeReading = onResumeReading,
                        onStopReading = onStopReading,
                        onSaveSession = onSaveSession,
                        onDiscardPendingSession = onDiscardPendingSession,
                        onLogManualSession = onLogManualSession,
                        onDeleteSession = onDeleteSession,
                    )
                }
            }
        }
    }

    if (showDeleteBookDialog && uiState is BookDetailUiState.Ready) {
        DeleteBookConfirmationDialog(
            bookTitle = uiState.book.title,
            onConfirm = {
                showDeleteBookDialog = false
                onDeleteBook()
            },
            onDismiss = { showDeleteBookDialog = false },
        )
    }
}

/**
 * Delete-book confirmation dialog (Task4 Phase E), opened from the TopAppBar delete icon.
 * Mirrors the wording of the confirmation dialog that used to live on `LibraryScreen`'s book
 * cards before deletion moved here.
 */
@Composable
private fun DeleteBookConfirmationDialog(
    bookTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_book_title)) },
        text = { Text(stringResource(R.string.delete_book_body, bookTitle)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.delete_button))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        },
    )
}

/**
 * Content for [BookDetailUiState.Ready]: header, timer, manual-entry affordance, and session
 * history, plus the pending-session and delete-confirmation dialogs.
 */
@Composable
private fun BookDetailContent(
    state: BookDetailUiState.Ready,
    timerState: ReadingTimerState,
    elapsedSeconds: Long,
    coverStorageDir: String,
    onStartReading: () -> Unit,
    onPauseReading: () -> Unit,
    onResumeReading: () -> Unit,
    onStopReading: () -> Unit,
    onSaveSession: (startUnit: Double, endUnit: Double, deltaPages: Int?, notes: String?) -> Unit,
    onDiscardPendingSession: () -> Unit,
    onLogManualSession: (
        durationMinutes: Long?,
        timestampEnd: Instant,
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int?,
        notes: String?,
    ) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    var sessionToDelete by remember { mutableStateOf<ReadingSessionEntity?>(null) }
    var showManualEntry by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            BookHeader(
                book = state.book,
                details = state.details,
                currentProgress = state.currentProgress,
                coverStorageDir = coverStorageDir,
            )
        }

        item {
            TimerCard(
                timerState = timerState,
                elapsedSeconds = elapsedSeconds,
                onStart = onStartReading,
                onPause = onPauseReading,
                onResume = onResumeReading,
                onStop = onStopReading,
            )
        }

        item {
            TextButton(onClick = { showManualEntry = true }) {
                Text(stringResource(R.string.log_session_manually))
            }
        }

        // The pending-session dialog already surfaces state.errorMessage while a timer-backed
        // session is awaiting save (see below). A manual-entry failure has no dialog left open to
        // show it in (that dialog closes optimistically on Save -- see ManualSessionDialog KDoc),
        // so show it here instead, scoped to the case where it isn't already visible elsewhere.
        val manualEntryError = state.errorMessage
        if (manualEntryError != null && state.pendingSession == null) {
            item {
                Text(
                    text = manualEntryError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.session_history_title),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (state.sessions.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.no_sessions_logged_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    onDeleteClick = { sessionToDelete = session },
                )
            }
        }
    }

    val pendingSession = state.pendingSession
    if (pendingSession != null) {
        PendingSessionDialog(
            pendingSession = pendingSession,
            errorMessage = state.errorMessage,
            currentProgress = state.currentProgress,
            onSave = onSaveSession,
            onDiscard = onDiscardPendingSession,
        )
    }

    if (showManualEntry) {
        ManualSessionDialog(
            currentProgress = state.currentProgress,
            onSave = { durationMinutes, timestampEnd, startUnit, endUnit, deltaPages, notes ->
                onLogManualSession(durationMinutes, timestampEnd, startUnit, endUnit, deltaPages, notes)
                showManualEntry = false
            },
            onDismiss = { showManualEntry = false },
        )
    }

    val session = sessionToDelete
    if (session != null) {
        DeleteSessionConfirmationDialog(
            onConfirm = {
                onDeleteSession(session.id)
                sessionToDelete = null
            },
            onDismiss = { sessionToDelete = null },
        )
    }
}

/**
 * Cover + metadata header: cover thumbnail (left), title/release year/ISBN/format/total pages and
 * current progress (right). ISBN/format/total pages are only shown when [details] is non-null and
 * the individual field is present; [currentProgress] formatting is delegated to [formatProgress].
 */
@Composable
private fun BookHeader(
    book: MediaItemEntity,
    details: BookDetailsEntity?,
    currentProgress: Double?,
    coverStorageDir: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(160.dp),
        ) {
            CoverImage(
                coverDir = coverStorageDir,
                coverImageHash = book.coverImageHash,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val releaseYear = book.releaseYear
            if (releaseYear != null) {
                Text(
                    text = stringResource(R.string.released_prefix, releaseYear),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val isbn = details?.isbn
            if (isbn != null) {
                Text(
                    text = stringResource(R.string.isbn_prefix, isbn),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (details != null) {
                Text(
                    text = stringResource(R.string.format_prefix, details.format.displayLabel()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val totalPages = details?.totalPages
            if (totalPages != null) {
                Text(
                    text = stringResource(R.string.total_pages_prefix, totalPages),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val progressText = formatProgress(currentProgress, details?.totalPages)
            if (progressText != null) {
                Text(
                    text = stringResource(R.string.progress_prefix, progressText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Maps [BookFormat] to its human-readable display label (e.g. [BookFormat.PHYSICAL] -> "Physical"). */
@Composable
private fun BookFormat.displayLabel(): String = when (this) {
    BookFormat.PHYSICAL -> stringResource(R.string.book_format_physical)
    BookFormat.EBOOK -> stringResource(R.string.book_format_ebook)
    BookFormat.AUDIOBOOK -> stringResource(R.string.book_format_audiobook)
}

/**
 * Timer card: formatted elapsed time and action buttons gated by [timerState] --
 * [ReadingTimerState.Idle] shows "Start reading"; [ReadingTimerState.Running] shows "Pause" +
 * "Stop"; [ReadingTimerState.Paused] shows "Resume" + "Stop".
 */
@Composable
private fun TimerCard(
    timerState: ReadingTimerState,
    elapsedSeconds: Long,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = formatElapsed(elapsedSeconds),
                style = MaterialTheme.typography.displaySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when (timerState) {
                    is ReadingTimerState.Idle -> {
                        Button(onClick = onStart) { Text(stringResource(R.string.start_reading_button)) }
                    }
                    is ReadingTimerState.Running -> {
                        Button(onClick = onPause) { Text(stringResource(R.string.pause_button)) }
                        Button(onClick = onStop) { Text(stringResource(R.string.stop_button)) }
                    }
                    is ReadingTimerState.Paused -> {
                        Button(onClick = onResume) { Text(stringResource(R.string.resume_button)) }
                        Button(onClick = onStop) { Text(stringResource(R.string.stop_button)) }
                    }
                }
            }
        }
    }
}

/**
 * Dialog for saving a finished timer run ([pendingSession]). Visibility is entirely
 * state-driven by the caller (rendered only while `state.pendingSession != null`), so unlike
 * [ManualSessionDialog] it never closes itself: a failed [onSave] leaves `pendingSession` set
 * (see [BookDetailViewModel.saveSession] KDoc), which keeps this dialog open with
 * [errorMessage] displayed so the user can correct their input and retry without re-timing the
 * session; a successful save clears `pendingSession`, which naturally dismisses the dialog.
 *
 * Start/end position and pages-read fields are digit-and-decimal-point filtered so a negative
 * value (the one input [LogReadingSessionUseCase][com.hub.media.features.books.domain.LogReadingSessionUseCase]
 * rejects) can never be typed in the first place.
 *
 * `onDismissRequest` is a no-op: this dialog represents a *finished, already-timed* run, so an
 * accidental outside tap or back press must not silently discard it the way it would discard an
 * un-started form. [onDiscard] (the explicit "Discard" button) is the only path that abandons the
 * pending session -- see [BookDetailViewModel.discardPendingSession].
 *
 * [currentProgress] (Task4 Phase E) prefills the start-position field with the book's last-known
 * progress as a "resume where you left off" convenience -- the user can freely edit or clear it.
 */
@Composable
private fun PendingSessionDialog(
    pendingSession: ReadingTimerResult,
    errorMessage: String?,
    currentProgress: Double?,
    onSave: (startUnit: Double, endUnit: Double, deltaPages: Int?, notes: String?) -> Unit,
    onDiscard: () -> Unit,
) {
    var startUnitText by remember { mutableStateOf(currentProgress?.let(::formatUnit) ?: "") }
    var endUnitText by remember { mutableStateOf("") }
    var deltaPagesText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = {}, // Incidental dismiss (outside tap / back) must not discard a finished run.
        title = { Text(stringResource(R.string.save_reading_session_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        R.string.session_duration_label,
                        formatElapsed(pendingSession.durationSeconds),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = startUnitText,
                    onValueChange = { startUnitText = it.filterDecimalInput() },
                    label = { Text(stringResource(R.string.start_position_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = endUnitText,
                    onValueChange = { endUnitText = it.filterDecimalInput() },
                    label = { Text(stringResource(R.string.end_position_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = deltaPagesText,
                    onValueChange = { deltaPagesText = it.filterIntegerInput() },
                    label = { Text(stringResource(R.string.pages_read_optional_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text(stringResource(R.string.notes_optional_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        startUnitText.toDoubleOrNull() ?: 0.0,
                        endUnitText.toDoubleOrNull() ?: 0.0,
                        deltaPagesText.toIntOrNull(),
                        notesText.ifBlank { null },
                    )
                },
                enabled = startUnitText.isNotBlank() && endUnitText.isNotBlank(),
            ) {
                Text(stringResource(R.string.save_button))
            }
        },
        dismissButton = {
            Button(onClick = onDiscard) {
                Text(stringResource(R.string.discard_button))
            }
        },
    )
}

/**
 * Dialog for logging a session with no live timer involved: session date + end time, duration
 * (minutes), start/end position, optional pages-read and notes.
 *
 * The date field defaults to today and is edited via a [DatePickerDialog] (opened by tapping the
 * "Date:" button). The end time defaults to the current time and is edited via a Material 3
 * [TimePicker] (Task4 Phase E; replaces the earlier digit-filtered hour/minute text fields), hosted
 * in a custom [AlertDialog] with OK/Cancel buttons since this Material 3 version has no built-in
 * `TimePickerDialog` wrapper analogous to [DatePickerDialog] -- Cancel restores the
 * previously-selected time, mirroring the date picker's cancel-restore pattern below. Because both
 * date and time default to "now," the zero-extra-tap "I just finished reading" path is unchanged --
 * picking a date/time is purely opt-in, for backdating a session that happened in the past.
 * [TimePickerState] always exposes `hour` in 0-23 regardless of `is24Hour` (that flag only controls
 * the dial/input display), so unlike the old text fields there is no invalid-range state to guard
 * against -- the Save button's `enabled` condition no longer needs a time-validity check.
 *
 * The time button's label is formatted via `android.text.format.DateFormat.getTimeFormat`, which
 * renders in the device's locale and respects its 12h/24h display preference (the same preference
 * drives `is24Hour` on the [TimePicker] itself, via `DateFormat.is24HourFormat`).
 *
 * [timestampEnd] passed to [onSave] is derived from the selected date + hour/minute in the
 * device's local timezone (same `java.time` conversion approach as [formatSessionDate]); the
 * caller derives `timestampStart` by subtracting the entered duration from it -- or, when the
 * duration field is left blank (see below), by setting `timestampStart = timestampEnd` instead.
 *
 * [currentProgress] (Task4 Phase E) prefills the start-position field with the book's last-known
 * progress as a "resume where you left off" convenience -- the user can freely edit or clear it.
 *
 * ### Duration is optional (schema v2, ROADMAP Task 5 pre-phase)
 * The duration field may be left blank: `durationMinutes` is then `null`, forwarded all the way
 * to [com.hub.media.core.database.entities.ReadingSessionEntity.durationSeconds] as `null` rather
 * than `0` -- see that entity's KDoc for why `0` cannot double as "unknown" without corrupting
 * future time-read stats. A backlogged manual session (e.g. "I read some of this book last
 * month, I don't remember for how long") is the motivating case; a live timer run always has a
 * real duration and never takes this path. Because there is then no duration to subtract from
 * `timestampEnd`, the caller sets `timestampStart = timestampEnd`: a zero-length interval. This
 * only affects the *interval*, not the *session* -- the session is still date/time-anchored via
 * `timestampEnd` and its position bounds (`startUnit`/`endUnit`) are entered exactly as normal;
 * only its true time-span is unrepresented, which is exactly what a `null` duration already says.
 * The Save button's `enabled` condition therefore no longer requires the duration field to be
 * filled in -- only start/end position are required, same as [PendingSessionDialog].
 *
 * Unlike [PendingSessionDialog], this dialog closes optimistically as soon as Save is tapped
 * rather than waiting on a success/failure signal. There is no shared-state flag analogous to
 * `pendingSession` for the manual path that this dialog could stay bound to, and the one
 * validation [LogReadingSessionUseCase][com.hub.media.features.books.domain.LogReadingSessionUseCase]
 * performs on this input -- rejecting a negative position -- is already made unreachable by the
 * digit-and-decimal-point input filtering below, so an optimistic close cannot silently hide a
 * real failure in practice. The rare case of some other persistence failure is still surfaced via
 * `state.errorMessage` as a banner in [BookDetailContent] once this dialog has closed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualSessionDialog(
    currentProgress: Double?,
    onSave: (
        durationMinutes: Long?,
        timestampEnd: Instant,
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int?,
        notes: String?,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var durationText by remember { mutableStateOf("") }
    var startUnitText by remember { mutableStateOf(currentProgress?.let(::formatUnit) ?: "") }
    var endUnitText by remember { mutableStateOf("") }
    var deltaPagesText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf("") }

    val context = LocalContext.current
    val nowForDefaults = remember { java.time.LocalDateTime.now() }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = remember { localDateToUtcMidnightMillis(nowForDefaults.toLocalDate()) },
    )
    var showDatePicker by remember { mutableStateOf(false) }
    // Snapshot of datePickerState.selectedDateMillis taken when the picker is opened, so a
    // picked-then-Cancelled date can be reverted rather than sticking (OK leaves the in-dialog
    // selection unchanged; only Cancel restores this).
    var dateBeforePickerOpen by remember { mutableStateOf<Long?>(null) }

    val is24Hour = remember { android.text.format.DateFormat.is24HourFormat(context) }
    val timePickerState = rememberTimePickerState(
        initialHour = nowForDefaults.hour,
        initialMinute = nowForDefaults.minute,
        is24Hour = is24Hour,
    )
    var showTimePicker by remember { mutableStateOf(false) }
    // Snapshot of the selected hour/minute taken when the picker is opened, so a
    // picked-then-Cancelled time can be reverted rather than sticking (OK leaves the in-dialog
    // selection unchanged; only Cancel restores this) -- mirrors dateBeforePickerOpen above.
    var timeBeforePickerOpen by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.log_session_manually)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        dateBeforePickerOpen = datePickerState.selectedDateMillis
                        showDatePicker = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            R.string.date_label,
                            formatUtcMidnightMillis(datePickerState.selectedDateMillis),
                        ),
                    )
                }
                OutlinedButton(
                    onClick = {
                        timeBeforePickerOpen = timePickerState.hour to timePickerState.minute
                        showTimePicker = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            R.string.time_label,
                            formatTimeOfDay(context, timePickerState.hour, timePickerState.minute),
                        ),
                    )
                }
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { durationText = it.filterIntegerInput() },
                    label = { Text(stringResource(R.string.duration_minutes_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = startUnitText,
                    onValueChange = { startUnitText = it.filterDecimalInput() },
                    label = { Text(stringResource(R.string.start_position_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = endUnitText,
                    onValueChange = { endUnitText = it.filterDecimalInput() },
                    label = { Text(stringResource(R.string.end_position_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = deltaPagesText,
                    onValueChange = { deltaPagesText = it.filterIntegerInput() },
                    label = { Text(stringResource(R.string.pages_read_optional_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text(stringResource(R.string.notes_optional_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        // Blank duration => null ("unknown"), not 0 -- see class KDoc's
                        // "Duration is optional" section. durationText is digit-filtered, so a
                        // non-blank value always parses.
                        durationText.toLongOrNull(),
                        deriveTimestampEnd(
                            dateUtcMidnightMillis = datePickerState.selectedDateMillis
                                ?: localDateToUtcMidnightMillis(nowForDefaults.toLocalDate()),
                            hour = timePickerState.hour,
                            minute = timePickerState.minute,
                        ),
                        startUnitText.toDoubleOrNull() ?: 0.0,
                        endUnitText.toDoubleOrNull() ?: 0.0,
                        deltaPagesText.toIntOrNull(),
                        notesText.ifBlank { null },
                    )
                },
                enabled = startUnitText.isNotBlank() && endUnitText.isNotBlank(),
            ) {
                Text(stringResource(R.string.save_button))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        },
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.ok_button)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis = dateBeforePickerOpen
                        showDatePicker = false
                    },
                ) { Text(stringResource(R.string.cancel_button)) }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.select_time_title)) },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.ok_button)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val (hour, minute) = timeBeforePickerOpen ?: (nowForDefaults.hour to nowForDefaults.minute)
                        timePickerState.hour = hour
                        timePickerState.minute = minute
                        showTimePicker = false
                    },
                ) { Text(stringResource(R.string.cancel_button)) }
            },
        )
    }
}

/**
 * A single row in the session history list: date, duration (when known), start->end positions,
 * optional pages-read/notes, and a delete icon button.
 *
 * [ReadingSessionEntity.durationSeconds] is nullable (schema v2, ROADMAP Task 5 pre-phase): a
 * backlogged manual entry may have been saved with no known duration. When `null`, this renders
 * the positions line ([R.string.session_positions]) without a duration segment, rather than a
 * misleading "0:00:00" -- see that entity's KDoc for why `null` and `0` must stay visually and
 * semantically distinct.
 */
@Composable
private fun SessionRow(
    session: ReadingSessionEntity,
    onDeleteClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatSessionDate(session.timestampStart),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val durationSeconds = session.durationSeconds
                Text(
                    text = if (durationSeconds != null) {
                        stringResource(
                            R.string.session_duration_positions,
                            formatElapsed(durationSeconds),
                            formatUnit(session.startUnit),
                            formatUnit(session.endUnit),
                        )
                    } else {
                        stringResource(
                            R.string.session_positions,
                            formatUnit(session.startUnit),
                            formatUnit(session.endUnit),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                val deltaPages = session.deltaPages
                if (deltaPages != null) {
                    Text(
                        text = stringResource(R.string.pages_read_count, deltaPages),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val notes = session.notes
                if (!notes.isNullOrBlank()) {
                    Text(text = notes, style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete_session_content_description),
                )
            }
        }
        HorizontalDivider()
    }
}

/**
 * Delete confirmation dialog for a single reading session, matching [LibraryScreen]'s
 * delete-confirmation pattern (confirm/cancel [Button]s in an [AlertDialog]).
 */
@Composable
private fun DeleteSessionConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_session_title)) },
        text = { Text(stringResource(R.string.delete_session_body)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.delete_button))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        },
    )
}

/** Formats a seconds count as `H:MM:SS` (hours unpadded, minutes/seconds zero-padded). */
private fun formatElapsed(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%d:%02d:%02d".format(java.util.Locale.ROOT, hours, minutes, seconds)
}

/**
 * Formats [currentProgress] for display: page-style ("Page 142 / 350") when [totalPages] is
 * known, or percent-style ("37%") when [totalPages] is null -- e-reader/percentage tracking has
 * no fixed page count to show a denominator for. Returns null (nothing to show) when
 * [currentProgress] itself is null, i.e. no session has ever been logged.
 *
 * Note: This is a @Composable function that calls [stringResource] to fetch localized strings
 * from resources rather than using hardcoded string literals.
 */
@Composable
private fun formatProgress(currentProgress: Double?, totalPages: Int?): String? {
    if (currentProgress == null) return null
    return if (totalPages != null) {
        stringResource(R.string.progress_page_format, currentProgress.roundToInt(), totalPages)
    } else {
        stringResource(R.string.progress_percent_format, currentProgress.roundToInt())
    }
}

/** Formats a [Double] position value, dropping a trailing `.0` for whole-number pages. */
private fun formatUnit(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/**
 * Formats [hour]/[minute] (a [androidx.compose.material3.TimePickerState]'s always-24h `hour` and
 * `minute`, Task4 Phase E) as a time-of-day string using [android.text.format.DateFormat], which
 * renders in the device's locale and respects its 12h/24h display preference -- the same
 * preference [android.text.format.DateFormat.is24HourFormat] supplies to the `TimePicker`'s
 * `is24Hour` at the call site. The wrapping date value is irrelevant; only the time-of-day is
 * ever read back out via the formatter.
 */
private fun formatTimeOfDay(context: android.content.Context, hour: Int, minute: Int): String {
    val calendar = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
    }
    return android.text.format.DateFormat.getTimeFormat(context).format(calendar.time)
}

/**
 * Formats [instant] for session history display as a local date/time. Converted through
 * `java.time` (rather than `kotlinx-datetime`, which is a `shared`-module dependency not exposed
 * to this Android-only app module) since `java.time` is available unconditionally on this
 * project's `minSdk 28`.
 */
private fun formatSessionDate(instant: Instant): String {
    val javaInstant = java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds())
    val zoned = javaInstant.atZone(java.time.ZoneId.systemDefault())
    return SESSION_DATE_FORMATTER.format(zoned)
}

private val SESSION_DATE_FORMATTER: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm")

private val DATE_ONLY_FORMATTER: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")

/**
 * Converts a [java.time.LocalDate] to the UTC-midnight epoch millis that Material 3's
 * `DatePickerState` represents dates as (a date picker selection is timezone-agnostic, always
 * anchored to midnight UTC, regardless of the device's local timezone).
 */
private fun localDateToUtcMidnightMillis(date: java.time.LocalDate): Long =
    date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

/** Formats a `DatePickerState.selectedDateMillis`-style UTC-midnight millis value for display. */
private fun formatUtcMidnightMillis(utcMidnightMillis: Long?): String {
    if (utcMidnightMillis == null) return "-"
    val localDate = java.time.Instant.ofEpochMilli(utcMidnightMillis)
        .atZone(java.time.ZoneOffset.UTC)
        .toLocalDate()
    return DATE_ONLY_FORMATTER.format(localDate)
}

/**
 * Derives a [kotlin.time.Instant] end-of-session timestamp from a `DatePickerState`-style
 * UTC-midnight date millis plus an hour/minute of day, combined in the device's local timezone
 * (same conversion approach as [formatSessionDate], just inverted). [hour]/[minute] are clamped
 * to valid ranges so out-of-range typed input (e.g. "99") can't throw.
 */
private fun deriveTimestampEnd(dateUtcMidnightMillis: Long, hour: Int, minute: Int): Instant {
    val localDate = java.time.Instant.ofEpochMilli(dateUtcMidnightMillis)
        .atZone(java.time.ZoneOffset.UTC)
        .toLocalDate()
    val zonedDateTime = localDate
        .atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        .atZone(java.time.ZoneId.systemDefault())
    return Instant.fromEpochMilliseconds(zonedDateTime.toInstant().toEpochMilli())
}

/** Keeps only digits and at most one decimal point, e.g. for [Double] position input fields. */
private fun String.filterDecimalInput(): String {
    val builder = StringBuilder()
    var seenDot = false
    for (char in this) {
        when {
            char.isDigit() -> builder.append(char)
            char == '.' && !seenDot -> {
                builder.append(char)
                seenDot = true
            }
        }
    }
    return builder.toString()
}

/** Keeps only digits, e.g. for [Int]/[Long] input fields (duration minutes, pages read). */
private fun String.filterIntegerInput(): String = filter { it.isDigit() }

private val PREVIEW_BOOK = MediaItemEntity(
    id = "book-1",
    type = MediaType.BOOK,
    title = "The Great Gatsby",
    releaseYear = 1925,
    purchasePrice = 9.99,
    createdAt = Instant.fromEpochMilliseconds(0),
    coverImageHash = null,
)

private val PREVIEW_DETAILS = BookDetailsEntity(
    mediaId = "book-1",
    isbn = "9780743273565",
    format = BookFormat.PHYSICAL,
    totalPages = 180,
)

private val PREVIEW_SESSIONS = listOf(
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

/** Preview of the book detail screen with an established session history. */
@Preview(showBackground = true)
@Composable
private fun BookDetailScreenReadyPreview() {
    MediaTrackerTheme {
        BookDetailScreen(
            uiState = BookDetailUiState.Ready(
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
        )
    }
}

/** Preview of the book detail screen with a finished timer run awaiting save. */
@Preview(showBackground = true)
@Composable
private fun BookDetailScreenPendingSessionPreview() {
    MediaTrackerTheme {
        BookDetailScreen(
            uiState = BookDetailUiState.Ready(
                book = PREVIEW_BOOK,
                details = PREVIEW_DETAILS,
                sessions = PREVIEW_SESSIONS,
                pendingSession = ReadingTimerResult(
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
        )
    }
}
