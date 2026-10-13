package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.WatchLogEntity

/**
 * The episode sheet's content (#90): the episode, every time it was watched, and the actions on
 * them. **Stateless** -- it holds no picker and no sheet window; the route hosts it inside a
 * `ModalBottomSheet` and owns the "Watched again?" prompt and the Edit-date picker. That split is
 * what lets the Robolectric lane drive it at all (AGENTS.md §7).
 *
 * @param viewings Newest first, as [com.hub.media.core.database.dao.WatchLogDao.observeForEpisode]
 *   returns them. Empty means not watched: the sheet says so and offers the ordinary tick instead.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@Composable
fun EpisodeSheetContent(
    episode: EpisodeEntity,
    viewings: List<WatchLogEntity>,
    onTick: () -> Unit,
    onWatchedAgain: () -> Unit,
    onEditViewing: (WatchLogEntity) -> Unit,
    onDeleteViewing: (WatchLogEntity) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text =
                episode.title?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.up_next_untitled_format, episode.episodeNumber),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text =
                listOfNotNull(
                    stringResource(R.string.up_next_episode_code_format, episode.seasonNumber, episode.episodeNumber),
                    episode.airDate?.let { formatAirDay(it.toEpochMilliseconds()) },
                ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        if (viewings.isEmpty()) {
            Text(stringResource(R.string.episode_sheet_not_watched), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onTick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.episode_sheet_mark_watched))
            }
        } else {
            Text(stringResource(R.string.episode_sheet_viewings_title), style = MaterialTheme.typography.titleSmall)
            viewings.forEach { viewing ->
                val day = DATE_ONLY_FORMATTER.format(instantToLocalDateTime(viewing.watchedAt))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = day, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onEditViewing(viewing) }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.episode_sheet_edit_date_cd, day),
                        )
                    }
                    IconButton(onClick = { onDeleteViewing(viewing) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.episode_sheet_delete_viewing_cd, day),
                        )
                    }
                }
            }
            OutlinedButton(onClick = onWatchedAgain, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.episode_sheet_watched_again))
            }
        }
    }
}
