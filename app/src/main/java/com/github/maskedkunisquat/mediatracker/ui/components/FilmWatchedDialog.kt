package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.hub.media.features.tv.domain.WatchedWhen

/**
 * "When did you watch it?" for a film (#192): **Now** or **Pick a date**. Unlike an episode's
 * [MarkWatchedDialog] there is no air-date option -- a film stores only its release *year*.
 *
 * When [rateable] (#231) it also offers stars, optional: the viewing is saved with them, or unrated if
 * none were chosen. The film's own screen asks; Add and Edit keep the plain dialog, and a film added as
 * Watched can be rated from its History afterwards.
 *
 * @param title The dialog's title, already formatted ("Watched Heat", "Watched Heat again").
 */
@Composable
fun FilmWatchedDialog(
    title: String,
    onConfirm: (WatchedWhen, rating: Int?) -> Unit,
    onDismiss: () -> Unit,
    rateable: Boolean = false,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var halves by rememberSaveable { mutableStateOf<Int?>(null) }
    if (picking) {
        WatchedDatePickerDialog(
            initialPickMillis = null,
            confirmLabel = stringResource(R.string.film_watched_pick_confirm),
            onPicked = { onConfirm(it, halves) },
            onDismiss = { picking = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text =
            if (rateable) {
                {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RatingStars(
                            halves = halves,
                            starSize = 40.dp,
                            onRate = { halves = it },
                            modifier = Modifier.testTag(TestTags.MovieDetail.WATCHED_STARS),
                        )
                        Text(
                            text = halves?.let { formatStars(it) }.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            } else {
                null
            },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(WatchedWhen.Now, halves) },
            ) { Text(stringResource(R.string.film_watched_now)) }
        },
        dismissButton = {
            TextButton(onClick = { picking = true }) { Text(stringResource(R.string.film_watched_pick)) }
        },
    )
}
