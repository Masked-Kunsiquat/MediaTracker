@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.github.maskedkunisquat.mediatracker.ui.screens

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingSessionEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.features.books.timer.ReadingTimerResult
import com.hub.media.features.books.timer.ReadingTimerState
import com.hub.media.ui.AppContainer
import com.hub.media.ui.BookDetailUiState
import com.hub.media.ui.BookDetailViewModel
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.launch

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
            0 -> DetailsTab(
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
            1 -> ReadingHistoryTab(
                sessions = state.sessions,
                onLogManuallyClick = { sessionToEdit = null; showManualEntry = true },
                onEditSessionClick = { session -> sessionToEdit = session; showManualEntry = true },
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
 * Details tab content (ROADMAP Task 6 Phase D): cover + metadata header, reading status, and
 * progress -- see [BookHeader] -- plus the live reading timer (moved here from Reading history in
 * the books-polish pass, see [BookDetailContent]'s KDoc). The "re-fetch cover" affordance (ROADMAP
 * Task 6 Phase E) no longer lives here as a standalone button either -- it moved onto the cover
 * image itself (tap to enlarge, long-press for a menu containing it), see [BookHeader].
 *
 * ### Selectable/copyable text (ROADMAP backlog, addressed alongside this phase)
 * [BookHeader] is wrapped in a [SelectionContainer] so its title/ISBN/format/etc. text can be
 * long-press selected and copied, applied narrowly to just this metadata block per the backlog
 * item's own caveat (long-press selection conflicts with clickable elements) -- [BookHeader]
 * itself wraps its clickable/long-pressable children (the status [AssistChip], the ISBN copy
 * button, and the cover image) in [DisableSelection] so none of their tap/long-press handling is
 * disrupted. This tab has no session rows or library cards (those live on the Reading history tab
 * and the library screen respectively, and are NOT wrapped in [SelectionContainer] anywhere), so
 * no further carve-out is needed here.
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
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SelectionContainer {
            BookHeader(
                book = book,
                details = details,
                currentProgress = currentProgress,
                coverStorageDir = coverStorageDir,
                isRefetchingCover = isRefetchingCover,
                onStatusChange = onStatusChange,
                onCopyIsbn = onCopyIsbn,
                onRefetchCover = onRefetchCover,
            )
        }

        TimerCard(
            timerState = timerState,
            elapsedSeconds = elapsedSeconds,
            onStart = onStartReading,
            onPause = onPauseReading,
            onResume = onResumeReading,
            onStop = onStopReading,
        )
    }
}

/**
 * Reading history tab content (ROADMAP Task 6 Phase D): the manual-entry affordance and session
 * history list. **The live timer moved to the Details tab in the books-polish pass** (see
 * [BookDetailContent]'s KDoc) -- this tab keeps only the manual-entry affordance and the session
 * list, per that same change's scope. [onEditSessionClick]/[onDeleteSessionClick] receive the
 * whole [ReadingSessionEntity] (rather than just its id) so [BookDetailContent] can populate
 * `sessionToEdit`/`sessionToDelete` exactly as it did before this was split out.
 */
@Composable
private fun ReadingHistoryTab(
    sessions: List<ReadingSessionEntity>,
    onLogManuallyClick: () -> Unit,
    onEditSessionClick: (ReadingSessionEntity) -> Unit,
    onDeleteSessionClick: (ReadingSessionEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TextButton(onClick = onLogManuallyClick) {
                Text(stringResource(R.string.log_session_manually))
            }
        }

        item {
            Text(
                text = stringResource(R.string.session_history_title),
                style = MaterialTheme.typography.titleMedium,
            )
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
            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    onEditClick = { onEditSessionClick(session) },
                    onDeleteClick = { onDeleteSessionClick(session) },
                )
            }
        }
    }
}

/**
 * Cover + metadata header: cover thumbnail (left, now interactive -- see [InteractiveCoverBox]),
 * title/release year/ISBN/format/total pages/status and current progress (right). ISBN/format/
 * total pages are only shown when [details] is non-null and the individual field is present;
 * [currentProgress] formatting is delegated to [formatProgress]. The reading status (ROADMAP Task
 * 6 Phase C) is rendered as a tappable [AssistChip] that opens a [DropdownMenu] of every
 * [ReadingStatus] -- a quick, one-tap status change without leaving this screen for the full
 * edit-metadata form (`EditBookScreen`'s status radio group is for a deliberate full-form edit;
 * this chip is for the common "just finished this" / "started reading this" case). Hidden entirely
 * when [details] is null (nothing to change yet — the data-integrity edge case documented on
 * [com.hub.media.features.books.data.BookRepository.observeBookDetail]).
 *
 * ### Cover interactions replace the standalone re-fetch button (books-polish pass)
 * The cover thumbnail's tap-to-enlarge / long-press-to-refetch behavior is implemented by
 * [InteractiveCoverBox] -- see its KDoc for how [isRefetchingCover]/[onRefetchCover] and the
 * no-ISBN explanation (previously inline body text next to a standalone
 * [OutlinedButton][androidx.compose.material3.OutlinedButton]) survive the move onto the cover
 * itself.
 *
 * The status [AssistChip], the ISBN's copy [IconButton], and the cover ([InteractiveCoverBox]) are
 * each wrapped in [DisableSelection] (ROADMAP backlog: selectable/copyable text) since the caller
 * ([DetailsTab]) wraps this whole composable in a [SelectionContainer] -- without the carve-out,
 * long-press-to-select would conflict with each element's own tap/long-press handling (most
 * notably the cover's long-press-for-menu gesture, which is the same gesture selection uses).
 */
@Composable
private fun BookHeader(
    book: MediaItemEntity,
    details: BookDetailsEntity?,
    currentProgress: Double?,
    coverStorageDir: String,
    isRefetchingCover: Boolean,
    onStatusChange: (ReadingStatus) -> Unit,
    onCopyIsbn: (String) -> Unit,
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
                val copyIsbnDescription = stringResource(R.string.isbn_copy_content_description)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.isbn_prefix, isbn),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    DisableSelection {
                        IconButton(
                            onClick = { onCopyIsbn(isbn) },
                            modifier = Modifier
                                .size(32.dp)
                                .semantics { contentDescription = copyIsbnDescription },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_content_copy),
                                contentDescription = null,
                            )
                        }
                    }
                }
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
            if (details != null) {
                DisableSelection {
                    StatusChip(status = details.status, onStatusChange = onStatusChange)
                }
            }
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
        modifier = Modifier
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
                modifier = Modifier
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
                        text = when {
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
            modifier = Modifier
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
private fun StatusChip(status: ReadingStatus, onStatusChange: (ReadingStatus) -> Unit) {
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
/**
 * Practical upper bound for [ManualSessionDialog]'s duration field, in minutes: 10 years
 * (`10 * 365 * 24 * 60`). A manually-backlogged single reading session in the tens of years is
 * never legitimate, so this exists to catch fat-fingered/overflowing input (see the duration
 * field's KDoc on [ManualSessionDialog]) -- it is not derived from `Long.MAX_VALUE / 60` (the
 * true limit before the entered-minutes-to-seconds conversion overflows), which is astronomically
 * larger and would let obviously-bogus values like a 12-digit minute count through unrejected.
 */
private const val MAX_MANUAL_DURATION_MINUTES = 10L * 365 * 24 * 60 // 5,256,000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualSessionDialog(
    currentProgress: Double?,
    totalPages: Int?,
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
    val isPageMode = totalPages != null

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
    val prefilledDurationText = remember {
        sessionToEdit?.durationSeconds?.let { seconds ->
            kotlin.math.round(seconds / 60.0).toLong().toString()
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
    val durationIsValid = durationText.isBlank() ||
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
    val effectiveDurationSeconds = if (durationText == prefilledDurationText && originalDurationSeconds != null) {
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
 * defaulted to this same tab). Now also covers the timer card (books-polish pass moved it here
 * from Reading history) alongside the interactive cover.
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

/**
 * Preview of the Reading history tab's content in isolation (ROADMAP Task 6 Phase D). No longer
 * includes the timer card (books-polish pass moved it to [DetailsTabPreview]/[DetailsTab]).
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
