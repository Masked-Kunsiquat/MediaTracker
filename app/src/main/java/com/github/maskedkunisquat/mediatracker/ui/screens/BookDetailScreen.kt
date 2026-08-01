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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    LaunchedEffect(uiState) {
        if (uiState is BookDetailUiState.NotFound) {
            onNavigateBack()
        }
    }

    BookDetailScreen(
        uiState = uiState,
        timerState = timerState,
        elapsedSeconds = elapsedSeconds,
        coverStorageDir = coverStorageDir,
        onNavigateBack = onNavigateBack,
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
            val start = timestampEnd - durationMinutes.minutes
            viewModel.logManualSession(
                timestampStart = start,
                timestampEnd = timestampEnd,
                durationSeconds = durationMinutes * 60,
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
    onStartReading: () -> Unit,
    onPauseReading: () -> Unit,
    onResumeReading: () -> Unit,
    onStopReading: () -> Unit,
    onSaveSession: (startUnit: Double, endUnit: Double, deltaPages: Int?, notes: String?) -> Unit,
    onDiscardPendingSession: () -> Unit,
    onLogManualSession: (
        durationMinutes: Long,
        timestampEnd: Instant,
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int?,
        notes: String?,
    ) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
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
        durationMinutes: Long,
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
                Text("Log session manually")
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
            Text(text = "Session History", style = MaterialTheme.typography.titleMedium)
        }

        if (state.sessions.isEmpty()) {
            item {
                Text(
                    text = "No sessions logged yet",
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
            onSave = onSaveSession,
            onDiscard = onDiscardPendingSession,
        )
    }

    if (showManualEntry) {
        ManualSessionDialog(
            onSave = { durationMinutes, timestampEnd, startUnit, endUnit, deltaPages, notes ->
                onLogManualSession(durationMinutes, timestampEnd, startUnit, endUnit, deltaPages, notes)
                showManualEntry = false
            },
            onDismiss = { showManualEntry = false },
        )
    }

    if (sessionToDelete != null) {
        DeleteSessionConfirmationDialog(
            onConfirm = {
                onDeleteSession(sessionToDelete!!.id)
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
            if (book.releaseYear != null) {
                Text(
                    text = "Released: ${book.releaseYear}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (details?.isbn != null) {
                Text(text = "ISBN: ${details.isbn}", style = MaterialTheme.typography.bodySmall)
            }
            if (details != null) {
                Text(text = "Format: ${details.format}", style = MaterialTheme.typography.bodySmall)
            }
            if (details?.totalPages != null) {
                Text(
                    text = "Total pages: ${details.totalPages}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val progressText = formatProgress(currentProgress, details?.totalPages)
            if (progressText != null) {
                Text(
                    text = "Progress: $progressText",
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
                        Button(onClick = onStart) { Text("Start reading") }
                    }
                    is ReadingTimerState.Running -> {
                        Button(onClick = onPause) { Text("Pause") }
                        Button(onClick = onStop) { Text("Stop") }
                    }
                    is ReadingTimerState.Paused -> {
                        Button(onClick = onResume) { Text("Resume") }
                        Button(onClick = onStop) { Text("Stop") }
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
 */
@Composable
private fun PendingSessionDialog(
    pendingSession: ReadingTimerResult,
    errorMessage: String?,
    onSave: (startUnit: Double, endUnit: Double, deltaPages: Int?, notes: String?) -> Unit,
    onDiscard: () -> Unit,
) {
    var startUnitText by remember { mutableStateOf("") }
    var endUnitText by remember { mutableStateOf("") }
    var deltaPagesText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDiscard,
        title = { Text("Save reading session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Duration: ${formatElapsed(pendingSession.durationSeconds)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = startUnitText,
                    onValueChange = { startUnitText = it.filterDecimalInput() },
                    label = { Text("Start position") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = endUnitText,
                    onValueChange = { endUnitText = it.filterDecimalInput() },
                    label = { Text("End position") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = deltaPagesText,
                    onValueChange = { deltaPagesText = it.filterIntegerInput() },
                    label = { Text("Pages read (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text("Notes (optional)") },
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
                Text("Save")
            }
        },
        dismissButton = {
            Button(onClick = onDiscard) {
                Text("Discard")
            }
        },
    )
}

/**
 * Dialog for logging a session with no live timer involved: session date + end time, duration
 * (minutes), start/end position, optional pages-read and notes.
 *
 * The date field defaults to today and is edited via a [DatePickerDialog] (opened by tapping the
 * "Date:" button); the end time defaults to the current time and is edited via two small
 * digit-filtered hour/minute [OutlinedTextField]s rather than a full `TimePicker` dial --
 * this dialog already stacks five other fields (duration, two positions, pages, notes), and a
 * full `TimePicker` (dial or spinner) would push its total height well past what comfortably fits
 * above the keyboard on a phone; a compact "HH" / "MM" pair reuses the same digit-filtering
 * pattern already used elsewhere in this dialog and keeps everything on one screen (the field
 * column also scrolls, as a safety net on small displays). Because both date and time default to
 * "now," the zero-extra-tap "I just finished reading" path is unchanged -- picking a date/time is
 * purely opt-in, for backdating a session that happened in the past.
 *
 * [timestampEnd] passed to [onSave] is derived from the selected date + hour/minute in the
 * device's local timezone (same `java.time` conversion approach as [formatSessionDate]); the
 * caller derives `timestampStart` by subtracting the entered duration from it.
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
    onSave: (
        durationMinutes: Long,
        timestampEnd: Instant,
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int?,
        notes: String?,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var durationText by remember { mutableStateOf("") }
    var startUnitText by remember { mutableStateOf("") }
    var endUnitText by remember { mutableStateOf("") }
    var deltaPagesText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf("") }

    val nowForDefaults = remember { java.time.LocalDateTime.now() }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = remember { localDateToUtcMidnightMillis(nowForDefaults.toLocalDate()) },
    )
    var showDatePicker by remember { mutableStateOf(false) }
    var hourText by remember { mutableStateOf("%02d".format(nowForDefaults.hour)) }
    var minuteText by remember { mutableStateOf("%02d".format(nowForDefaults.minute)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log session manually") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Date: " + formatUtcMidnightMillis(datePickerState.selectedDateMillis))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = { hourText = it.filterIntegerInput().take(2) },
                        label = { Text("Hour (0-23)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = minuteText,
                        onValueChange = { minuteText = it.filterIntegerInput().take(2) },
                        label = { Text("Minute (0-59)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { durationText = it.filterIntegerInput() },
                    label = { Text("Duration (minutes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = startUnitText,
                    onValueChange = { startUnitText = it.filterDecimalInput() },
                    label = { Text("Start position") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = endUnitText,
                    onValueChange = { endUnitText = it.filterDecimalInput() },
                    label = { Text("End position") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = deltaPagesText,
                    onValueChange = { deltaPagesText = it.filterIntegerInput() },
                    label = { Text("Pages read (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        durationText.toLongOrNull() ?: 0L,
                        deriveTimestampEnd(
                            dateUtcMidnightMillis = datePickerState.selectedDateMillis
                                ?: localDateToUtcMidnightMillis(nowForDefaults.toLocalDate()),
                            hour = hourText.toIntOrNull() ?: 0,
                            minute = minuteText.toIntOrNull() ?: 0,
                        ),
                        startUnitText.toDoubleOrNull() ?: 0.0,
                        endUnitText.toDoubleOrNull() ?: 0.0,
                        deltaPagesText.toIntOrNull(),
                        notesText.ifBlank { null },
                    )
                },
                enabled = durationText.isNotBlank() &&
                    startUnitText.isNotBlank() &&
                    endUnitText.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

/**
 * A single row in the session history list: date, duration, start->end positions, optional
 * pages-read/notes, and a delete icon button.
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
                Text(
                    text = "Duration: ${formatElapsed(session.durationSeconds)}  •  " +
                        "${formatUnit(session.startUnit)} -> ${formatUnit(session.endUnit)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (session.deltaPages != null) {
                    Text(
                        text = "Pages read: ${session.deltaPages}",
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
        title = { Text("Delete session?") },
        text = { Text("Are you sure you want to delete this reading session? This cannot be undone.") },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Delete")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/** Formats a seconds count as `H:MM:SS` (hours unpadded, minutes/seconds zero-padded). */
private fun formatElapsed(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%d:%02d:%02d".format(hours, minutes, seconds)
}

/**
 * Formats [currentProgress] for display: page-style ("Page 142 / 350") when [totalPages] is
 * known, or percent-style ("37%") when [totalPages] is null -- e-reader/percentage tracking has
 * no fixed page count to show a denominator for. Returns null (nothing to show) when
 * [currentProgress] itself is null, i.e. no session has ever been logged.
 */
private fun formatProgress(currentProgress: Double?, totalPages: Int?): String? {
    if (currentProgress == null) return null
    return if (totalPages != null) {
        "Page ${currentProgress.roundToInt()} / $totalPages"
    } else {
        "${currentProgress.roundToInt()}%"
    }
}

/** Formats a [Double] position value, dropping a trailing `.0` for whole-number pages. */
private fun formatUnit(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

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
