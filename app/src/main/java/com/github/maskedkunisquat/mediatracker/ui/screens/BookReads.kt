package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.components.RatingStars
import com.github.maskedkunisquat.mediatracker.ui.components.RunRatingRow
import com.github.maskedkunisquat.mediatracker.ui.text.formatDuration
import com.hub.media.core.database.entities.ReadThroughEntity
import com.hub.media.core.database.entities.ReadingSessionEntity
import com.hub.media.core.database.entities.ReadingStatus

/**
 * What the book detail screen does with a book's reads (#230): start a re-read, remove an empty one,
 * rate one, or close "Rate this read?". One holder so the screen's already long parameter list grows by
 * one, and every caller -- the route, the tests, the previews -- must still supply each action.
 */
data class BookReadActions(
    val onReadAgain: () -> Unit,
    val onRemoveRead: (readId: String) -> Unit,
    val onRateRead: (readId: String, halves: Int?) -> Unit,
    val onDismissRatePrompt: () -> Unit,
    /** "Rate" beside the status: reopens "Rate this read?" for the latest read (#231 review). */
    val onRequestRate: () -> Unit,
)

/** "Read #2 · Finished", the status in a read's own words. */
@Composable
private fun readTitle(read: ReadThroughEntity): String =
    stringResource(R.string.read_number_status, read.number, read.status.displayLabel())

/**
 * The date line under a read's title: the finish date (the title already says Finished, and the header's own
 * note says "Finished <date>" for the latest read), or when it started.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@Composable
private fun readDateLine(read: ReadThroughEntity): String? {
    val finished = read.finishedAt
    val started = read.startedAt
    return when {
        read.status == ReadingStatus.FINISHED && finished != null ->
            DATE_ONLY_FORMATTER.format(instantToLocalDateTime(finished))
        started != null ->
            stringResource(
                R.string.read_started_on,
                DATE_ONLY_FORMATTER.format(instantToLocalDateTime(started)),
            )
        else -> null
    }
}

/**
 * A read shown open (#230): its title and dates, its stars to set or change its rating, and -- for an
 * empty re-read -- a menu with Remove this read, the undo for a mistaken Read again. Its sessions follow
 * below it, from the history's timeline.
 */
@Composable
internal fun ReadHeaderCard(
    read: ReadThroughEntity,
    canRemove: Boolean,
    onRate: (Int?) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    onCollapse: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = readTitle(read),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    readDateLine(read)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onCollapse != null) {
                    IconButton(onClick = onCollapse) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.read_collapse, read.number),
                        )
                    }
                }
                if (canRemove) {
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.read_menu, read.number),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.read_remove)) },
                                onClick = {
                                    menuOpen = false
                                    onRemove()
                                },
                            )
                        }
                    }
                }
            }
            RunRatingRow(halves = read.rating, onRate = onRate)
        }
    }
}

/**
 * An earlier read, folded to one card (#230): its title and dates, its stars, and how many sessions and
 * how long it took. Tapping it opens it in the history.
 */
@Composable
internal fun FoldedReadCard(
    read: ReadThroughEntity,
    sessions: List<ReadingSessionEntity>,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = readTitle(read)
    val dateLine = readDateLine(read)
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .clickable(onClickLabel = stringResource(R.string.read_expand, read.number), onClick = onExpand),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (dateLine != null) "$title · $dateLine" else title,
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RatingStars(halves = read.rating, starSize = 16.dp)
                    Text(
                        text =
                            pluralStringResource(
                                R.plurals.read_sessions_summary,
                                sessions.size,
                                sessions.size,
                                formatDuration(sessions.sumOf { it.durationSeconds ?: 0L }),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
