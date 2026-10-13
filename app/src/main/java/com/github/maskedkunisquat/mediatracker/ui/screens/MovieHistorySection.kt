package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.components.ViewingRow
import com.hub.media.core.database.entities.WatchLogEntity

/**
 * A film's watch history on its detail screen (#192): every viewing, newest first, each with Edit
 * date and Delete, and **Watched again** while the film is Watched. Stateless -- the route owns the
 * prompts and the Undo snackbars.
 *
 * @param isWatched Whether the film's status is Watched; a rewatch of anything else is a status change.
 */
@Composable
internal fun MovieHistorySection(
    viewings: List<WatchLogEntity>,
    isWatched: Boolean,
    onWatchedAgain: () -> Unit,
    onEditViewing: (WatchLogEntity) -> Unit,
    onDeleteViewing: (WatchLogEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.movie_history_title), style = MaterialTheme.typography.titleMedium)
        if (viewings.isEmpty()) {
            Text(
                stringResource(R.string.movie_history_not_watched),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        viewings.forEach { viewing ->
            ViewingRow(viewing = viewing, onEdit = { onEditViewing(viewing) }, onDelete = { onDeleteViewing(viewing) })
        }
        if (isWatched) {
            OutlinedButton(onClick = onWatchedAgain, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.episode_sheet_watched_again))
            }
        }
    }
}
