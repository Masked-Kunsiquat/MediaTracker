@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.github.maskedkunisquat.mediatracker.ui.screens

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.BookDetailViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.components.DetailFact
import com.github.maskedkunisquat.mediatracker.ui.components.DetailFactAction
import com.github.maskedkunisquat.mediatracker.ui.components.DetailFacts
import com.github.maskedkunisquat.mediatracker.ui.components.DetailSynopsis
import com.github.maskedkunisquat.mediatracker.ui.insets.scrollingContentPadding
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
 * - [BookDetailUiState.Ready]: one scrolling page -- header, synopsis, progress, timer row, facts
 *   and reading history, in that order (#141 step 3; see this function's body).
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
 * @param onRefetchCover Called when the "Re-fetch cover" item is selected from the TopAppBar's
 *   overflow menu (ROADMAP Task 6 Phase E; moved there from the cover's long-press in #141 step 3
 *   -- see this function's `topBar`), wired to [BookDetailViewModel.refetchCover].
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

    var showOverflowMenu by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                // Empty: the title now lives in DetailHeader below, the same move Movie/TV made
                // (#141) -- DetailHeader marks it a heading directly.
                title = {},
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
                        // "Re-fetch cover" moved here from the cover's long-press (#141 step 3) --
                        // it was unreachable exactly when it was most wanted: with no cover, there
                        // was only a placeholder to long-press, and that placeholder is gone now
                        // that the header draws artwork only when a hash exists.
                        if (uiState.isRefetchingCover) {
                            val refetchingDescription = stringResource(R.string.refetch_cover_in_progress)
                            CircularProgressIndicator(
                                modifier =
                                    Modifier
                                        .padding(horizontal = 12.dp)
                                        .size(24.dp)
                                        .semantics { contentDescription = refetchingDescription },
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Box {
                                IconButton(onClick = { showOverflowMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription =
                                            stringResource(
                                                R.string.book_detail_overflow_menu_content_description,
                                            ),
                                    )
                                }
                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                    onDismissRequest = { showOverflowMenu = false },
                                ) {
                                    val hasIsbn = !uiState.details?.isbn.isNullOrBlank()
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (hasIsbn) {
                                                    stringResource(R.string.refetch_cover_button)
                                                } else {
                                                    stringResource(R.string.refetch_cover_no_isbn)
                                                },
                                            )
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            onRefetchCover()
                                        },
                                        enabled = hasIsbn,
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        // #141 step 3: one scrolling page replaces the Details/Reading history tabs. Lazy, not a
        // scrolling Column, for the same reason TVShowDetailScreen's season list is lazy -- the
        // reading-history timeline can grow long, and a Column would compose every row on entry
        // whether or not it is on screen. Each section is its own item, per that same file's
        // comment.
        //
        // This dialog-related state used to live on the now-deleted `BookDetailContent`, scoped to
        // the Ready branch only; it is hoisted here instead since there is no longer a second
        // composable for it to live on, but is otherwise unchanged.
        var sessionToDelete by remember { mutableStateOf<ReadingSessionEntity?>(null) }
        var showManualEntry by remember { mutableStateOf(false) }
        // Non-null while the manual-entry dialog is open in *edit* mode (opened from a session
        // row's edit icon, prefilled from this row); null while it's open in *create* mode
        // (opened from the reading-history section's "Log session manually" button). See
        // ManualSessionDialog's KDoc.
        var sessionToEdit by remember { mutableStateOf<ReadingSessionEntity?>(null) }

        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(innerPadding),
            contentPadding = scrollingContentPadding(innerPadding, PaddingValues(16.dp)),
        ) {
            when (uiState) {
                is BookDetailUiState.Loading ->
                    item {
                        CircularProgressIndicator(modifier = Modifier.fillMaxWidth().wrapContentWidth())
                    }

                // Nothing to render; the route wrapper navigates back on this state.
                is BookDetailUiState.NotFound -> {}

                is BookDetailUiState.Ready -> {
                    val book = uiState.book
                    val details = uiState.details

                    // Every other mutation that can fail (manual-entry save/edit, session delete,
                    // book delete, status change, cover refetch) has no dialog left open to show
                    // it in by the time it fails, so show it here instead, scoped to the case
                    // where the pending-session dialog isn't already surfacing it below.
                    val errorMessage = uiState.errorMessage
                    if (errorMessage != null && uiState.pendingSession == null) {
                        item {
                            Text(
                                text = errorMessage,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    item {
                        BookDetailHeaderSection(
                            book = book,
                            details = details,
                            coverStorageDir = coverStorageDir,
                            onStatusChange = onStatusChange,
                        )
                    }
                    item { DetailSynopsis(text = book.synopsis) }
                    item {
                        BookProgressSection(
                            currentProgress = uiState.currentProgress,
                            totalPages = details?.totalPages,
                            trackingMode = details?.trackingMode,
                        )
                    }
                    item {
                        BookTimerRow(
                            timerState = timerState,
                            elapsedSeconds = elapsedSeconds,
                            onStart = onStartReading,
                            onPause = onPauseReading,
                            onResume = onResumeReading,
                            onStop = onStopReading,
                        )
                    }
                    if (details != null) {
                        item {
                            DetailFacts(
                                facts =
                                    listOf(
                                        DetailFact(
                                            label = stringResource(R.string.detail_label_format),
                                            value = details.format.displayLabel(),
                                        ),
                                        DetailFact(
                                            label = stringResource(R.string.detail_label_pages),
                                            value = details.totalPages?.toString(),
                                        ),
                                        DetailFact(
                                            label = stringResource(R.string.detail_label_isbn),
                                            value = details.isbn,
                                            // Only when there's an ISBN to copy -- a null/blank
                                            // ISBN drops the whole row before this action would
                                            // ever render (DetailFacts' own null-value rule).
                                            action =
                                                details.isbn?.takeUnless { it.isBlank() }?.let { isbn ->
                                                    DetailFactAction(
                                                        iconRes = R.drawable.ic_content_copy,
                                                        contentDescription =
                                                            stringResource(R.string.isbn_copy_content_description),
                                                        onClick = { onCopyIsbn(isbn) },
                                                    )
                                                },
                                        ),
                                        DetailFact(
                                            label = stringResource(R.string.detail_label_tracking_mode),
                                            value = details.trackingMode.displayLabel(),
                                        ),
                                    ),
                            )
                        }
                    }

                    readingHistorySection(
                        sessions = uiState.sessions,
                        onLogManuallyClick = {
                            sessionToEdit = null
                            showManualEntry = true
                        },
                        onEditSessionClick = { session ->
                            sessionToEdit = session
                            showManualEntry = true
                        },
                        onDeleteSessionClick = { session -> sessionToDelete = session },
                    )
                }
            }
        }

        if (uiState is BookDetailUiState.Ready) {
            val pendingSession = uiState.pendingSession
            if (pendingSession != null) {
                PendingSessionDialog(
                    pendingSession = pendingSession,
                    errorMessage = uiState.errorMessage,
                    currentProgress = uiState.currentProgress,
                    trackingMode = uiState.details?.trackingMode,
                    onSave = onSaveSession,
                    onDiscard = onDiscardPendingSession,
                )
            }

            if (showManualEntry) {
                ManualSessionDialog(
                    currentProgress = uiState.currentProgress,
                    trackingMode = uiState.details?.trackingMode,
                    sessionToEdit = sessionToEdit,
                    onSave = { durationSeconds, timestampEnd, startUnit, endUnit, deltaPages, notes ->
                        val editing = sessionToEdit
                        if (editing != null) {
                            onEditSession(
                                editing.id,
                                durationSeconds,
                                timestampEnd,
                                startUnit,
                                endUnit,
                                deltaPages,
                                notes,
                            )
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
