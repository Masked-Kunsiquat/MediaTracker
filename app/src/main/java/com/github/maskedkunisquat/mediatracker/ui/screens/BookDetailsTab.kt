package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.components.BOOK_COVER_ASPECT_RATIO
import com.github.maskedkunisquat.mediatracker.ui.components.CoverImage
import com.github.maskedkunisquat.mediatracker.ui.insets.barPadding
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.features.books.timer.ReadingTimerState

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
internal fun DetailsTab(
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
