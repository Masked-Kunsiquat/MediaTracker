@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.text.filterDecimalInput
import com.github.maskedkunisquat.mediatracker.ui.text.formatUnit
import com.hub.media.core.database.entities.ReadingSessionEntity
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.features.books.timer.ReadingTimerResult
import com.hub.media.ui.filterIntegerInput
import com.hub.media.ui.parseOptionalFiniteDouble
import com.hub.media.ui.parseOptionalNumber
import kotlin.math.roundToInt
import kotlin.time.Instant

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
internal fun PendingSessionDialog(
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

    // parseOptionalFiniteDouble rather than a raw toDoubleOrNull plus a hand-written isFinite
    // check (#78): an overflowing digit string parses to POSITIVE_INFINITY rather than failing, so
    // "did it parse" is not the question. The shared parser folds that in, which is why the
    // `isValid` lines below no longer restate it.
    val parsedStartUnit = parseOptionalFiniteDouble(startUnitText)
    val startUnitIsValid = parsedStartUnit?.value != null
    val startUnitShowsError = startUnitText.isNotBlank() && !startUnitIsValid

    val parsedEndUnit = parseOptionalFiniteDouble(endUnitText)
    val endUnitIsValid = parsedEndUnit?.value != null
    val endUnitShowsError = endUnitText.isNotBlank() && !endUnitIsValid

    // Page-mode: deltaPages is fully determined by the positions the user already entered, so it
    // needs no separate manual input -- see ManualSessionDialog's KDoc.
    val derivedDeltaPages =
        if (isPageMode && startUnitIsValid && endUnitIsValid) {
            (parsedEndUnit.value!! - parsedStartUnit.value!!).roundToInt()
        } else {
            null
        }

    // Blank stays distinct from unreadable here too: parseOptionalNumber returns a ParsedNumber
    // whose value is null for a blank field (an omitted count, which is allowed) and null itself
    // for text that does not parse -- so `deltaPagesIsValid` is simply "not unreadable".
    val parsedDeltaPages = parseOptionalNumber(deltaPagesText, String::toIntOrNull)
    val deltaPagesIsValid = parsedDeltaPages != null
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
                        parsedStartUnit?.value ?: 0.0,
                        parsedEndUnit?.value ?: 0.0,
                        if (isPageMode) derivedDeltaPages else parsedDeltaPages?.value,
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
internal const val MAX_MANUAL_DURATION_MINUTES = 10L * 365 * 24 * 60 // 5,256,000

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
internal fun ManualSessionDialog(
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
    val parsedDurationMinutes = parseOptionalNumber(durationText, String::toLongOrNull)
    // Read into a local rather than testing `parsedDurationMinutes.value` twice: it is a property
    // of a class in another module, so Kotlin will not smart-cast it and the bound check below
    // would not compile against the nullable type.
    val validatedDurationMinutes = parsedDurationMinutes?.value
    val durationIsValid =
        parsedDurationMinutes != null &&
            (validatedDurationMinutes == null || validatedDurationMinutes <= MAX_MANUAL_DURATION_MINUTES)

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

    // parseOptionalFiniteDouble rather than a raw toDoubleOrNull plus a hand-written isFinite
    // check (#78): an overflowing digit string parses to POSITIVE_INFINITY rather than failing, so
    // "did it parse" is not the question. The shared parser folds that in, which is why the
    // `isValid` lines below no longer restate it.
    val parsedStartUnit = parseOptionalFiniteDouble(startUnitText)
    val startUnitIsValid = parsedStartUnit?.value != null
    val startUnitShowsError = startUnitText.isNotBlank() && !startUnitIsValid

    val parsedEndUnit = parseOptionalFiniteDouble(endUnitText)
    val endUnitIsValid = parsedEndUnit?.value != null
    val endUnitShowsError = endUnitText.isNotBlank() && !endUnitIsValid

    // Page-mode: deltaPages is fully determined by the positions already entered -- see the "Page
    // vs. percent mode" section of this function's KDoc.
    val derivedDeltaPages =
        if (isPageMode && startUnitIsValid && endUnitIsValid) {
            (parsedEndUnit.value!! - parsedStartUnit.value!!).roundToInt()
        } else {
            null
        }

    // Blank stays distinct from unreadable here too: parseOptionalNumber returns a ParsedNumber
    // whose value is null for a blank field (an omitted count, which is allowed) and null itself
    // for text that does not parse -- so `deltaPagesIsValid` is simply "not unreadable".
    val parsedDeltaPages = parseOptionalNumber(deltaPagesText, String::toIntOrNull)
    val deltaPagesIsValid = parsedDeltaPages != null
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
                        parsedStartUnit?.value ?: 0.0,
                        parsedEndUnit?.value ?: 0.0,
                        if (isPageMode) derivedDeltaPages else parsedDeltaPages?.value,
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
