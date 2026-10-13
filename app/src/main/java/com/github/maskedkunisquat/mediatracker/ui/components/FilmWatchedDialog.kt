package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.features.tv.domain.WatchedWhen

/**
 * "When did you watch it?" for a film (#192): **Now** or **Pick a date**. Unlike an episode's
 * [MarkWatchedDialog] there is no air-date option -- a film stores only its release *year*.
 *
 * @param title The dialog's title, already formatted ("Watched Heat", "Watched Heat again").
 */
@Composable
fun FilmWatchedDialog(
    title: String,
    onConfirm: (WatchedWhen) -> Unit,
    onDismiss: () -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    if (picking) {
        WatchedDatePickerDialog(
            initialPickMillis = null,
            confirmLabel = stringResource(R.string.film_watched_pick_confirm),
            onPicked = onConfirm,
            onDismiss = { picking = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            TextButton(onClick = { onConfirm(WatchedWhen.Now) }) { Text(stringResource(R.string.film_watched_now)) }
        },
        dismissButton = {
            TextButton(onClick = { picking = true }) { Text(stringResource(R.string.film_watched_pick)) }
        },
    )
}
