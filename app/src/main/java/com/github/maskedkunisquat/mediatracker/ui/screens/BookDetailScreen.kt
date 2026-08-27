@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.github.maskedkunisquat.mediatracker.ui.screens

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.BookDetailViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.components.BOOK_COVER_ASPECT_RATIO
import com.github.maskedkunisquat.mediatracker.ui.components.CoverImage
import com.github.maskedkunisquat.mediatracker.ui.insets.barPadding
import com.github.maskedkunisquat.mediatracker.ui.insets.exceptBottom
import com.github.maskedkunisquat.mediatracker.ui.insets.plus
import com.github.maskedkunisquat.mediatracker.ui.text.filterDecimalInput
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingSessionEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.features.books.timer.ReadingTimerResult
import com.hub.media.features.books.timer.ReadingTimerState
import com.hub.media.ui.AppContainer
import com.hub.media.ui.BookDetailUiState
import com.hub.media.ui.BookDetailViewModel
import com.hub.media.ui.filterIntegerInput
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
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
    // edit icon, prefilled from this row); null while it's open in *create* mode (opened from the
    // "Log session manually" button below). See ManualSessionDialog's KDoc.
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

/**
 * Details tab content (books-polish pass revamp; originally ROADMAP Task 6 Phase D). Replaces the
 * former single stack of prefix-string [Text] rows ("Released: …", "ISBN: …", "Format: …") with a
 * considered hierarchy, top to bottom:
 * 1. [BookHeader] -- cover, title/release-year heading block, and the reading-status chip.
 * 2. [ProgressSection] -- current reading progress, the thing this screen is checked for most
 *    (per the ROADMAP revamp brief), promoted above the timer and given its own prominent card with
 *    a [LinearProgressIndicator] wherever a fraction is derivable.
 * 3. [TimerCard] -- restyled with a `primaryContainer` background and full-width buttons so it
 *    reads as *the* primary action on this tab, not another stacked card of equal visual weight.
 * 4. [MetadataCard] -- ISBN/format/total-pages/tracking-mode as a compact two-column key/value
 *    grid, replacing the old `released_prefix`/`isbn_prefix`/`format_prefix`/`total_pages_prefix`/
 *    `progress_prefix` strings (all deleted -- see `strings.xml`) that existed only because the
 *    layout was too primitive to give each fact its own visual slot.
 *
 * ### Selectable/copyable text (ROADMAP backlog, addressed alongside Task 6 Phase D)
 * [BookHeader] and [MetadataCard] are each wrapped in their own [SelectionContainer] (rather than
 * one container spanning the whole tab) so title/ISBN/format/etc. text stays long-press
 * selectable/copyable while [TimerCard]'s live elapsed-time readout -- which changes every second
 * while running -- is deliberately left outside any [SelectionContainer]. Both wrapped composables
 * carry their own [DisableSelection] carve-outs around clickable/long-pressable children (status
 * chip, ISBN copy button, cover image) exactly as before.
 */
@Composable
private fun DetailsTab(
    book: MediaItemEntity,
    details: BookDetailsEntity?,
    currentProgress: Double?,
    coverStorageDir: String,
    isRefetchingCover: Boolean,
    timerState: ReadingTimerState,
    elapsedSeconds: Long,
    onStartReading: () -> Unit,
    onPauseReading: () -> Unit,
    onResumeReading: () -> Unit,
    onStopReading: () -> Unit,
    onStatusChange: (ReadingStatus) -> Unit,
    onCopyIsbn: (String) -> Unit,
    onRefetchCover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // Inside the scroll: the tab's content passes under the navigation bar, and its
                // last row still clears it. The screen's Box deliberately does not apply this.
                .padding(barPadding(WindowInsetsSides.Bottom))
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SelectionContainer {
            BookHeader(
                book = book,
                details = details,
                coverStorageDir = coverStorageDir,
                isRefetchingCover = isRefetchingCover,
                onStatusChange = onStatusChange,
                onRefetchCover = onRefetchCover,
            )
        }

        ProgressSection(
            currentProgress = currentProgress,
            totalPages = details?.totalPages,
            trackingMode = details?.trackingMode,
        )

        TimerCard(
            timerState = timerState,
            elapsedSeconds = elapsedSeconds,
            onStart = onStartReading,
            onPause = onPauseReading,
            onResume = onResumeReading,
            onStop = onStopReading,
        )

        if (details != null) {
            SelectionContainer {
                MetadataCard(details = details, onCopyIsbn = onCopyIsbn)
            }
        }
    }
}

/**
 * Reading history tab content: the manual-entry affordance plus a **timeline** of session history
 * (books-polish pass revamp; originally ROADMAP Task 6 Phase D's flat [LazyColumn] of
 * concatenated-string [SessionRow]s). **The live timer moved to the Details tab in an earlier
 * books-polish pass** (see [BookDetailContent]'s KDoc) -- this tab keeps only the manual-entry
 * affordance and the session history, per that same change's scope.
 *
 * ### Timeline construction (Compose primitives only -- AGENTS.md §5, no chart/timeline library)
 * [buildTimelineEntries] flattens [sessions] (already most-recent-first) into a single ordered list
 * of [TimelineEntry] -- a [TimelineEntry.DateHeader] whenever the calendar day changes, interleaved
 * with a [TimelineEntry.SessionEntry] per session -- and the whole flattened list is rendered as one
 * continuous rail: [TimelineRow] draws a vertical line segment above/below a node for every entry
 * (a plain [Canvas] line + circle, no drawing library), with the line suppressed only at the very
 * first and very last entry so the rail reads as one unbroken spine running through both date
 * headers and session cards, exactly like a changelog/commit-history timeline. Each session's facts
 * (time, duration, position range, pages read) render as distinct [StatBadge] chips in
 * [SessionEventCard] rather than one concatenated string.
 *
 * [onEditSessionClick]/[onDeleteSessionClick] receive the whole [ReadingSessionEntity] (rather than
 * just its id) so [BookDetailContent] can populate `sessionToEdit`/`sessionToDelete` exactly as
 * before this was split out.
 */
@Composable
private fun ReadingHistoryTab(
    sessions: List<ReadingSessionEntity>,
    onLogManuallyClick: () -> Unit,
    onEditSessionClick: (ReadingSessionEntity) -> Unit,
    onDeleteSessionClick: (ReadingSessionEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = remember(sessions) { buildTimelineEntries(sessions) }
    val today = remember { java.time.LocalDate.now() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // Bottom bar inset only -- the screen's pinned tab row above already applied the
        // horizontal one, and applying it twice would indent the sessions past the tabs.
        contentPadding = PaddingValues(16.dp).plus(barPadding(WindowInsetsSides.Bottom)),
    ) {
        item {
            TextButton(
                onClick = onLogManuallyClick,
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Text(stringResource(R.string.log_session_manually))
            }
        }

        if (sessions.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.no_sessions_logged_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
                val showTopLine = index > 0
                val showBottomLine = index < entries.lastIndex
                when (entry) {
                    is TimelineEntry.DateHeader ->
                        TimelineRow(
                            showTopLine = showTopLine,
                            showBottomLine = showBottomLine,
                            showDot = false,
                        ) {
                            TimelineDateHeader(date = entry.date, today = today)
                        }
                    is TimelineEntry.SessionEntry ->
                        TimelineRow(
                            showTopLine = showTopLine,
                            showBottomLine = showBottomLine,
                            showDot = true,
                        ) {
                            SessionEventCard(
                                session = entry.session,
                                onEditClick = { onEditSessionClick(entry.session) },
                                onDeleteClick = { onDeleteSessionClick(entry.session) },
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                        }
                }
            }
        }
    }
}

/**
 * Cover + heading block: cover thumbnail (left, interactive -- see [InteractiveCoverBox]), title
 * and release year as a proper heading (right) rather than a body-text row, and the reading-status
 * chip. ISBN/format/total-pages/tracking-mode moved out to [MetadataCard] and current progress to
 * [ProgressSection] (books-polish pass revamp) -- this header's job is now purely "what book is
 * this and what's its status," not a catch-all metadata dump.
 *
 * The reading status (ROADMAP Task 6 Phase C) is rendered as a tappable [AssistChip] that opens a
 * [DropdownMenu] of every [ReadingStatus] -- a quick, one-tap status change without leaving this
 * screen for the full edit-metadata form (`EditBookScreen`'s status radio group is for a deliberate
 * full-form edit; this chip is for the common "just finished this" / "started reading this" case).
 * Hidden entirely when [details] is null (nothing to change yet — the data-integrity edge case
 * documented on [com.hub.media.features.books.data.BookRepository.observeBookDetail]).
 *
 * ### Cover interactions replace the standalone re-fetch button (books-polish pass)
 * The cover thumbnail's tap-to-enlarge / long-press-to-refetch behavior is implemented by
 * [InteractiveCoverBox] -- see its KDoc for how [isRefetchingCover]/[onRefetchCover] and the
 * no-ISBN explanation (previously inline body text next to a standalone
 * [OutlinedButton][androidx.compose.material3.OutlinedButton]) survive the move onto the cover
 * itself.
 *
 * The status [AssistChip] and the cover ([InteractiveCoverBox]) are each wrapped in
 * [DisableSelection] (ROADMAP backlog: selectable/copyable text) since the caller ([DetailsTab])
 * wraps this whole composable in a [SelectionContainer] -- without the carve-out, long-press-to-
 * select would conflict with each element's own tap/long-press handling (most notably the cover's
 * long-press-for-menu gesture, which is the same gesture selection uses).
 */
@Composable
private fun BookHeader(
    book: MediaItemEntity,
    details: BookDetailsEntity?,
    coverStorageDir: String,
    isRefetchingCover: Boolean,
    onStatusChange: (ReadingStatus) -> Unit,
    onRefetchCover: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DisableSelection {
            InteractiveCoverBox(
                coverStorageDir = coverStorageDir,
                coverImageHash = book.coverImageHash,
                hasIsbn = !details?.isbn.isNullOrBlank(),
                isRefetchingCover = isRefetchingCover,
                onRefetchCover = onRefetchCover,
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val releaseYear = book.releaseYear
            if (releaseYear != null) {
                Text(
                    text = stringResource(R.string.detail_published_year, releaseYear),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (details != null) {
                DisableSelection {
                    StatusChip(status = details.status, onStatusChange = onStatusChange)
                }
            }
        }
    }
}

/**
 * Reading-progress section (books-polish pass), promoted above [TimerCard] on [DetailsTab] since
 * progress is "the thing the user checks most" (ROADMAP revamp brief) -- previously a single
 * `progress_prefix` body-text row buried in [BookHeader]'s metadata stack.
 *
 * Degrades gracefully through three cases, exactly mirroring [formatProgress]'s own precedence:
 * - No session ever logged ([currentProgress] null): a muted "nothing to show yet" message, no bar.
 * - A fraction is derivable (percent mode, or page mode with a known [totalPages]): the formatted
 *   text plus a [LinearProgressIndicator] for an at-a-glance visual, via [progressFraction].
 * - Page mode with no known [totalPages]: the formatted text (a bare page number) with no bar,
 *   since there is no denominator to visualize a fraction of.
 */
@Composable
private fun ProgressSection(
    currentProgress: Double?,
    totalPages: Int?,
    trackingMode: TrackingMode?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.detail_progress_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (currentProgress == null) {
                Text(
                    text = stringResource(R.string.detail_progress_not_started),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val progressText = formatProgress(currentProgress, totalPages, trackingMode)
                if (progressText != null) {
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                val fraction = progressFraction(currentProgress, totalPages, trackingMode)
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Book metadata as a compact, scannable two-column key/value grid (books-polish pass), replacing
 * the old `isbn_prefix`/`format_prefix`/`total_pages_prefix` concatenated-string [Text] rows that
 * used to live in [BookHeader] -- each fact now gets its own [MetadataRow] rather than sharing a
 * body-text line with a hardcoded label prefix. ISBN keeps its existing copy [IconButton] affordance
 * (wrapped in [DisableSelection] since the caller wraps this whole composable in a
 * [SelectionContainer]); total pages shows [R.string.detail_value_unknown] rather than being
 * omitted, so the grid's shape doesn't jump around depending on which fields a given book happens
 * to have.
 */
@Composable
private fun MetadataCard(
    details: BookDetailsEntity,
    onCopyIsbn: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val authors = details.authors
            if (!authors.isNullOrBlank()) {
                // Degrades cleanly when absent (ROADMAP Task 9 Phase A): most existing books have
                // no author on record until re-fetched -- this row is simply omitted, matching the
                // ISBN row's own conditional-display pattern just below, rather than showing a
                // "detail_value_unknown" placeholder every book would otherwise carry.
                MetadataRow(label = stringResource(R.string.detail_label_authors)) {
                    Text(text = authors, style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
            }
            val isbn = details.isbn
            if (!isbn.isNullOrBlank()) {
                val copyIsbnDescription = stringResource(R.string.isbn_copy_content_description)
                MetadataRow(label = stringResource(R.string.detail_label_isbn)) {
                    Text(text = isbn, style = MaterialTheme.typography.bodyMedium)
                    DisableSelection {
                        IconButton(
                            onClick = { onCopyIsbn(isbn) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_content_copy),
                                contentDescription = copyIsbnDescription,
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
            MetadataRow(label = stringResource(R.string.detail_label_format)) {
                Text(text = details.format.displayLabel(), style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
            MetadataRow(label = stringResource(R.string.detail_label_total_pages)) {
                Text(
                    text = details.totalPages?.toString() ?: stringResource(R.string.detail_value_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider()
            MetadataRow(label = stringResource(R.string.detail_label_tracking_mode)) {
                Text(text = details.trackingMode.displayLabel(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** One label/value row of [MetadataCard]'s key/value grid; [value] renders the row's right side. */
@Composable
private fun MetadataRow(
    label: String,
    value: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            value()
        }
    }
}

/**
 * The Details tab's cover thumbnail (books-polish pass), replacing the standalone "Re-fetch cover"
 * [OutlinedButton][androidx.compose.material3.OutlinedButton] that used to sit below [BookHeader]
 * on [DetailsTab]:
 * - **Tap** opens the cover enlarged in a [Dialog] ([EnlargedCoverDialog]) with
 *   [ContentScale.Fit], dismissible by tapping the enlarged image or the system back
 *   gesture/button.
 * - **Long-press** opens a [DropdownMenu] anchored on the cover with a single item that re-runs
 *   [onRefetchCover] (ROADMAP Task 6 Phase E's re-fetch-cover action).
 *
 * [Modifier.combinedClickable] provides the tap/long-press pair (`onClickLabel`/`onLongClickLabel`
 * give each gesture an accessible name for screen readers, since there's no visible label on the
 * cover itself the way the old button had "Re-fetch cover" text).
 *
 * ### Every piece of the old button's state survives the move
 * - [isRefetchingCover] disables the menu item exactly as it disabled the old button, and is
 *   *additionally* surfaced as a translucent overlay spinner directly on the cover -- unlike the
 *   old button's inline spinner (only visible once you'd already found the button), this stays
 *   visible regardless of whether the long-press menu happens to be open.
 * - The no-ISBN case ([hasIsbn] false) still disables the action and still explains why. The old
 *   button showed [R.string.refetch_cover_no_isbn] as a separate line of body text next to the
 *   (disabled) button; with no button left to put that text "next to", the same string is now the
 *   *menu item's own text* while it's disabled for that reason -- reachable the same way the
 *   action itself is reached (long-press), rather than a snackbar the user would have to trigger
 *   separately to learn why nothing happened.
 * - Failures (metadata lookup, download, save) are unchanged: they still flow into
 *   [BookDetailUiState.Ready.errorMessage] and surface via [BookDetailContent]'s existing banner,
 *   exactly as the old button's failures did -- nothing here duplicates that.
 *
 * Sized by [BOOK_COVER_ASPECT_RATIO] (2:3) rather than the previous fixed 120dp x 160dp (3:4) box
 * that used to make [ContentScale.Crop] slice the top/bottom off many covers, and rendered with
 * [ContentScale.Fit] (the header's own default via [CoverImage]'s `contentScale` param) so nothing
 * is cropped here the way [LibraryScreen]'s grid rows intentionally still crop for a uniform look.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InteractiveCoverBox(
    coverStorageDir: String,
    coverImageHash: String?,
    hasIsbn: Boolean,
    isRefetchingCover: Boolean,
    onRefetchCover: () -> Unit,
) {
    var showEnlargedCover by remember { mutableStateOf(false) }
    var showCoverMenu by remember { mutableStateOf(false) }
    val viewCoverLabel = stringResource(R.string.cover_view_action_label)
    val coverOptionsLabel = stringResource(R.string.cover_options_action_label)
    val refetchingDescription = stringResource(R.string.refetch_cover_in_progress)

    Box(
        modifier =
            Modifier
                .width(120.dp)
                .aspectRatio(BOOK_COVER_ASPECT_RATIO)
                .combinedClickable(
                    onClickLabel = viewCoverLabel,
                    onClick = { showEnlargedCover = true },
                    onLongClickLabel = coverOptionsLabel,
                    onLongClick = { showCoverMenu = true },
                ),
    ) {
        CoverImage(
            coverDir = coverStorageDir,
            coverImageHash = coverImageHash,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )

        if (isRefetchingCover) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .semantics { contentDescription = refetchingDescription },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                    color = Color.White,
                )
            }
        }

        DropdownMenu(expanded = showCoverMenu, onDismissRequest = { showCoverMenu = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        text =
                            when {
                                !hasIsbn -> stringResource(R.string.refetch_cover_no_isbn)
                                isRefetchingCover -> stringResource(R.string.refetch_cover_in_progress)
                                else -> stringResource(R.string.refetch_cover_button)
                            },
                    )
                },
                onClick = {
                    showCoverMenu = false
                    onRefetchCover()
                },
                enabled = hasIsbn && !isRefetchingCover,
            )
        }
    }

    if (showEnlargedCover) {
        EnlargedCoverDialog(
            coverStorageDir = coverStorageDir,
            coverImageHash = coverImageHash,
            onDismiss = { showEnlargedCover = false },
        )
    }
}

/**
 * Full-size cover view (books-polish pass), opened by tapping the Details tab's cover thumbnail
 * ([InteractiveCoverBox]). Rendered with [ContentScale.Fit] (never [ContentScale.Crop]) so the
 * whole cover is visible, sized to [BOOK_COVER_ASPECT_RATIO] within most of the screen's width
 * ([DialogProperties.usePlatformDefaultWidth] set to `false` so `fillMaxWidth` isn't capped by the
 * platform's default dialog width). Dismissible by tapping anywhere on the enlarged image, or by
 * the system back gesture/button (the [Dialog]'s default `onDismissRequest`/back handling, left as
 * the default rather than suppressed the way [PendingSessionDialog] deliberately suppresses it --
 * this dialog holds no unsaved user input to protect against an accidental dismiss).
 */
@Composable
private fun EnlargedCoverDialog(
    coverStorageDir: String,
    coverImageHash: String?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(0.9f)
                    .aspectRatio(BOOK_COVER_ASPECT_RATIO)
                    .clickable(onClick = onDismiss),
        ) {
            CoverImage(
                coverDir = coverStorageDir,
                coverImageHash = coverImageHash,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/**
 * Quick reading-status change control (ROADMAP Task 6 Phase C): an [AssistChip] showing [status]'s
 * display label that opens a [DropdownMenu] of every [ReadingStatus] on tap. Selecting an entry
 * calls [onStatusChange] and closes the menu; selecting the already-current status is a harmless
 * no-op re-application (matches [com.hub.media.features.books.data.BookRepository.updateReadingStatus]'s
 * own "re-saving the same FINISHED status preserves finishedAt" behavior).
 */
@Composable
private fun StatusChip(
    status: ReadingStatus,
    onStatusChange: (ReadingStatus) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(stringResource(R.string.status_prefix, status.displayLabel())) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ReadingStatus.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.displayLabel()) },
                    onClick = {
                        expanded = false
                        onStatusChange(option)
                    },
                )
            }
        }
    }
}

/**
 * Timer card: formatted elapsed time and action buttons gated by [timerState] --
 * [ReadingTimerState.Idle] shows "Start reading"; [ReadingTimerState.Running] shows "Pause" +
 * "Stop"; [ReadingTimerState.Paused] shows "Resume" + "Stop".
 *
 * Restyled in the books-polish pass to read as *the* primary action on [DetailsTab] rather than
 * another stacked card of equal visual weight: a `primaryContainer` background (Material 3's
 * highest-emphasis container short of `primary` itself, which would fight with the filled action
 * [Button]s below it) and full-width, evenly [Modifier.weight]ed buttons instead of the small
 * side-by-side pill buttons the plain-`Card` version used.
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.timer_card_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = formatElapsed(elapsedSeconds),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (timerState) {
                    is ReadingTimerState.Idle -> {
                        Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.start_reading_button))
                        }
                    }
                    is ReadingTimerState.Running -> {
                        Button(onClick = onPause, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.pause_button))
                        }
                        Button(onClick = onStop, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.stop_button))
                        }
                    }
                    is ReadingTimerState.Paused -> {
                        Button(onClick = onResume, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.resume_button))
                        }
                        Button(onClick = onStop, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.stop_button))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shared full-screen chrome for [PendingSessionDialog] and [ManualSessionDialog] (ROADMAP Task 7
 * Phase E visual revamp -- previously each was a cramped [AlertDialog] that already needed an
 * internal scroll for the "how long" + "progress" + notes fields; Material 3's own guidance favors
 * a full-screen dialog over an [AlertDialog] once a form gets this involved on a compact screen).
 * A plain [Dialog] with [DialogProperties.usePlatformDefaultWidth] set to `false` (rather than
 * navigating to a real destination) is used because these dialogs are opened from hoisted boolean/
 * nullable state in [BookDetailContent] (AGENTS.md §5), not from a nav-graph route -- switching to
 * an actual screen would mean threading a session-editing route and its args through the nav graph
 * for what is still, semantically, a transient piece of dialog state.
 *
 * Composition mirrors [EditBookScreen]/[SettingsScreen]'s established language exactly, so the
 * whole pair reads as one system rather than a fourth style:
 * - A [CenterAlignedTopAppBar] title, with an optional [Icons.Filled.Close] navigation icon
 *   ([showCloseIcon]) -- present on [ManualSessionDialog] (mirrors [EditBookScreen]'s back icon,
 *   which is itself just another way to invoke Cancel) but omitted on [PendingSessionDialog],
 *   which must offer no incidental way to abandon a finished timed run (see that dialog's KDoc).
 * - A scrollable body [Column] ([content]) holding titled, [Card]-backed sections built with
 *   [SessionFormSection] -- the same "title [Text] above a [Card]" convention as `EditBookScreen`'s
 *   `FormSection`/`SettingsScreen`'s `SettingsSection`.
 * - An optional non-scrolling [errorContent] slot, pinned above the bottom bar rather than inside
 *   the scrollable body, mirroring `EditBookForm`'s `errorMessage` placement -- an error must stay
 *   visible regardless of scroll position.
 * - A [bottomBar] built with [SessionDialogBottomBar], the same elevated, pinned two-button action
 *   row as `EditBookScreen`'s `EditBookBottomBar`.
 *
 * [dismissOnBackPress]/[dismissOnClickOutside] are forwarded straight to [DialogProperties] --
 * [PendingSessionDialog] sets both `false` (see its KDoc's "not dismissible" section, ROADMAP Task
 * 7 Phase E non-negotiable #6); [ManualSessionDialog] leaves both at their default `true` (an
 * un-started/editable form has nothing un-discardable about an incidental dismiss).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDialogFrame(
    title: String,
    onDismissRequest: () -> Unit,
    showCloseIcon: Boolean,
    bottomBar: @Composable () -> Unit,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    errorContent: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = dismissOnBackPress,
                dismissOnClickOutside = dismissOnClickOutside,
            ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                // This Dialog is its own window, so it needs its own IME handling -- Scaffold's
                // default contentWindowInsets don't include the IME, and without safeDrawing the
                // keyboard would cover the position/duration/notes fields below.
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text(title) },
                        navigationIcon = {
                            if (showCloseIcon) {
                                IconButton(onClick = onDismissRequest) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.cancel_button),
                                    )
                                }
                            }
                        },
                    )
                },
            ) { innerPadding ->
                Column(modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding).fillMaxSize()) {
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        content = content,
                    )
                    errorContent()
                    bottomBar()
                }
            }
        }
    }
}

/**
 * One titled, [Card]-backed group of related session-dialog fields (ROADMAP Task 7 Phase E),
 * mirroring `EditBookScreen`'s `FormSection`/`SettingsScreen`'s `SettingsSection` convention
 * exactly: a [Text] title above a [Card], its content column spaced by 16dp. Declared separately
 * per-file (Kotlin top-level `private` is file-scoped) rather than shared across files, matching
 * how this codebase already keeps each screen's private layout helpers local to that screen.
 */
@Composable
private fun SessionFormSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

/**
 * A tappable field that opens a picker: a small [label] above an [OutlinedButton] showing only the
 * current [value].
 *
 * The label is deliberately OUTSIDE the button. These fields sit two-to-a-row, so each button gets
 * roughly half the dialog's width; when the label lived inside as a `"Date: %s"` prefix it consumed
 * enough of that to ellipsize the value it was labelling ("Date: Aug ...", "Time: 12:5 ..."), which
 * is the one thing the control exists to show. Hoisting the label hands the whole button width to
 * the value, and matches how every other field on these dialogs is labelled.
 *
 * [value] still gets single-line ellipsis as a backstop for an unusually long locale-specific date
 * format on a narrow screen — but it should no longer be reachable in practice.
 */
@Composable
private fun PickerField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(text = value, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Persistent, non-scrolling two-button action row (ROADMAP Task 7 Phase E), pinned to the bottom of
 * [SessionDialogFrame] on an elevated [Surface] (Material 3's default `BottomAppBar` tonal
 * elevation, 3dp) -- the same shape as `EditBookScreen`'s `EditBookBottomBar`. [secondaryLabel]/
 * [onSecondary] is Cancel on [ManualSessionDialog] and Discard on [PendingSessionDialog];
 * [primaryLabel]/[onPrimary]/[primaryEnabled] is always Save.
 */
@Composable
private fun SessionDialogBottomBar(
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onSecondary, modifier = Modifier.weight(1f)) {
                Text(secondaryLabel)
            }
            Button(onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.weight(1f)) {
                Text(primaryLabel)
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
 * ### Visual structure (ROADMAP Task 7 Phase E revamp)
 * Rendered via [SessionDialogFrame] with `showCloseIcon = false` -- see this dialog's pre-existing
 * "not dismissible" section below, which the revamp does not relax: there is deliberately no
 * top-bar icon that could be tapped as a shortcut past the explicit Discard button. The finished
 * run's duration is surfaced as a small `primaryContainer` stat card (echoing [TimerCard], the same
 * control that produced this run) above a [SessionFormSection] "Progress" (start/end position,
 * page-mode's auto-derived pages-read or percent-mode's manual field) and a "Notes" section,
 * followed by [errorMessage] (pinned above the bottom bar, not inside the scrollable body, so it
 * stays visible regardless of scroll position) and a [SessionDialogBottomBar] (Discard/Save).
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
 * ### Page vs. percent mode ([trackingMode])
 * See [ManualSessionDialog]'s KDoc for the full rationale -- the same explicit
 * [com.hub.media.core.database.entities.TrackingMode] field is used here (ROADMAP Task 7 Phase A;
 * this dialog previously inferred the mode from `totalPages != null`, which is exactly the
 * invisible-flip problem this field replaces): [TrackingMode.PAGES][com.hub.media.core.database.entities.TrackingMode.PAGES]
 * derives pages read as `endUnit - startUnit` and shows it read-only rather than asking for it;
 * anything else (`PERCENT`, or `null` when this book has no [BookDetailsEntity] row) shows the
 * manual pages-read field, with the same parse-once + isError treatment as the position fields
 * (blank stays legitimately `null`; a non-blank unparseable value is rejected rather than silently
 * discarded).
 *
 * `onDismissRequest` is a no-op: this dialog represents a *finished, already-timed* run, so an
 * accidental outside tap or back press must not silently discard it the way it would discard an
 * un-started form. [onDiscard] (the explicit "Discard" button) is the only path that abandons the
 * pending session -- see [BookDetailViewModel.discardPendingSession].
 *
 * [currentProgress] (Task4 Phase E) prefills the start-position field with the book's last-known
 * progress as a "resume where you left off" convenience -- the user can freely edit or clear it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PendingSessionDialog(
    pendingSession: ReadingTimerResult,
    errorMessage: String?,
    currentProgress: Double?,
    trackingMode: TrackingMode?,
    onSave: (startUnit: Double, endUnit: Double, deltaPages: Int?, notes: String?) -> Unit,
    onDiscard: () -> Unit,
) {
    var startUnitText by remember { mutableStateOf(currentProgress?.let(::formatUnit) ?: "") }
    var endUnitText by remember { mutableStateOf("") }
    var deltaPagesText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf("") }

    val isPageMode = trackingMode == TrackingMode.PAGES

    val parsedStartUnit = startUnitText.toDoubleOrNull()
    val startUnitIsValid = parsedStartUnit != null && parsedStartUnit.isFinite()
    val startUnitShowsError = startUnitText.isNotBlank() && !startUnitIsValid

    val parsedEndUnit = endUnitText.toDoubleOrNull()
    val endUnitIsValid = parsedEndUnit != null && parsedEndUnit.isFinite()
    val endUnitShowsError = endUnitText.isNotBlank() && !endUnitIsValid

    // Page-mode: deltaPages is fully determined by the positions the user already entered, so it
    // needs no separate manual input -- see ManualSessionDialog's KDoc.
    val derivedDeltaPages =
        if (isPageMode && startUnitIsValid && endUnitIsValid) {
            (parsedEndUnit!! - parsedStartUnit!!).roundToInt()
        } else {
            null
        }

    val parsedDeltaPages = deltaPagesText.toIntOrNull()
    val deltaPagesIsValid = deltaPagesText.isBlank() || parsedDeltaPages != null
    val deltaPagesShowsError = !isPageMode && deltaPagesText.isNotBlank() && !deltaPagesIsValid

    val canSave =
        startUnitText.isNotBlank() &&
            startUnitIsValid &&
            endUnitText.isNotBlank() &&
            endUnitIsValid &&
            (isPageMode || deltaPagesIsValid)

    // Percent-mode position fields get a "%" suffix (ROADMAP Task 7 Phase E) -- page-mode positions
    // are raw page numbers with no fixed unit, so no suffix is shown for them (matches
    // ManualSessionDialog's identical treatment below).
    val positionSuffix: (@Composable () -> Unit)? =
        if (isPageMode) {
            null
        } else {
            { Text(stringResource(R.string.position_percent_suffix)) }
        }

    SessionDialogFrame(
        title = stringResource(R.string.save_reading_session_title),
        onDismissRequest = {}, // Incidental dismiss (outside tap / back) must not discard a finished run.
        showCloseIcon = false, // No shortcut past the explicit Discard button -- see this function's KDoc.
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
        errorContent = {
            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                )
            }
        },
        bottomBar = {
            SessionDialogBottomBar(
                primaryLabel = stringResource(R.string.save_button),
                primaryEnabled = canSave,
                onPrimary = {
                    onSave(
                        parsedStartUnit ?: 0.0,
                        parsedEndUnit ?: 0.0,
                        if (isPageMode) derivedDeltaPages else parsedDeltaPages,
                        notesText.ifBlank { null },
                    )
                },
                secondaryLabel = stringResource(R.string.discard_button),
                onSecondary = onDiscard,
            )
        },
    ) {
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.timer_card_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = formatElapsed(pendingSession.durationSeconds),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        SessionFormSection(title = stringResource(R.string.manual_entry_section_progress)) {
            OutlinedTextField(
                value = startUnitText,
                onValueChange = { startUnitText = it.filterDecimalInput() },
                label = { Text(stringResource(R.string.start_position_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = startUnitShowsError,
                suffix = positionSuffix,
                supportingText =
                    if (startUnitShowsError) {
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
                suffix = positionSuffix,
                supportingText =
                    if (endUnitShowsError) {
                        { Text(stringResource(R.string.position_invalid_error)) }
                    } else {
                        null
                    },
                modifier = Modifier.fillMaxWidth(),
            )
            if (isPageMode) {
                Text(
                    text =
                        stringResource(
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
                    supportingText =
                        if (deltaPagesShowsError) {
                            { Text(stringResource(R.string.pages_read_invalid_error)) }
                        } else {
                            null
                        },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SessionFormSection(title = stringResource(R.string.manual_entry_section_notes)) {
            OutlinedTextField(
                value = notesText,
                onValueChange = { notesText = it },
                label = { Text(stringResource(R.string.notes_optional_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Practical upper bound for [ManualSessionDialog]'s duration field, in minutes: 10 years
 * (`10 * 365 * 24 * 60`). A manually-backlogged single reading session in the tens of years is
 * never legitimate, so this exists to catch fat-fingered/overflowing input (see the duration
 * field's KDoc on [ManualSessionDialog]) -- it is not derived from `Long.MAX_VALUE / 60` (the
 * true limit before the entered-minutes-to-seconds conversion overflows), which is astronomically
 * larger and would let obviously-bogus values like a 12-digit minute count through unrejected.
 */
private const val MAX_MANUAL_DURATION_MINUTES = 10L * 365 * 24 * 60 // 5,256,000

/**
 * Dialog for logging -- or, since ROADMAP Task 6 Phase B, editing -- a session with no live timer
 * involved: session date + end time, duration (minutes), start/end position, pages-read (page-mode:
 * derived; percent-mode: manual/optional), and notes. Fields are grouped into "When" (date/time),
 * "How long" (duration), "Progress" (positions + pages), and "Notes" sections (the last split out
 * on its own as of the Task 7 Phase E visual revamp below; previously a bare field with no section
 * of its own).
 *
 * ### Visual structure (ROADMAP Task 7 Phase E revamp)
 * Rendered via [SessionDialogFrame] as a full-screen dialog rather than the cramped [AlertDialog]
 * this used before -- see [SessionDialogFrame]'s KDoc for why a full-screen presentation was chosen
 * over Material 3's `AlertDialog`/`ModalBottomSheet` for a form carrying this many fields. Each
 * group listed above is now its own [SessionFormSection] (title above a [Card], matching
 * `EditBookScreen`/`SettingsScreen`'s section convention) instead of a bare [labelMedium][MaterialTheme.typography]
 * [Text] header floating over ungrouped fields. `showCloseIcon = true` gives this dialog a top-bar
 * close icon (mirroring `EditBookScreen`'s back icon) in addition to the bottom bar's Cancel
 * button -- unlike [PendingSessionDialog], an un-started/editable form has nothing un-discardable
 * about an incidental dismiss, so both paths call the same [onDismiss].
 *
 * ### Create vs. edit ([sessionToEdit])
 * When [sessionToEdit] is `null` (opened from the "Log session manually" button), every field
 * starts blank/defaulted exactly as before ("now" for date/time, [currentProgress] for the start
 * position). When non-null (opened from a session row's edit icon, ROADMAP Task 6 Phase B), every
 * field is prefilled from that row instead: date/time from [ReadingSessionEntity.timestampEnd],
 * duration from [ReadingSessionEntity.durationSeconds] rounded to the nearest minute for *display*
 * (this dialog's duration field only has minute granularity), positions from `startUnit`/`endUnit`,
 * and notes verbatim. The dialog's title and the semantics of Save are the only other difference --
 * the caller ([BookDetailContent]) decides whether [onSave]'s payload means "create" or "update
 * `sessionToEdit.id`", this composable itself is agnostic to which.
 *
 * ### Duration precision is preserved when untouched (AGENTS.md §1 fix)
 * Rounding the *displayed* duration text to whole minutes does NOT mean rounding the *stored*
 * value. Task 6 Phase B originally converted whatever whole-minute count was in `durationText`
 * back to seconds unconditionally at Save time -- which meant opening this dialog to fix some
 * unrelated field (a page number, a note) on a timer-backed session with real sub-minute precision
 * (e.g. 1,847s = 30m47s) silently coarsened its stored `durationSeconds` to 1,860s the moment Save
 * was tapped, even though the user never touched the duration field. That is exactly the silent
 * mutation of an untouched field AGENTS.md §1 (user data safety) forbids, so it has been fixed:
 * [prefilledDurationText] captures the exact string [durationText] was seeded with, alongside
 * [originalDurationSeconds] (`sessionToEdit?.durationSeconds`). At Save time, `durationText` being
 * still identical to [prefilledDurationText] AND [originalDurationSeconds] being non-null together
 * mean "the user never edited this field" -- in that case [originalDurationSeconds] is re-emitted
 * to [onSave] verbatim, preserving whatever precision it had. Any other case (the text differs from
 * the prefill -- a genuine edit -- or there was no original value to fall back on, i.e. create mode
 * or an originally-unknown duration) falls back to the entered minutes converted to seconds, or
 * `null` when blank, exactly as before. A dedicated per-second edit UI is still not offered (the
 * user cannot deliberately enter sub-minute precision by hand) -- this fix only guarantees that
 * *not touching* the field never destroys precision that was already there.
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
 * ### Page vs. percent mode ([trackingMode])
 * `deltaPages` is redundant to ask for whenever positions are page numbers -- it's fully
 * determined by `endUnit - startUnit`, which the user is already entering (ROADMAP Task 6 Phase
 * B). **As of ROADMAP Task 7 Phase A**, the mode this dialog operates in is read directly from
 * [com.hub.media.core.database.entities.BookDetailsEntity.trackingMode] rather than inferred from
 * `totalPages != null` (the pre-Phase-A behavior, kept only in this paragraph's history for
 * context): that inference was invisible to the user and flipped silently the moment `totalPages`
 * was edited, which is exactly the problem [com.hub.media.core.database.entities.TrackingMode]
 * exists to fix -- see its KDoc. [formatProgress] (this same screen) reads the identical field, so
 * there remains exactly one source of truth for "is this book tracked by page or by percent"
 * across the whole screen. In page mode ([TrackingMode.PAGES]) the pages-read field is replaced by
 * a read-only derived-value [Text] ("Pages read (auto): N") computed from the position fields,
 * making which mode is active visually obvious; in percent mode ([TrackingMode.PERCENT], or `null`
 * when this book has no [BookDetailsEntity] row) the manual field is shown exactly as before, just
 * with the same parse-once + isError validation now applied to every other numeric field (see
 * below).
 *
 * ### Duration is optional (schema v2, ROADMAP Task 5 pre-phase)
 * The duration field may be left blank: the effective `durationSeconds` emitted to [onSave] is
 * then `null`, forwarded all the way to
 * [com.hub.media.core.database.entities.ReadingSessionEntity.durationSeconds] as `null` rather
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
 *   bounds a parseable value to [MAX_MANUAL_DURATION_MINUTES], which protects this composable's
 *   own `parsedDurationMinutes * 60` seconds conversion and the caller's
 *   `timestampEnd - durationSeconds.seconds` arithmetic in [BookDetailScreenRoute] -- both
 *   saturate rather than throw on overflow, so an unreasonably large-but-parseable value would
 *   otherwise persist a nonsense `durationSeconds` rather than fail loudly.
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualSessionDialog(
    currentProgress: Double?,
    trackingMode: TrackingMode?,
    sessionToEdit: ReadingSessionEntity?,
    onSave: (
        durationSeconds: Long?,
        timestampEnd: Instant,
        startUnit: Double,
        endUnit: Double,
        deltaPages: Int?,
        notes: String?,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    val isPageMode = trackingMode == TrackingMode.PAGES

    // The session's original per-second-precision duration, when editing one that had a known
    // duration -- `null` in create mode, and `null` when the session being edited itself had an
    // unknown duration. Captured once so Save can fall back to it verbatim -- see the "Duration
    // precision is preserved when untouched" section of this function's KDoc.
    val originalDurationSeconds = remember { sessionToEdit?.durationSeconds }

    // The exact string `durationText` below was seeded with. Comparing against this (rather than,
    // say, "is it non-blank") at Save time is what lets Save distinguish "user never touched this
    // field" from "user edited it, then happened to retype the same digits" -- both leave
    // `durationText == prefilledDurationText`, and both are legitimately treated as "unchanged"
    // (retyping the identical value is, semantically, still not a change).
    val prefilledDurationText =
        remember {
            sessionToEdit?.durationSeconds?.let { seconds ->
                kotlin.math
                    .round(seconds / 60.0)
                    .toLong()
                    .toString()
            } ?: ""
        }
    var durationText by remember { mutableStateOf(prefilledDurationText) }
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
    // those two `null` causes (and gates the Save button below); `validatedDurationMinutes` feeds
    // `effectiveDurationSeconds` below, which is the value that actually reaches [onSave].
    val parsedDurationMinutes = durationText.toLongOrNull()
    val durationIsValid =
        durationText.isBlank() ||
            (parsedDurationMinutes != null && parsedDurationMinutes <= MAX_MANUAL_DURATION_MINUTES)
    val validatedDurationMinutes = if (durationText.isBlank()) null else parsedDurationMinutes

    // The value that actually reaches [onSave] -- see the "Duration precision is preserved when
    // untouched" section of this function's KDoc. `durationText == prefilledDurationText` means
    // the user never (net) changed what this field was seeded with; combined with
    // `originalDurationSeconds != null` (there was a real per-second value to preserve), that
    // means Save must re-emit it verbatim rather than reconstructing it from the rounded minutes
    // currently on screen. Every other case -- a genuine edit, a blank field, or no original value
    // to fall back on (create mode, or a session whose duration was itself unknown) -- falls back
    // to the entered minutes converted to seconds (`null` when blank), exactly as before this fix.
    val effectiveDurationSeconds =
        if (durationText == prefilledDurationText && originalDurationSeconds != null) {
            originalDurationSeconds
        } else {
            validatedDurationMinutes?.let { it * 60 }
        }

    val parsedStartUnit = startUnitText.toDoubleOrNull()
    val startUnitIsValid = parsedStartUnit != null && parsedStartUnit.isFinite()
    val startUnitShowsError = startUnitText.isNotBlank() && !startUnitIsValid

    val parsedEndUnit = endUnitText.toDoubleOrNull()
    val endUnitIsValid = parsedEndUnit != null && parsedEndUnit.isFinite()
    val endUnitShowsError = endUnitText.isNotBlank() && !endUnitIsValid

    // Page-mode: deltaPages is fully determined by the positions already entered -- see the "Page
    // vs. percent mode" section of this function's KDoc.
    val derivedDeltaPages =
        if (isPageMode && startUnitIsValid && endUnitIsValid) {
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
    val initialDateTime =
        remember(sessionToEdit) {
            sessionToEdit?.timestampEnd?.let(::instantToLocalDateTime) ?: java.time.LocalDateTime.now()
        }
    val datePickerState =
        rememberDatePickerState(
            initialSelectedDateMillis = remember { localDateToUtcMidnightMillis(initialDateTime.toLocalDate()) },
        )
    var showDatePicker by remember { mutableStateOf(false) }
    // Snapshot of datePickerState.selectedDateMillis taken when the picker is opened, so a
    // picked-then-Cancelled date can be reverted rather than sticking (OK leaves the in-dialog
    // selection unchanged; only Cancel restores this).
    var dateBeforePickerOpen by remember { mutableStateOf<Long?>(null) }

    val is24Hour =
        remember {
            android.text.format.DateFormat
                .is24HourFormat(context)
        }
    val timePickerState =
        rememberTimePickerState(
            initialHour = initialDateTime.hour,
            initialMinute = initialDateTime.minute,
            is24Hour = is24Hour,
        )
    var showTimePicker by remember { mutableStateOf(false) }
    // Snapshot of the selected hour/minute taken when the picker is opened, so a
    // picked-then-Cancelled time can be reverted rather than sticking (OK leaves the in-dialog
    // selection unchanged; only Cancel restores this) -- mirrors dateBeforePickerOpen above.
    var timeBeforePickerOpen by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val canSave =
        startUnitText.isNotBlank() &&
            startUnitIsValid &&
            endUnitText.isNotBlank() &&
            endUnitIsValid &&
            durationIsValid &&
            (isPageMode || deltaPagesIsValid)

    // Percent-mode position fields get a "%" suffix (ROADMAP Task 7 Phase E) -- page-mode positions
    // are raw page numbers with no fixed unit, so no suffix is shown for them (matches
    // PendingSessionDialog's identical treatment).
    val positionSuffix: (@Composable () -> Unit)? =
        if (isPageMode) {
            null
        } else {
            { Text(stringResource(R.string.position_percent_suffix)) }
        }

    SessionDialogFrame(
        title =
            stringResource(
                if (sessionToEdit != null) R.string.edit_session_title else R.string.log_session_manually,
            ),
        onDismissRequest = onDismiss,
        showCloseIcon = true,
        bottomBar = {
            SessionDialogBottomBar(
                primaryLabel = stringResource(R.string.save_button),
                primaryEnabled = canSave,
                onPrimary = {
                    onSave(
                        // Blank duration => null ("unknown"), not 0 -- see class KDoc's
                        // "Duration is optional" section. When the field is untouched from its
                        // prefill and the session being edited had a known duration,
                        // effectiveDurationSeconds is that original per-second value verbatim
                        // (never rounded) -- see the "Duration precision is preserved when
                        // untouched" section of this function's KDoc. Otherwise it's the entered
                        // minutes converted to seconds; validatedDurationMinutes (feeding that
                        // fallback) is hoisted above the fields and gated by durationIsValid
                        // (below), so an unparseable/overflowing non-blank value can never reach
                        // here -- see the "Numeric field validation" section of this function's
                        // KDoc.
                        effectiveDurationSeconds,
                        deriveTimestampEnd(
                            dateUtcMidnightMillis =
                                datePickerState.selectedDateMillis
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
                secondaryLabel = stringResource(R.string.cancel_button),
                onSecondary = onDismiss,
            )
        },
    ) {
        SessionFormSection(title = stringResource(R.string.manual_entry_section_when)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The "Date"/"Time" labels sit ABOVE their buttons rather than inside them: each
                // button only gets half the dialog width, and an in-pill "Date: " prefix ate
                // enough of that to ellipsize the value itself ("Date: Aug ...", "Time: 12:5 ...").
                // Outside, the full button width belongs to the value.
                PickerField(
                    label = stringResource(R.string.date_field_label),
                    value = formatUtcMidnightMillis(datePickerState.selectedDateMillis),
                    onClick = {
                        dateBeforePickerOpen = datePickerState.selectedDateMillis
                        showDatePicker = true
                    },
                    modifier = Modifier.weight(1f),
                )
                PickerField(
                    label = stringResource(R.string.time_field_label),
                    value = formatTimeOfDay(context, timePickerState.hour, timePickerState.minute),
                    onClick = {
                        timeBeforePickerOpen = timePickerState.hour to timePickerState.minute
                        showTimePicker = true
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SessionFormSection(title = stringResource(R.string.manual_entry_section_how_long)) {
            OutlinedTextField(
                value = durationText,
                onValueChange = { durationText = it.filterIntegerInput() },
                label = { Text(stringResource(R.string.duration_minutes_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                suffix = { Text(stringResource(R.string.duration_minutes_suffix)) },
                singleLine = true,
                isError = !durationIsValid,
                supportingText =
                    if (!durationIsValid) {
                        { Text(stringResource(R.string.duration_minutes_invalid, MAX_MANUAL_DURATION_MINUTES)) }
                    } else {
                        null
                    },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SessionFormSection(title = stringResource(R.string.manual_entry_section_progress)) {
            OutlinedTextField(
                value = startUnitText,
                onValueChange = { startUnitText = it.filterDecimalInput() },
                label = { Text(stringResource(R.string.start_position_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = startUnitShowsError,
                suffix = positionSuffix,
                supportingText =
                    if (startUnitShowsError) {
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
                suffix = positionSuffix,
                supportingText =
                    if (endUnitShowsError) {
                        { Text(stringResource(R.string.position_invalid_error)) }
                    } else {
                        null
                    },
                modifier = Modifier.fillMaxWidth(),
            )
            if (isPageMode) {
                Text(
                    text =
                        stringResource(
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
                    supportingText =
                        if (deltaPagesShowsError) {
                            { Text(stringResource(R.string.pages_read_invalid_error)) }
                        } else {
                            null
                        },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SessionFormSection(title = stringResource(R.string.manual_entry_section_notes)) {
            OutlinedTextField(
                value = notesText,
                onValueChange = { notesText = it },
                label = { Text(stringResource(R.string.notes_optional_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

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
 * One entry in the Reading history timeline: either a date separator or a session event.
 * See [buildTimelineEntries] for how [ReadingHistoryTab]'s flat, most-recent-first session list is
 * turned into this ordered sequence, and [TimelineRow] for how the sequence is rendered as one
 * continuous rail.
 */
private sealed class TimelineEntry {
    /** Stable [LazyColumn]/`itemsIndexed` key for this entry. */
    abstract val key: String

    /** A calendar-day separator, inserted whenever consecutive sessions fall on different days. */
    data class DateHeader(
        val date: java.time.LocalDate,
    ) : TimelineEntry() {
        override val key: String = "date-$date"
    }

    /** A single reading session, rendered by [SessionEventCard]. */
    data class SessionEntry(
        val session: ReadingSessionEntity,
    ) : TimelineEntry() {
        override val key: String = session.id
    }
}

/**
 * Flattens [sessions] (most-recent-first, per [BookDetailUiState.Ready.sessions]) into an ordered
 * [TimelineEntry] list: a [TimelineEntry.DateHeader] the first time (walking newest-to-oldest) a
 * given calendar day is seen, immediately followed by a [TimelineEntry.SessionEntry] per session on
 * that day. Grouping by simple adjacency (rather than a full `groupBy`) is correct *because*
 * [sessions] is already sorted by time -- every session for a given day is guaranteed contiguous,
 * so no re-sort/merge step is needed. The calendar day itself is derived via the existing
 * [instantToLocalDateTime] conversion (device-local timezone), the same helper
 * [formatSessionDate]/[ManualSessionDialog]'s edit-mode prefill already use, so there is exactly
 * one Instant-to-local-day conversion in this file.
 */
private fun buildTimelineEntries(sessions: List<ReadingSessionEntity>): List<TimelineEntry> {
    val entries = mutableListOf<TimelineEntry>()
    var lastDate: java.time.LocalDate? = null
    for (session in sessions) {
        val date = instantToLocalDateTime(session.timestampStart).toLocalDate()
        if (date != lastDate) {
            entries += TimelineEntry.DateHeader(date)
            lastDate = date
        }
        entries += TimelineEntry.SessionEntry(session)
    }
    return entries
}

/**
 * Renders one [TimelineEntry] row as a rail segment (left) + [content] (right): a plain [Canvas]
 * draws a vertical line above/below a center dot, built entirely from Compose primitives per
 * AGENTS.md §5 (no charting/timeline library). [showTopLine]/[showBottomLine] are suppressed only
 * at the very first/last entry of the whole flattened list (see call site in [ReadingHistoryTab]),
 * so the rail reads as one unbroken spine running through every date header and session card rather
 * than a series of disconnected per-row ticks. [showDot] is false for date headers (a plain pass-
 * through line, no node) and true for session entries.
 *
 * `Modifier.height(IntrinsicSize.Min)` on the outer [Row] is what lets the rail [Canvas] stretch to
 * match whatever height [content] actually needs (a session card's height varies with whether it has
 * notes, a pages-read badge, etc.) without either composable needing to know the other's size ahead
 * of time.
 */
@Composable
private fun TimelineRow(
    showTopLine: Boolean,
    showBottomLine: Boolean,
    showDot: Boolean,
    content: @Composable () -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val dotColor = MaterialTheme.colorScheme.primary
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
    ) {
        Canvas(
            modifier =
                Modifier
                    .width(24.dp)
                    .fillMaxHeight(),
        ) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val strokeWidth = 2.dp.toPx()
            if (showTopLine) {
                drawLine(
                    color = lineColor,
                    start = Offset(centerX, 0f),
                    end = Offset(centerX, centerY),
                    strokeWidth = strokeWidth,
                )
            }
            if (showBottomLine) {
                drawLine(
                    color = lineColor,
                    start = Offset(centerX, centerY),
                    end = Offset(centerX, size.height),
                    strokeWidth = strokeWidth,
                )
            }
            if (showDot) {
                drawCircle(color = dotColor, radius = 5.dp.toPx(), center = Offset(centerX, centerY))
            }
        }
        Box(modifier = Modifier.weight(1f).padding(start = 8.dp, bottom = 4.dp)) {
            content()
        }
    }
}

/**
 * A timeline date separator: "Today"/"Yesterday" for the two most recent days (a small relative-
 * date convenience), the full `MMM d, yyyy` date otherwise via the existing [DATE_ONLY_FORMATTER].
 */
@Composable
private fun TimelineDateHeader(
    date: java.time.LocalDate,
    today: java.time.LocalDate,
) {
    val label =
        when (date) {
            today -> stringResource(R.string.timeline_today)
            today.minusDays(1) -> stringResource(R.string.timeline_yesterday)
            else -> DATE_ONLY_FORMATTER.format(date)
        }
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
}

/**
 * One reading session's timeline card: time-of-day + edit/delete icons on top, its facts as
 * distinct [StatBadge] chips (duration, position range, pages read if known) rather than the old
 * single concatenated `"Duration: 0:31:00  •  42 -> 78"` line, and notes (if any) below.
 *
 * ### Edit affordance (ROADMAP Task 6 Phase B, unchanged by this revamp)
 * An explicit edit [IconButton] (rather than making the whole card tappable) was chosen because the
 * card already carries a delete [IconButton] for its other destructive/mutating action -- adding a
 * second icon button keeps both actions equally explicit and discoverable, exactly mirroring the
 * TopAppBar's existing Edit-then-Delete icon pair for the book itself (ROADMAP Task 6 Phase A).
 *
 * ### Unknown duration ([ReadingSessionEntity.durationSeconds] nullable, schema v2)
 * A backlogged manual entry may have been saved with no known duration. When `null`, the duration
 * [StatBadge] shows [R.string.session_duration_unknown] ("Duration unknown") in a muted/italic
 * style -- never [formatElapsed] on a substitute `0`, which would misleadingly read as a real
 * zero-length session (see that entity's KDoc for why `null` and `0` must stay visually and
 * semantically distinct; this is the same distinction the pre-revamp [SessionRow] preserved by
 * omitting the duration segment entirely, just now rendered as an explicit, legible chip instead of
 * a silently-shorter string).
 */
@Composable
private fun SessionEventCard(
    session: ReadingSessionEntity,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val localDateTime = instantToLocalDateTime(session.timestampStart)
    val timeLabel = formatTimeOfDay(context, localDateTime.hour, localDateTime.minute)

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    IconButton(onClick = onEditClick, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.edit_session_content_description),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = onDeleteClick, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.delete_session_content_description),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val durationSeconds = session.durationSeconds
                StatBadge(
                    label = stringResource(R.string.session_stat_duration_label),
                    value =
                        if (durationSeconds != null) {
                            formatElapsed(durationSeconds)
                        } else {
                            stringResource(R.string.session_duration_unknown)
                        },
                    muted = durationSeconds == null,
                )
                StatBadge(
                    label = stringResource(R.string.session_stat_progress_label),
                    value =
                        stringResource(
                            R.string.session_position_range,
                            formatUnit(session.startUnit),
                            formatUnit(session.endUnit),
                        ),
                )
                val deltaPages = session.deltaPages
                if (deltaPages != null) {
                    StatBadge(
                        label = stringResource(R.string.session_stat_pages_label),
                        value = stringResource(R.string.session_pages_delta, deltaPages),
                    )
                }
            }

            val notes = session.notes
            if (!notes.isNullOrBlank()) {
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One small labelled fact chip used by [SessionEventCard] (duration, position range, pages read)
 * -- replaces the old single-line concatenated string with a distinct visual element per fact, per
 * the ROADMAP revamp brief. [muted]/italic styling is used specifically for the unknown-duration
 * case so it reads as "we don't know" rather than a normal value.
 */
@Composable
private fun StatBadge(
    label: String,
    value: String,
    muted: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontStyle = if (muted) FontStyle.Italic else FontStyle.Normal,
                color =
                    if (muted) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
        }
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
 * Formats [currentProgress] for display, keyed off the book's explicit
 * [com.hub.media.core.database.entities.TrackingMode] (ROADMAP Task 7 Phase A) rather than
 * inferring it from [totalPages] being non-null (the pre-Phase-A behavior) -- see that enum's KDoc
 * for why that inference was replaced: [totalPages] is now used purely as an optional denominator,
 * never as the mode signal itself.
 *
 * - [TrackingMode.PAGES][com.hub.media.core.database.entities.TrackingMode.PAGES]: page-style
 *   ("Page 142 / 350") when [totalPages] is also known, or a bare page number ("Page 142") when a
 *   book is explicitly tracked by page but has no recorded total (an edge case the old
 *   totalPages-only inference could never produce, since a null total always meant percent mode
 *   then -- now that the two fields are independent, this dialog must not mislabel a raw page
 *   number as a percentage).
 * - [TrackingMode.PERCENT][com.hub.media.core.database.entities.TrackingMode.PERCENT] or `null`
 *   (this book has no [BookDetailsEntity] row): percent-style ("37%").
 *
 * Returns null (nothing to show) when [currentProgress] itself is null, i.e. no session has ever
 * been logged.
 *
 * Note: This is a @Composable function that calls [stringResource] to fetch localized strings
 * from resources rather than using hardcoded string literals.
 */
@Composable
private fun formatProgress(
    currentProgress: Double?,
    totalPages: Int?,
    trackingMode: TrackingMode?,
): String? {
    if (currentProgress == null) return null
    return when {
        trackingMode == TrackingMode.PAGES && totalPages != null ->
            stringResource(R.string.progress_page_format, currentProgress.roundToInt(), totalPages)
        trackingMode == TrackingMode.PAGES ->
            stringResource(R.string.progress_page_only_format, currentProgress.roundToInt())
        else -> stringResource(R.string.progress_percent_format, currentProgress.roundToInt())
    }
}

/**
 * Derives a `0f..1f` completion fraction for [ProgressSection]'s [LinearProgressIndicator], or
 * `null` when no fraction can be derived -- mirrors [formatProgress]'s own mode precedence so the
 * bar and its text label never disagree about what mode the book is in:
 * - [TrackingMode.PERCENT] (or `null`, no [BookDetailsEntity] row): [currentProgress] is already a
 *   0-100 percentage, so the fraction is simply `currentProgress / 100`.
 * - [TrackingMode.PAGES] with a known [totalPages]: `currentProgress / totalPages`.
 * - [TrackingMode.PAGES] with no known [totalPages]: `null` -- a bare page number has no
 *   denominator to visualize a fraction of (degrades to text-only, per [ProgressSection]'s KDoc).
 *
 * Clamped to `0f..1f` since a session's `endUnit` is user-entered and not validated against
 * `totalPages` at save time (a typo could otherwise overshoot the bar past 100%).
 */
private fun progressFraction(
    currentProgress: Double?,
    totalPages: Int?,
    trackingMode: TrackingMode?,
): Float? {
    if (currentProgress == null) return null
    val fraction =
        when {
            trackingMode == TrackingMode.PAGES && totalPages != null && totalPages > 0 ->
                currentProgress / totalPages
            trackingMode == TrackingMode.PAGES -> return null
            else -> currentProgress / 100.0
        }
    return fraction.toFloat().coerceIn(0f, 1f)
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
private fun formatTimeOfDay(
    context: android.content.Context,
    hour: Int,
    minute: Int,
): String {
    val calendar =
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
        }
    return android.text.format.DateFormat
        .getTimeFormat(context)
        .format(calendar.time)
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
    java.time.Instant
        .ofEpochMilli(instant.toEpochMilliseconds())
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDateTime()

private val SESSION_DATE_FORMATTER: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter
        .ofPattern("MMM d, yyyy HH:mm")

private val DATE_ONLY_FORMATTER: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter
        .ofPattern("MMM d, yyyy")

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
    val localDate =
        java.time.Instant
            .ofEpochMilli(utcMidnightMillis)
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
private fun deriveTimestampEnd(
    dateUtcMidnightMillis: Long,
    hour: Int,
    minute: Int,
): Instant {
    val localDate =
        java.time.Instant
            .ofEpochMilli(dateUtcMidnightMillis)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
    val zonedDateTime =
        localDate
            .atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
            .atZone(java.time.ZoneId.systemDefault())
    return Instant.fromEpochMilliseconds(zonedDateTime.toInstant().toEpochMilli())
}

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
