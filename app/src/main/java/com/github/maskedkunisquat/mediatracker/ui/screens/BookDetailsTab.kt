@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
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
import com.github.maskedkunisquat.mediatracker.ui.components.DetailProgressCard
import com.github.maskedkunisquat.mediatracker.ui.components.DetailStatus
import com.github.maskedkunisquat.mediatracker.ui.components.DetailStatusChip
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.features.books.timer.ReadingTimerState
import kotlin.math.roundToInt
import kotlin.time.Instant

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
 * Reading-progress section (#141 step 3), using the shared [DetailProgressCard] wherever a
 * fraction is derivable -- the same card show's episode progress uses -- while still preserving
 * Book's own two degraded cases, which [DetailProgressCard] cannot express (its own "renders
 * nothing" guard is for a show with zero episodes *tracked*, not for "no progress logged yet," and
 * it always draws a bar, which a bare page number has no denominator for):
 * - No session ever logged ([currentProgress] null): a muted "nothing to show yet" message, no bar
 *   ([ProgressPlaceholderCard]).
 * - Page mode with no known [totalPages]: the formatted text (a bare page number) with no bar,
 *   since there is no denominator to visualize a fraction of ([ProgressPlaceholderCard] again, in
 *   its emphasized style).
 * - Otherwise (a fraction is derivable): [DetailProgressCard] itself. [completed] is clamped to
 *   [total] -- unlike a show's watched-episode count, [currentProgress] is user-entered and not
 *   validated against [totalPages] at save time, so an overshoot must not draw a bar past 100%
 *   (mirrors [progressFraction]'s own `coerceIn(0f, 1f)`, which this replaces as the fraction
 *   source but must still honour).
 */
@Composable
internal fun BookProgressSection(
    currentProgress: Double?,
    totalPages: Int?,
    trackingMode: TrackingMode?,
) {
    if (currentProgress == null) {
        ProgressPlaceholderCard(text = stringResource(R.string.detail_progress_not_started), emphasize = false)
        return
    }
    val value = formatProgress(currentProgress, totalPages, trackingMode) ?: return
    val fraction = progressFraction(currentProgress, totalPages, trackingMode)
    if (fraction == null) {
        ProgressPlaceholderCard(text = value, emphasize = true)
        return
    }
    val total = if (trackingMode == TrackingMode.PAGES && totalPages != null) totalPages else PERCENT_TOTAL
    val completed = currentProgress.roundToInt().coerceIn(0, total)
    DetailProgressCard(value = value, completed = completed, total = total)
}

/** The denominator [BookProgressSection] uses for [TrackingMode.PERCENT] (and `null`) books. */
private const val PERCENT_TOTAL = 100

/**
 * [BookProgressSection]'s two text-only degraded cases, drawn in [DetailProgressCard]'s own card
 * shell (shape, colour, padding) so they read as the same component even though no bar is drawn.
 * [emphasize] selects [DetailProgressCard]'s value styling (bold, `headlineSmall`, primary colour)
 * for a real-but-unbarred progress value, or a muted `bodyMedium` line for "nothing logged yet".
 */
@Composable
private fun ProgressPlaceholderCard(
    text: String,
    emphasize: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.detail_progress_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = text,
                style = if (emphasize) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyMedium,
                fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
                color =
                    if (emphasize) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
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
 * Compact reading-timer row (#141 step 3), replacing the old full-height `TimerCard`: "Reading
 * timer" and the elapsed time on the left, [timerState]'s action buttons on the right, in one row
 * instead of stacked -- on a single scrolling page the old card's full height would sit between
 * progress and reading history and push the history far down. Same gating, same callbacks, same
 * strings as before: [ReadingTimerState.Idle] shows Start; [ReadingTimerState.Running] shows Pause
 * + Stop; [ReadingTimerState.Paused] shows Resume + Stop. Stays a `primaryContainer` card so it
 * still reads as *the* primary action on the page.
 */
@Composable
internal fun BookTimerRow(
    timerState: ReadingTimerState,
    elapsedSeconds: Long,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.timer_card_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = formatElapsed(elapsedSeconds),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            // One filled button per state, so the row's primary action carries the weight the old
            // full-width TimerCard gave it; Stop stays secondary beside it.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (timerState) {
                    is ReadingTimerState.Idle -> {
                        Button(onClick = onStart) {
                            Text(stringResource(R.string.start_reading_button))
                        }
                    }
                    is ReadingTimerState.Running -> {
                        TextButton(onClick = onStop) {
                            Text(stringResource(R.string.stop_button))
                        }
                        Button(onClick = onPause) {
                            Text(stringResource(R.string.pause_button))
                        }
                    }
                    is ReadingTimerState.Paused -> {
                        TextButton(onClick = onStop) {
                            Text(stringResource(R.string.stop_button))
                        }
                        Button(onClick = onResume) {
                            Text(stringResource(R.string.resume_button))
                        }
                    }
                }
            }
        }
    }
}
