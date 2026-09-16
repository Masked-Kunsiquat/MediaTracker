@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.components.BOOK_COVER_ASPECT_RATIO
import com.github.maskedkunisquat.mediatracker.ui.components.CoverImage
import com.github.maskedkunisquat.mediatracker.ui.components.DetailArtwork
import com.github.maskedkunisquat.mediatracker.ui.components.DetailHeader
import com.github.maskedkunisquat.mediatracker.ui.components.DetailStatus
import com.github.maskedkunisquat.mediatracker.ui.components.DetailStatusChip
import com.github.maskedkunisquat.mediatracker.ui.insets.barPadding
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.features.books.timer.ReadingTimerState
import kotlin.time.Instant

/**
 * Details tab content (books-polish pass revamp; originally ROADMAP Task 6 Phase D). Replaces the
 * former single stack of prefix-string [Text] rows ("Released: …", "ISBN: …", "Format: …") with a
 * considered hierarchy, top to bottom:
 * 1. [BookDetailHeaderSection] -- the shared [DetailHeader] (#141 step 3): cover, title/subline,
 *    rating and the status chip, in the same shape Film and TV use.
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
 * [MetadataCard] is wrapped in its own [SelectionContainer] so ISBN/format/etc. text stays
 * long-press selectable/copyable, carrying its own [DisableSelection] carve-out around the ISBN
 * copy button exactly as before. [BookDetailHeaderSection] is **not** wrapped (#141 step 3) --
 * [DetailHeader] is the same shared component Film and TV render unwrapped, and wrapping only
 * Book's copy of it would mean auditing gesture conflicts (the artwork's tap-to-enlarge, the status
 * chip's dropdown) against long-press-to-select that Film/TV never had to solve either.
 * [TimerCard]'s live elapsed-time readout -- which changes every second while running -- stays
 * outside any [SelectionContainer] as before.
 */
@Composable
internal fun DetailsTab(
    book: MediaItemEntity,
    details: BookDetailsEntity?,
    currentProgress: Double?,
    coverStorageDir: String,
    timerState: ReadingTimerState,
    elapsedSeconds: Long,
    onStartReading: () -> Unit,
    onPauseReading: () -> Unit,
    onResumeReading: () -> Unit,
    onStopReading: () -> Unit,
    onStatusChange: (ReadingStatus) -> Unit,
    onCopyIsbn: (String) -> Unit,
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
        BookDetailHeaderSection(
            book = book,
            details = details,
            coverStorageDir = coverStorageDir,
            onStatusChange = onStatusChange,
        )

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
 * Book's header (#141 step 3): the shared [DetailHeader] -- kind + release year, title, authors as
 * the subline, community rating out of 10 (not TMDB's exclusive scale -- see
 * [MediaItemEntity.communityRating]'s KDoc, and the correction on #141's step-3 decision comment
 * that a book is not out of 5), and [bookStatusControl] as the status slot -- plus the tap-to-enlarge
 * gesture on the artwork, which is Book's own divergence and why [DetailHeader.onArtworkClick]
 * exists. "Re-fetch cover" is **not** here: it moved to the top bar's overflow menu
 * ([BookDetailScreen]'s `TopAppBar`), out of the cover's long-press, per #141 step 3.
 */
@Composable
internal fun BookDetailHeaderSection(
    book: MediaItemEntity,
    details: BookDetailsEntity?,
    coverStorageDir: String,
    onStatusChange: (ReadingStatus) -> Unit,
) {
    var showEnlargedCover by remember { mutableStateOf(false) }

    DetailHeader(
        kind = stringResource(R.string.book_detail_kind),
        year = book.releaseYear,
        title = book.title,
        subline = details?.authors?.takeUnless { it.isBlank() },
        rating = book.communityRating,
        artwork =
            book.coverImageHash?.let { hash ->
                DetailArtwork(coverStorageDir = coverStorageDir, coverImageHash = hash, mediaType = MediaType.BOOK)
            },
        statusNote = finishedNote(details?.status, details?.finishedAt),
        onArtworkClick = { showEnlargedCover = true },
        artworkClickLabel = stringResource(R.string.cover_view_action_label),
    ) {
        DetailStatusChip(bookStatusControl(details?.status, onStatusChange))
    }

    if (showEnlargedCover) {
        EnlargedCoverDialog(
            coverStorageDir = coverStorageDir,
            coverImageHash = book.coverImageHash,
            onDismiss = { showEnlargedCover = false },
        )
    }
}

/**
 * Reading-progress section (books-polish pass), promoted above [TimerCard] on [DetailsTab] since
 * progress is "the thing the user checks most" (ROADMAP revamp brief) -- previously a single
 * `progress_prefix` body-text row buried in the old header's metadata stack.
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
 * Full-size cover view (books-polish pass), opened by tapping [BookDetailHeaderSection]'s artwork
 * (#141 step 3 moved tap-to-enlarge onto the shared [DetailHeader]'s `onArtworkClick`; this dialog
 * itself is unchanged). Rendered with [ContentScale.Fit] (never [ContentScale.Crop]) so the whole
 * cover is visible, sized to [BOOK_COVER_ASPECT_RATIO] within most of the screen's width
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
 * Book's status mapper (#141 step 3): editable over [ReadingStatus.entries], selecting the
 * already-current status is a harmless no-op re-application (matches
 * [com.hub.media.features.books.data.BookRepository.updateReadingStatus]'s own "re-saving the same
 * FINISHED status preserves finishedAt" behavior). No "Status:" prefix, unlike the old `StatusChip`
 * this replaces -- [DetailStatusChip]'s label is the status alone, the same as film's and (for its
 * read-only case) TV's.
 */
@Composable
internal fun bookStatusControl(
    status: ReadingStatus?,
    onStatusChange: (ReadingStatus) -> Unit,
): DetailStatus.Editable<ReadingStatus> =
    DetailStatus.Editable(
        value = status ?: ReadingStatus.TO_READ,
        options = ReadingStatus.entries,
        label = { it.displayLabel() },
        onSelect = onStatusChange,
        onClickLabel = stringResource(R.string.detail_status_change_action_label),
    )

/**
 * "Finished <date>" for a FINISHED book with a recorded [finishedAt], `null` otherwise -- mirrors
 * [MovieDetailScreen]'s `watchedNote` (same [DATE_ONLY_FORMATTER], same status-gates-the-date shape,
 * since a book moved away from FINISHED and back could otherwise show a stale date -- see
 * [BookDetailsEntity.finishedAt]'s KDoc for when it is cleared).
 */
@Composable
internal fun finishedNote(
    status: ReadingStatus?,
    finishedAt: Instant?,
): String? {
    if (status != ReadingStatus.FINISHED || finishedAt == null) return null
    val date = DATE_ONLY_FORMATTER.format(instantToLocalDateTime(finishedAt))
    return stringResource(R.string.book_detail_finished_note, date)
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
