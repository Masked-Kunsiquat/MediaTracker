@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.insets.barPadding
import com.github.maskedkunisquat.mediatracker.ui.insets.plus
import com.github.maskedkunisquat.mediatracker.ui.text.formatUnit
import com.hub.media.core.database.entities.ReadingSessionEntity

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
internal fun ReadingHistoryTab(
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
 * [formatSessionDate]/[ManualSessionDialog]'s edit-mode prefill already use, so the book detail
 * screen has exactly one Instant-to-local-day conversion rather than one per caller. (That helper
 * sits in `BookDetailFormatting.kt` since #81 split this screen up; it was in the same file when
 * this was written.)
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
internal fun DeleteSessionConfirmationDialog(
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
