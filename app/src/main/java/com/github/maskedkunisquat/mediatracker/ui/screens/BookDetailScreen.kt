@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.github.maskedkunisquat.mediatracker.ui.screens

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.BookDetailViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.insets.exceptBottom
import com.hub.media.core.database.entities.ReadingSessionEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.features.books.timer.ReadingTimerState
import com.hub.media.ui.AppContainer
import com.hub.media.ui.BookDetailUiState
import com.hub.media.ui.BookDetailViewModel
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
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
    val viewModel: BookDetailViewModel =
        viewModel(
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
        onLogManualSession = { durationSeconds, timestampEnd, startUnit, endUnit, deltaPages, notes ->
            // Manual entry has no live timer to read timestamps from, so the dialog itself
            // collects a session date + end time (defaulting to today/now, so the common "I just
            // finished reading" case still takes zero extra taps) and derives timestampEnd from
            // that selection -- see ManualSessionDialog. timestampStart is then simply the
            // effective duration subtracted from timestampEnd, which supports backdating an entry
            // to an arbitrary past date/time as well as the zero-extra-tap "just now" case.
            //
            // durationSeconds is already the fully-resolved, correct-precision value by the time
            // it reaches here -- ManualSessionDialog itself does the minutes->seconds conversion
            // (its duration field only has minute granularity), because only that composable knows
            // whether the field was actually touched by the user (see its KDoc's "Duration
            // precision" section and AGENTS.md §1). This lambda must NOT redo/reinterpret that
            // conversion -- it has no way to tell an untouched prefill from a genuine edit.
            //
            // durationSeconds is null when the duration field was left blank (schema v2, ROADMAP
            // Task 5 pre-phase -- backlogged manual sessions don't always have a known duration).
            // With no duration to subtract, timestampStart is set equal to timestampEnd: a
            // zero-length interval that anchors the session to its date without asserting a false
            // span -- see ManualSessionDialog's KDoc.
            val start = if (durationSeconds != null) timestampEnd - durationSeconds.seconds else timestampEnd
            viewModel.logManualSession(
                timestampStart = start,
                timestampEnd = timestampEnd,
                durationSeconds = durationSeconds,
                startUnit = startUnit,
                endUnit = endUnit,
                deltaPages = deltaPages,
                notes = notes,
            )
        },
        onDeleteSession = viewModel::deleteSession,
        onEditSession = { sessionId, durationSeconds, timestampEnd, startUnit, endUnit, deltaPages, notes ->
            // Mirrors onLogManualSession's timestampStart derivation exactly (same optional-
            // duration semantics, and the same "durationSeconds already resolved by the dialog"
            // caveat) -- see that lambda's KDoc above.
            val start = if (durationSeconds != null) timestampEnd - durationSeconds.seconds else timestampEnd
            viewModel.updateSession(
                sessionId = sessionId,
                timestampStart = start,
                timestampEnd = timestampEnd,
                durationSeconds = durationSeconds,
                startUnit = startUnit,
                endUnit = endUnit,
                deltaPages = deltaPages,
                notes = notes,
            )
        },
        onEditBook = onNavigateToEditBook,
        onStatusChange = viewModel::updateStatus,
        onRefetchCover = viewModel::refetchCover,
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
 * @param onLogManualSession Called with (durationSeconds, timestampEnd, startUnit, endUnit,
 *   deltaPages, notes) from the manual-entry form; timestampEnd reflects the session date/time
 *   the user picked in the dialog (defaulting to now, but backdatable to a past date/time).
 *   durationSeconds is `null` when the duration field was left blank (schema v2, ROADMAP Task 5
 *   pre-phase) -- duration is optional for manual entries. Already resolved to seconds by
 *   [ManualSessionDialog] itself (its minutes-granularity field is converted there, not here) --
 *   see that composable's KDoc.
 * @param onDeleteSession Called with a session id after its delete is confirmed.
 * @param onEditSession Called with (sessionId, durationSeconds, timestampEnd, startUnit, endUnit,
 *   deltaPages, notes) from the manual-entry form when it was opened in edit mode (ROADMAP Task 6
 *   Phase B), i.e. via a session row's edit icon rather than the "Log session manually" button.
 *   Same argument shape/semantics as [onLogManualSession] -- see that parameter's doc.
 * @param onEditBook Called when the TopAppBar edit icon is tapped (only shown for
 *   [BookDetailUiState.Ready]), to navigate to the edit-metadata screen (ROADMAP Task 6 Phase A).
 * @param onStatusChange Called with the newly selected [ReadingStatus] from the header's quick
 *   status chip/dropdown (ROADMAP Task 6 Phase C), wired to [BookDetailViewModel.updateStatus].
 * @param onRefetchCover Called when the "Re-fetch cover" item is selected from the Details tab
 *   cover's long-press menu (ROADMAP Task 6 Phase E; moved off a standalone button and onto the
 *   cover's own interactions in the books-polish pass -- see [InteractiveCoverBox]), wired to
 *   [BookDetailViewModel.refetchCover].
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
        durationSeconds: Long?,
        timestampEnd: Instant,
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int?,
        notes: String?,
    ) -> Unit,
    onDeleteSession: (String) -> Unit,
    onEditSession: (
        sessionId: String,
        durationSeconds: Long?,
        timestampEnd: Instant,
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int?,
        notes: String?,
    ) -> Unit,
    onEditBook: () -> Unit,
    onStatusChange: (ReadingStatus) -> Unit,
    onRefetchCover: () -> Unit,
) {
    var showDeleteBookDialog by remember { mutableStateOf(false) }

    // ISBN tap-to-copy (ROADMAP Task 6 Phase E backlog item): a pure UI-local side effect (no
    // ViewModel/business state involved), so it's implemented entirely here rather than hoisted
    // as a callback param, per AGENTS.md §5's state-hoisting principle being about screen/business
    // state, not ephemeral platform actions like clipboard writes. Uses LocalClipboard (the
    // current, non-deprecated Compose clipboard API in this project's resolved Compose BOM --
    // LocalClipboardManager is deprecated in favor of it) rather than LocalClipboardManager.
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val isbnCopiedMessage = stringResource(R.string.isbn_copied_message)
    val onCopyIsbn: (String) -> Unit = { isbn ->
        coroutineScope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("ISBN", isbn)))
            // Android 13+ (API 33/TIRAMISU) shows its own system "copied to clipboard"
            // confirmation UI, so an in-app confirmation there would double up; only show ours
            // below that API level (minSdk is 28, so both paths are reachable in practice).
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                snackbarHostState.showSnackbar(isbnCopiedMessage)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            // Library's shape: the tab row below is pinned and takes the top app bar and the
            // horizontal cutout as real padding, while the navigation bar goes to whichever tab is
            // showing -- both of them scroll, and both re-add it themselves.
            modifier =
                Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .padding(innerPadding.exceptBottom()),
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
                        onStatusChange = onStatusChange,
                        onCopyIsbn = onCopyIsbn,
                        onRefetchCover = onRefetchCover,
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
 * Content for [BookDetailUiState.Ready]: a [PrimaryTabRow] splitting the screen into a Details tab
 * (cover/metadata header, reading status, progress, and -- as of the books-polish pass below --
 * the live reading timer -- see [DetailsTab]) and a Reading history tab (manual-entry affordance,
 * session history -- see [ReadingHistoryTab]), per ROADMAP Task 6 Phase D. **The timer moved from
 * Reading history to Details in the books-polish pass**: users found it counter-intuitive that
 * starting/stopping a reading session lived under a history tab rather than alongside the book
 * itself; the manual-entry affordance and session list stay on Reading history unchanged. A
 * Purchase & Borrow tab is deliberately NOT included -- that data model doesn't exist yet (see
 * ROADMAP's Task 6 Phase D note; tracked in the backlog).
 *
 * ### State survives the tab split
 * [sessionToDelete]/[showManualEntry]/[sessionToEdit] (all pre-existing) and [selectedTabIndex]
 * (new) are all hoisted to this function, one level above the tab content -- switching tabs never
 * tears down or recreates them, so a dialog opened from either tab (Reading history's manual-entry/
 * delete dialogs, or Details' pending-timer-session dialog now that the timer lives there) keeps
 * working exactly as before regardless of which tab happens to be selected when it renders; the
 * dialogs themselves ([PendingSessionDialog]/[ManualSessionDialog]/[DeleteSessionConfirmationDialog])
 * are rendered unconditionally on this same state below, outside the `when (selectedTabIndex)`
 * branch, so they overlay whichever tab is showing rather than only the one that opened them.
 * [state.errorMessage] is likewise rendered here, above the tab content, rather than inside either
 * tab -- it now surfaces failures from session mutations, book deletion, status changes, *and*
 * [onRefetchCover] (ROADMAP Task 6 Phase E, now triggered from the cover's long-press menu rather
 * than a standalone button -- see [BookHeader]), so pinning its display to one specific tab would
 * hide it whenever that failure happened to originate from an action on the other tab.
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
        durationSeconds: Long?,
        timestampEnd: Instant,
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int?,
        notes: String?,
    ) -> Unit,
    onDeleteSession: (String) -> Unit,
    onEditSession: (
        sessionId: String,
        durationSeconds: Long?,
        timestampEnd: Instant,
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int?,
        notes: String?,
    ) -> Unit,
    onStatusChange: (ReadingStatus) -> Unit,
    onCopyIsbn: (String) -> Unit,
    onRefetchCover: () -> Unit,
) {
    var sessionToDelete by remember { mutableStateOf<ReadingSessionEntity?>(null) }
    var showManualEntry by remember { mutableStateOf(false) }
    // Non-null while the manual-entry dialog is open in *edit* mode (opened from a session row's
    // edit icon, prefilled from this row); null while it's open in *create* mode (opened from
    // ReadingHistoryTab's "Log session manually" button). See ManualSessionDialog's KDoc.
    var sessionToEdit by remember { mutableStateOf<ReadingSessionEntity?>(null) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
            Tab(
                selected = selectedTabIndex == 0,
                onClick = { selectedTabIndex = 0 },
                text = { Text(stringResource(R.string.tab_details)) },
            )
            Tab(
                selected = selectedTabIndex == 1,
                onClick = { selectedTabIndex = 1 },
                text = { Text(stringResource(R.string.tab_reading_history)) },
            )
        }

        // The pending-session dialog already surfaces state.errorMessage while a timer-backed
        // session is awaiting save (see below). Every other mutation that can fail (manual-entry
        // save/edit, session delete, book delete, status change, cover refetch) has no dialog left
        // open to show it in by the time it fails, so show it here instead, scoped to the case
        // where it isn't already visible elsewhere -- see this function's KDoc for why this lives
        // above the tab content rather than inside one specific tab.
        val errorMessage = state.errorMessage
        if (errorMessage != null && state.pendingSession == null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        when (selectedTabIndex) {
            0 ->
                DetailsTab(
                    book = state.book,
                    details = state.details,
                    currentProgress = state.currentProgress,
                    coverStorageDir = coverStorageDir,
                    isRefetchingCover = state.isRefetchingCover,
                    timerState = timerState,
                    elapsedSeconds = elapsedSeconds,
                    onStartReading = onStartReading,
                    onPauseReading = onPauseReading,
                    onResumeReading = onResumeReading,
                    onStopReading = onStopReading,
                    onStatusChange = onStatusChange,
                    onCopyIsbn = onCopyIsbn,
                    onRefetchCover = onRefetchCover,
                    modifier = Modifier.weight(1f),
                )
            1 ->
                ReadingHistoryTab(
                    sessions = state.sessions,
                    onLogManuallyClick = {
                        sessionToEdit = null
                        showManualEntry = true
                    },
                    onEditSessionClick = { session ->
                        sessionToEdit = session
                        showManualEntry = true
                    },
                    onDeleteSessionClick = { session -> sessionToDelete = session },
                    modifier = Modifier.weight(1f),
                )
        }
    }

    val pendingSession = state.pendingSession
    if (pendingSession != null) {
        PendingSessionDialog(
            pendingSession = pendingSession,
            errorMessage = state.errorMessage,
            currentProgress = state.currentProgress,
            trackingMode = state.details?.trackingMode,
            onSave = onSaveSession,
            onDiscard = onDiscardPendingSession,
        )
    }

    if (showManualEntry) {
        ManualSessionDialog(
            currentProgress = state.currentProgress,
            trackingMode = state.details?.trackingMode,
            sessionToEdit = sessionToEdit,
            onSave = { durationSeconds, timestampEnd, startUnit, endUnit, deltaPages, notes ->
                val editing = sessionToEdit
                if (editing != null) {
                    onEditSession(editing.id, durationSeconds, timestampEnd, startUnit, endUnit, deltaPages, notes)
                } else {
                    onLogManualSession(durationSeconds, timestampEnd, startUnit, endUnit, deltaPages, notes)
                }
                showManualEntry = false
                sessionToEdit = null
            },
            onDismiss = {
                showManualEntry = false
                sessionToEdit = null
            },
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
