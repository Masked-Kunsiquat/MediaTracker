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
import androidx.compose.material.icons.filled.Edit
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
 * @param onNavigateToEditBook Callback to navigate to the edit-metadata screen (ROADMAP Task 6
 *   Phase A), invoked from the TopAppBar edit icon (only shown for [BookDetailUiState.Ready]).
 */
@Composable
fun BookDetailScreenRoute(
    appContainer: AppContainer,
    coverStorageDir: String,
    bookId: String,
    onNavigateBack: () -> Unit,
    onNavigateToEditBook: () -> Unit,
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
        onEditSession = { sessionId, durationMinutes, timestampEnd, startUnit, endUnit, deltaPages, notes ->
            // Mirrors onLogManualSession's timestampStart derivation exactly (same optional-
            // duration semantics) -- see that lambda's KDoc above.
            val start = if (durationMinutes != null) timestampEnd - durationMinutes.minutes else timestampEnd
            viewModel.updateSession(
                sessionId = sessionId,
                timestampStart = start,
                timestampEnd = timestampEnd,
                durationSeconds = durationMinutes?.let { it * 60 },
                startUnit = startUnit,
                endUnit = endUnit,
                deltaPages = deltaPages,
                notes = notes,
            )
        },
        onEditBook = onNavigateToEditBook,
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
 * @param onEditSession Called with (sessionId, durationMinutes, timestampEnd, startUnit, endUnit,
 *   deltaPages, notes) from the manual-entry form when it was opened in edit mode (ROADMAP Task 6
 *   Phase B), i.e. via a session row's edit icon rather than the "Log session manually" button.
 *   Same argument shape/semantics as [onLogManualSession] -- see that parameter's doc.
 * @param onEditBook Called when the TopAppBar edit icon is tapped (only shown for
 *   [BookDetailUiState.Ready]), to navigate to the edit-metadata screen (ROADMAP Task 6 Phase A).
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
    onEditSession: (
        sessionId: String,
        durationMinutes: Long?,
        timestampEnd: Instant,
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int?,
        notes: String?,
    ) -> Unit,
    onEditBook: () -> Unit,
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
                        IconButton(onClick = onEditBook) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.edit_book_content_description),
                            )
                        }
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
                        onEditSession = onEditSession,
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
    onEditSession: (
        sessionId: String,
        durationMinutes: Long?,
        timestampEnd: Instant,
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int?,
        notes: String?,
    ) -> Unit,
) {
    var sessionToDelete by remember { mutableStateOf<ReadingSessionEntity?>(null) }
    var showManualEntry by remember { mutableStateOf(false) }
    // Non-null while the manual-entry dialog is open in *edit* mode (opened from a session row's
    // edit icon, prefilled from this row); null while it's open in *create* mode (opened from the
    // "Log session manually" button below). See ManualSessionDialog's KDoc.
    var sessionToEdit by remember { mutableStateOf<ReadingSessionEntity?>(null) }

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
            TextButton(onClick = { sessionToEdit = null; showManualEntry = true }) {
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
                    onEditClick = { sessionToEdit = session; showManualEntry = true },
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
            totalPages = state.details?.totalPages,
            onSave = onSaveSession,
            onDiscard = onDiscardPendingSession,
        )
    }

    if (showManualEntry) {
        ManualSessionDialog(
            currentProgress = state.currentProgress,
            totalPages = state.details?.totalPages,
            sessionToEdit = sessionToEdit,
            onSave = { durationMinutes, timestampEnd, startUnit, endUnit, deltaPages, notes ->
                val editing = sessionToEdit
                if (editing != null) {
                    onEditSession(editing.id, durationMinutes, timestampEnd, startUnit, endUnit, deltaPages, notes)
                } else {
                    onLogManualSession(durationMinutes, timestampEnd, startUnit, endUnit, deltaPages, notes)
                }
                showManualEntry = false
                sessionToEdit = null
            },
            onDismiss = { showManualEntry = false; sessionToEdit = null },
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
 * ### Position/pages validation (ROADMAP Task 6 Phase B)
 * Start/end position are digit-and-decimal-point filtered so a negative value (the one input
 * [LogReadingSessionUseCase][com.hub.media.features.books.domain.LogReadingSessionUseCase]
 * rejects) can never be typed in the first place, but that filtering alone does not stop a blank
 * field, a lone "." (unparseable), or a long-enough digit string overflowing [String.toDoubleOrNull]
 * to [Double.POSITIVE_INFINITY] (a finite-looking string can still parse to a non-finite value) --
 * all three used to silently collapse to `0.0` via `?: 0.0` at Save time instead of being
 * rejected. Each position field is now parsed once, above the fields (`parsedStartUnit`/
 * `parsedEndUnit`), and `startUnitIsValid`/`endUnitIsValid` require both "parses" and
 * [Double.isFinite]; a non-blank-but-invalid value shows `isError` with [supportingText] and
 * gates Save disabled, mirroring [ManualSessionDialog]'s duration-field pattern. A blank field is
 * still not flagged as an error (it's simply incomplete, not invalid input), consistent with the
 * pre-existing Save-disabled-while-blank behavior.
 *
 * ### Page vs. percent mode ([totalPages])
 * See [ManualSessionDialog]'s KDoc for the full rationale -- the same [totalPages]-based signal is
 * used here: when non-null (page-mode), pages read is derived as `endUnit - startUnit` and shown
 * read-only rather than asked for; when null (percent-mode), the manual pages-read field is shown,
 * now with the same parse-once + isError treatment as the position fields (blank stays legitimately
 * `null`; a non-blank unparseable value is rejected rather than silently discarded).
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
    totalPages: Int?,
    onSave: (startUnit: Double, endUnit: Double, deltaPages: Int?, notes: String?) -> Unit,
    onDiscard: () -> Unit,
) {
    var startUnitText by remember { mutableStateOf(currentProgress?.let(::formatUnit) ?: "") }
    var endUnitText by remember { mutableStateOf("") }
    var deltaPagesText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf("") }

    val isPageMode = totalPages != null

    val parsedStartUnit = startUnitText.toDoubleOrNull()
    val startUnitIsValid = parsedStartUnit != null && parsedStartUnit.isFinite()
    val startUnitShowsError = startUnitText.isNotBlank() && !startUnitIsValid

    val parsedEndUnit = endUnitText.toDoubleOrNull()
    val endUnitIsValid = parsedEndUnit != null && parsedEndUnit.isFinite()
    val endUnitShowsError = endUnitText.isNotBlank() && !endUnitIsValid

    // Page-mode: deltaPages is fully determined by the positions the user already entered, so it
    // needs no separate manual input -- see ManualSessionDialog's KDoc.
    val derivedDeltaPages = if (isPageMode && startUnitIsValid && endUnitIsValid) {
        (parsedEndUnit!! - parsedStartUnit!!).roundToInt()
    } else {
        null
    }

    val parsedDeltaPages = deltaPagesText.toIntOrNull()
    val deltaPagesIsValid = deltaPagesText.isBlank() || parsedDeltaPages != null
    val deltaPagesShowsError = !isPageMode && deltaPagesText.isNotBlank() && !deltaPagesIsValid

    val canSave = startUnitText.isNotBlank() && startUnitIsValid &&
        endUnitText.isNotBlank() && endUnitIsValid &&
        (isPageMode || deltaPagesIsValid)

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
                Text(
                    text = stringResource(R.string.manual_entry_section_progress),
                    style = MaterialTheme.typography.labelMedium,
                )
                OutlinedTextField(
                    value = startUnitText,
                    onValueChange = { startUnitText = it.filterDecimalInput() },
                    label = { Text(stringResource(R.string.start_position_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = startUnitShowsError,
                    supportingText = if (startUnitShowsError) {
                        { Text(stringResource(R.string.position_invalid_error)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = endUnitText,
                    onValueChange = { endUnitText = it.filterDecimalInput() },
                    label = { Text(stringResource(R.string.end_position_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = endUnitShowsError,
                    supportingText = if (endUnitShowsError) {
                        { Text(stringResource(R.string.position_invalid_error)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isPageMode) {
                    Text(
                        text = stringResource(
                            R.string.pages_read_derived_label,
                            derivedDeltaPages?.toString() ?: stringResource(R.string.pages_read_derived_placeholder),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    OutlinedTextField(
                        value = deltaPagesText,
                        onValueChange = { deltaPagesText = it.filterIntegerInput() },
                        label = { Text(stringResource(R.string.pages_read_optional_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = deltaPagesShowsError,
                        supportingText = if (deltaPagesShowsError) {
                            { Text(stringResource(R.string.pages_read_invalid_error)) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
                        parsedStartUnit ?: 0.0,
                        parsedEndUnit ?: 0.0,
                        if (isPageMode) derivedDeltaPages else parsedDeltaPages,
                        notesText.ifBlank { null },
                    )
                },
                enabled = canSave,
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
 * Dialog for logging -- or, since ROADMAP Task 6 Phase B, editing -- a session with no live timer
 * involved: session date + end time, duration (minutes), start/end position, pages-read (page-mode:
 * derived; percent-mode: manual/optional), and notes. Fields are grouped into "When" (date/time),
 * "How long" (duration), and "Progress" (positions + pages) sections (Task 6 Phase B layout
 * cleanup) inside the existing scrollable [Column] -- this stays a dialog, not a full screen, per
 * ROADMAP scope.
 *
 * ### Create vs. edit ([sessionToEdit])
 * When [sessionToEdit] is `null` (opened from the "Log session manually" button), every field
 * starts blank/defaulted exactly as before ("now" for date/time, [currentProgress] for the start
 * position). When non-null (opened from a session row's edit icon, ROADMAP Task 6 Phase B), every
 * field is prefilled from that row instead: date/time from [ReadingSessionEntity.timestampEnd],
 * duration from [ReadingSessionEntity.durationSeconds] rounded to the nearest minute (this dialog's
 * duration field only has minute granularity -- a manually-created session's duration is always
 * already an exact multiple of 60 seconds so this round-trips losslessly, but editing a
 * timer-backed session with sub-minute precision will coarsen its stored duration to the nearest
 * minute once saved; a dedicated per-second edit UI was judged not worth it for what's expected to
 * be a rare edit of already-precise timer data), positions from `startUnit`/`endUnit`, and notes
 * verbatim. The dialog's title and the semantics of Save are the only other difference -- the
 * caller ([BookDetailContent]) decides whether [onSave]'s payload means "create" or "update
 * `sessionToEdit.id`", this composable itself is agnostic to which.
 *
 * The date field defaults to today (or, editing, the row's date) and is edited via a
 * [DatePickerDialog] (opened by tapping the "Date:" button). The end time defaults to the current
 * time (or, editing, the row's time) and is edited via a Material 3 [TimePicker] (Task4 Phase E;
 * replaces the earlier digit-filtered hour/minute text fields), hosted in a custom [AlertDialog]
 * with OK/Cancel buttons since this Material 3 version has no built-in `TimePickerDialog` wrapper
 * analogous to [DatePickerDialog] -- Cancel restores the previously-selected time, mirroring the
 * date picker's cancel-restore pattern below. Because both date and time default to "now" in
 * create mode, the zero-extra-tap "I just finished reading" path is unchanged -- picking a
 * date/time is purely opt-in, for backdating a session that happened in the past.
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
 * progress as a "resume where you left off" convenience in create mode -- the user can freely edit
 * or clear it. Ignored in edit mode ([sessionToEdit] wins).
 *
 * ### Page vs. percent mode ([totalPages])
 * `deltaPages` is redundant to ask for whenever positions are page numbers -- it's fully
 * determined by `endUnit - startUnit`, which the user is already entering (ROADMAP Task 6 Phase
 * B). This project's data model has no explicit "tracking mode" flag (schema stays frozen at v2
 * for this phase; a first-class reading-mode concept is Task 6 Phase C's job), so a signal has to
 * be chosen rather than invented as new schema: [totalPages] (from
 * [com.hub.media.core.database.entities.BookDetailsEntity.totalPages]) being non-null is reused
 * here as that signal, because it is **already** this exact signal elsewhere on this same screen --
 * [formatProgress] renders "Page 142 / 350" when `totalPages != null` and a bare percentage
 * otherwise. Reusing it keeps exactly one source of truth for "is this book tracked by page or by
 * percent" across the whole screen, rather than a second, parallel notion (a per-dialog toggle, or
 * inferring from position magnitude -- which would misfire for, say, a 100-page book at 100%
 * complete). In page mode (`totalPages != null`) the pages-read field is replaced by a read-only
 * derived-value [Text] ("Pages read (auto): N") computed from the position fields, making which
 * mode is active visually obvious; in percent mode (`totalPages == null`, no fixed denominator to
 * derive a page count from) the manual field is shown exactly as before, just with the same
 * parse-once + isError validation now applied to every other numeric field (see below).
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
 * ### Numeric field validation (ROADMAP Task 6 Phase B)
 * Every numeric field here -- duration, start/end position, and pages-read (percent-mode only) --
 * is parsed exactly once, above the fields, rather than inline at Save time, following the same
 * pattern: a `parsedX` value (`null` for both "blank" and "unparseable/overflowed"), an `xIsValid`
 * flag that disambiguates those two causes (blank is legitimately valid for an *optional* field --
 * duration, pages-read; a *required* field -- start/end position -- is simply incomplete while
 * blank, not erroneous, so blank alone does not show `isError`), and Save is gated on every field's
 * validity so an invalid value can never reach [onSave]:
 * - `durationText` is digit-filtered ([filterIntegerInput]) so it can never contain a sign or
 *   decimal point, but a long-enough digit string still overflows [String.toLongOrNull] (returns
 *   `null`) the same way a genuinely blank field does -- left unguarded, a mistyped 25-digit
 *   duration would silently save as "unknown" instead of being rejected. `durationIsValid` also
 *   bounds a parseable value to [MAX_MANUAL_DURATION_MINUTES], which protects the caller's
 *   `durationMinutes * 60` seconds conversion and `timestampEnd - durationMinutes.minutes`
 *   arithmetic in [BookDetailRoute] -- both saturate rather than throw on overflow, so an
 *   unreasonably large-but-parseable value would otherwise persist a nonsense `durationSeconds`
 *   rather than fail loudly.
 * - `startUnitText`/`endUnitText` are digit-and-decimal-point filtered ([filterDecimalInput]) so a
 *   negative value (the one input
 *   [LogReadingSessionUseCase][com.hub.media.features.books.domain.LogReadingSessionUseCase]
 *   rejects) can never be typed, but that alone doesn't stop a lone "." (unparseable) or a
 *   long-enough digit string overflowing [String.toDoubleOrNull] to [Double.POSITIVE_INFINITY] --
 *   both used to silently collapse to `0.0` via `?: 0.0` at Save time. `startUnitIsValid`/
 *   `endUnitIsValid` require both "parses" and [Double.isFinite].
 * - `deltaPagesText` (percent-mode only) is digit-filtered ([filterIntegerInput]), so the same
 *   overflow concern as duration applies to [String.toIntOrNull] -- `deltaPagesIsValid` catches it;
 *   blank stays legitimately `null`.
 *
 * Unlike [PendingSessionDialog], this dialog closes optimistically as soon as Save is tapped
 * rather than waiting on a success/failure signal. There is no shared-state flag analogous to
 * `pendingSession` for the manual path that this dialog could stay bound to; a rejected save (now
 * possible not just from the repository/use-case layer but, in edit mode, also from
 * [BookDetailViewModel.updateSession] targeting a since-deleted session) is surfaced via
 * `state.errorMessage` as a banner in [BookDetailContent] once this dialog has closed, exactly as
 * a rejected create already was.
 */
/**
 * Practical upper bound for [ManualSessionDialog]'s duration field, in minutes: 10 years
 * (`10 * 365 * 24 * 60`). A manually-backlogged single reading session in the tens of years is
 * never legitimate, so this exists to catch fat-fingered/overflowing input (see the duration
 * field's KDoc on [ManualSessionDialog]) -- it is not derived from `Long.MAX_VALUE / 60` (the
 * true limit before `durationMinutes * 60` overflows), which is astronomically larger and would
 * let obviously-bogus values like a 12-digit minute count through unrejected.
 */
private const val MAX_MANUAL_DURATION_MINUTES = 10L * 365 * 24 * 60 // 5,256,000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualSessionDialog(
    currentProgress: Double?,
    totalPages: Int?,
    sessionToEdit: ReadingSessionEntity?,
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
    val isPageMode = totalPages != null

    var durationText by remember {
        mutableStateOf(
            sessionToEdit?.durationSeconds?.let { seconds ->
                kotlin.math.round(seconds / 60.0).toLong().toString()
            } ?: "",
        )
    }
    var startUnitText by remember {
        mutableStateOf(
            sessionToEdit?.startUnit?.let(::formatUnit)
                ?: currentProgress?.let(::formatUnit)
                ?: "",
        )
    }
    var endUnitText by remember { mutableStateOf(sessionToEdit?.endUnit?.let(::formatUnit) ?: "") }
    var deltaPagesText by remember {
        mutableStateOf(sessionToEdit?.deltaPages?.takeIf { !isPageMode }?.toString() ?: "")
    }
    var notesText by remember { mutableStateOf(sessionToEdit?.notes ?: "") }

    // Parsed once, above the fields, rather than inline at Save time -- see the "Numeric field
    // validation" section of this function's KDoc. `parsedDurationMinutes` is the raw parse
    // (`null` for both "blank" and "unparseable/overflowed"); `durationIsValid` disambiguates
    // those two `null` causes; `validatedDurationMinutes` is the only value that ever reaches
    // [onSave], and only when `durationIsValid` (which also gates the Save button below).
    val parsedDurationMinutes = durationText.toLongOrNull()
    val durationIsValid = durationText.isBlank() ||
        (parsedDurationMinutes != null && parsedDurationMinutes <= MAX_MANUAL_DURATION_MINUTES)
    val validatedDurationMinutes = if (durationText.isBlank()) null else parsedDurationMinutes

    val parsedStartUnit = startUnitText.toDoubleOrNull()
    val startUnitIsValid = parsedStartUnit != null && parsedStartUnit.isFinite()
    val startUnitShowsError = startUnitText.isNotBlank() && !startUnitIsValid

    val parsedEndUnit = endUnitText.toDoubleOrNull()
    val endUnitIsValid = parsedEndUnit != null && parsedEndUnit.isFinite()
    val endUnitShowsError = endUnitText.isNotBlank() && !endUnitIsValid

    // Page-mode: deltaPages is fully determined by the positions already entered -- see the "Page
    // vs. percent mode" section of this function's KDoc.
    val derivedDeltaPages = if (isPageMode && startUnitIsValid && endUnitIsValid) {
        (parsedEndUnit!! - parsedStartUnit!!).roundToInt()
    } else {
        null
    }

    val parsedDeltaPages = deltaPagesText.toIntOrNull()
    val deltaPagesIsValid = deltaPagesText.isBlank() || parsedDeltaPages != null
    val deltaPagesShowsError = !isPageMode && deltaPagesText.isNotBlank() && !deltaPagesIsValid

    val context = LocalContext.current
    // "Defaults" for create mode (today/now); in edit mode these seed the pickers with the
    // session's own date/time instead -- see the "Create vs. edit" section of this function's
    // KDoc. Keyed on sessionToEdit so switching which row is being edited (were this composable
    // ever reused across rows without being torn down) would re-seed correctly; in practice each
    // dialog invocation is backed by a fresh `if (showManualEntry)` composition in
    // [BookDetailContent], so this is a defensive `remember` key rather than a load-bearing one.
    val initialDateTime = remember(sessionToEdit) {
        sessionToEdit?.timestampEnd?.let(::instantToLocalDateTime) ?: java.time.LocalDateTime.now()
    }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = remember { localDateToUtcMidnightMillis(initialDateTime.toLocalDate()) },
    )
    var showDatePicker by remember { mutableStateOf(false) }
    // Snapshot of datePickerState.selectedDateMillis taken when the picker is opened, so a
    // picked-then-Cancelled date can be reverted rather than sticking (OK leaves the in-dialog
    // selection unchanged; only Cancel restores this).
    var dateBeforePickerOpen by remember { mutableStateOf<Long?>(null) }

    val is24Hour = remember { android.text.format.DateFormat.is24HourFormat(context) }
    val timePickerState = rememberTimePickerState(
        initialHour = initialDateTime.hour,
        initialMinute = initialDateTime.minute,
        is24Hour = is24Hour,
    )
    var showTimePicker by remember { mutableStateOf(false) }
    // Snapshot of the selected hour/minute taken when the picker is opened, so a
    // picked-then-Cancelled time can be reverted rather than sticking (OK leaves the in-dialog
    // selection unchanged; only Cancel restores this) -- mirrors dateBeforePickerOpen above.
    var timeBeforePickerOpen by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val canSave = startUnitText.isNotBlank() && startUnitIsValid &&
        endUnitText.isNotBlank() && endUnitIsValid &&
        durationIsValid &&
        (isPageMode || deltaPagesIsValid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (sessionToEdit != null) R.string.edit_session_title else R.string.log_session_manually,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.manual_entry_section_when),
                    style = MaterialTheme.typography.labelMedium,
                )
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

                Text(
                    text = stringResource(R.string.manual_entry_section_how_long),
                    style = MaterialTheme.typography.labelMedium,
                )
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { durationText = it.filterIntegerInput() },
                    label = { Text(stringResource(R.string.duration_minutes_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = !durationIsValid,
                    supportingText = if (!durationIsValid) {
                        { Text(stringResource(R.string.duration_minutes_invalid, MAX_MANUAL_DURATION_MINUTES)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.manual_entry_section_progress),
                    style = MaterialTheme.typography.labelMedium,
                )
                OutlinedTextField(
                    value = startUnitText,
                    onValueChange = { startUnitText = it.filterDecimalInput() },
                    label = { Text(stringResource(R.string.start_position_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = startUnitShowsError,
                    supportingText = if (startUnitShowsError) {
                        { Text(stringResource(R.string.position_invalid_error)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = endUnitText,
                    onValueChange = { endUnitText = it.filterDecimalInput() },
                    label = { Text(stringResource(R.string.end_position_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = endUnitShowsError,
                    supportingText = if (endUnitShowsError) {
                        { Text(stringResource(R.string.position_invalid_error)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isPageMode) {
                    Text(
                        text = stringResource(
                            R.string.pages_read_derived_label,
                            derivedDeltaPages?.toString() ?: stringResource(R.string.pages_read_derived_placeholder),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    OutlinedTextField(
                        value = deltaPagesText,
                        onValueChange = { deltaPagesText = it.filterIntegerInput() },
                        label = { Text(stringResource(R.string.pages_read_optional_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = deltaPagesShowsError,
                        supportingText = if (deltaPagesShowsError) {
                            { Text(stringResource(R.string.pages_read_invalid_error)) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

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
                        // "Duration is optional" section. validatedDurationMinutes is hoisted
                        // above the fields and gated by durationIsValid (below), so an
                        // unparseable/overflowing non-blank value can never reach here -- see the
                        // "Numeric field validation" section of this function's KDoc.
                        validatedDurationMinutes,
                        deriveTimestampEnd(
                            dateUtcMidnightMillis = datePickerState.selectedDateMillis
                                ?: localDateToUtcMidnightMillis(initialDateTime.toLocalDate()),
                            hour = timePickerState.hour,
                            minute = timePickerState.minute,
                        ),
                        parsedStartUnit ?: 0.0,
                        parsedEndUnit ?: 0.0,
                        if (isPageMode) derivedDeltaPages else parsedDeltaPages,
                        notesText.ifBlank { null },
                    )
                },
                enabled = canSave,
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
                        val (hour, minute) = timeBeforePickerOpen ?: (initialDateTime.hour to initialDateTime.minute)
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
 * optional pages-read/notes, and edit/delete icon buttons.
 *
 * ### Edit affordance (ROADMAP Task 6 Phase B)
 * An explicit edit [IconButton] (rather than making the whole row tappable) was chosen because the
 * row already carries a delete [IconButton] for its other destructive/mutating action -- adding a
 * second icon button keeps both actions equally explicit and discoverable, exactly mirroring the
 * TopAppBar's existing Edit-then-Delete icon pair for the book itself (ROADMAP Task 6 Phase A).
 * Making the row itself tappable-to-edit instead would conflate "tap this row" with "start editing
 * it," foreclosing any future non-edit tap behavior (e.g. expanding a row's notes) without a
 * strong reason to prefer that ambiguity over two clearly-labeled icons.
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
    onEditClick: () -> Unit,
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
            IconButton(onClick = onEditClick) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.edit_session_content_description),
                )
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

/**
 * Formats a [Double] position value, dropping a trailing `.0` for whole-number pages. Also reused
 * by `EditBookScreen` (same package, ROADMAP Task 6 Phase A) to prefill the purchase-price field.
 */
internal fun formatUnit(value: Double): String =
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

/**
 * Converts [instant] to a local [java.time.LocalDateTime] (device timezone), for seeding
 * [ManualSessionDialog]'s date/time pickers when it's opened in edit mode -- same conversion
 * approach as [formatSessionDate], just kept as a `LocalDateTime` rather than formatted to a
 * string.
 */
private fun instantToLocalDateTime(instant: Instant): java.time.LocalDateTime =
    java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds())
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDateTime()

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

/**
 * Keeps only digits and at most one decimal point, e.g. for [Double] position input fields. Also
 * reused by `EditBookScreen` (same package, ROADMAP Task 6 Phase A) for its purchase-price field.
 */
internal fun String.filterDecimalInput(): String {
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

/**
 * Keeps only digits, e.g. for [Int]/[Long] input fields (duration minutes, pages read). Also
 * reused by `EditBookScreen` (same package, ROADMAP Task 6 Phase A) for its release-year and
 * total-pages fields.
 */
internal fun String.filterIntegerInput(): String = filter { it.isDigit() }

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
            onEditSession = { _, _, _, _, _, _, _ -> },
            onEditBook = {},
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
            onEditSession = { _, _, _, _, _, _, _ -> },
            onEditBook = {},
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
        )
    }
}
