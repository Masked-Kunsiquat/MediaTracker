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
import com.hub.media.core.database.entities.WatchThroughEntity
import com.hub.media.features.tv.data.RunState
import com.hub.media.features.tv.data.RunSummary

/**
 * What the show screen does with its watch-throughs (#231): start another, remove an empty one, rate
 * one, or close "Rate this watch-through?". One holder, as [BookReadActions] is for a book's reads.
 */
data class ShowRunActions(
    val onWatchAgain: () -> Unit,
    val onRemoveRun: (runId: String) -> Unit,
    val onRateRun: (runId: String, halves: Int?) -> Unit,
    val onDismissRatePrompt: () -> Unit,
)

/** A watch-through's state in the words the show's status chip already uses. */
@Composable
internal fun RunState.label(): String =
    stringResource(
        when (this) {
            RunState.NOT_STARTED -> R.string.library_filter_not_started
            RunState.WATCHING -> R.string.watch_status_watching
            RunState.FINISHED -> R.string.watch_status_watched
            RunState.UP_TO_DATE -> R.string.run_state_up_to_date
            RunState.ABANDONED -> R.string.library_filter_abandoned
        },
    )

/** The date line under a run's title: when it finished (or caught up), else when it started, else nothing. */
@OptIn(kotlin.time.ExperimentalTime::class)
@Composable
private fun runDateLine(
    state: RunState,
    summary: RunSummary?,
): String? =
    when {
        summary == null -> null
        state == RunState.FINISHED || state == RunState.UP_TO_DATE ->
            stringResource(R.string.run_finished_on, DATE_ONLY_FORMATTER.format(instantToLocalDateTime(summary.lastAt)))
        else ->
            stringResource(R.string.run_started_on, DATE_ONLY_FORMATTER.format(instantToLocalDateTime(summary.firstAt)))
    }

/**
 * The current watch-through (#231), above the seasons: "Watch-through #2 · Watching", its date line,
 * its stars, and -- for an empty run after #1 -- a menu with Remove this watch-through, the undo for a
 * mistaken Watch again. A show with one run shows this card too, so run #1 can be rated.
 */
@Composable
internal fun LatestRunCard(
    run: WatchThroughEntity,
    state: RunState,
    summary: RunSummary?,
    canRemove: Boolean,
    onRate: (Int?) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.run_number_state, run.number, state.label()),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    runDateLine(state, summary)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (canRemove) {
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.run_menu, run.number),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.run_remove)) },
                                onClick = {
                                    menuOpen = false
                                    onRemove()
                                },
                            )
                        }
                    }
                }
            }
            RunRatingRow(halves = run.rating, onRate = onRate)
        }
    }
}

/**
 * An earlier watch-through, folded (#231): "Watch-through #1 · Watched · <date>", its stars and its
 * episode count. Open, it lists what each season held in that run -- a summary, never a checklist, since
 * only the current run can be ticked.
 */
@Composable
internal fun FoldedRunCard(
    run: WatchThroughEntity,
    summary: RunSummary?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = if (run.abandoned) RunState.ABANDONED else RunState.FINISHED
    val title = stringResource(R.string.run_number_state, run.number, state.label())
    val dateLine = runDateLine(state, summary)
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel =
                        stringResource(if (expanded) R.string.run_collapse else R.string.run_expand, run.number),
                    onClick = onToggle,
                ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (dateLine != null) "$title · $dateLine" else title,
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RatingStars(halves = run.rating, starSize = 16.dp)
                    val episodes = summary?.episodes ?: 0
                    Text(
                        text = pluralStringResource(R.plurals.run_episodes_summary, episodes, episodes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (expanded) {
                    summary?.perSeason?.forEach { (season, count) ->
                        Text(
                            text = stringResource(R.string.run_season_summary, season, count),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
