package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.screens.DATE_ONLY_FORMATTER
import com.github.maskedkunisquat.mediatracker.ui.screens.instantToLocalDateTime
import com.hub.media.core.database.entities.WatchLogEntity

/**
 * One viewing in a watch history: its day, then Edit date and Delete. Shared by the episode sheet
 * (#90) and the movie detail History (#192), so the two render a viewing identically.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@Composable
fun ViewingRow(
    viewing: WatchLogEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    stars: (@Composable () -> Unit)? = null,
) {
    val day = DATE_ONLY_FORMATTER.format(instantToLocalDateTime(viewing.watchedAt))
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // [note] follows the day: which watch-through an episode's viewing belongs to (#231).
        Text(
            text = if (note != null) "$day · $note" else day,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        // A film viewing's own rating (#231), between its day and its actions.
        stars?.invoke()
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.episode_sheet_edit_date_cd, day))
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.episode_sheet_delete_viewing_cd, day),
            )
        }
    }
}
