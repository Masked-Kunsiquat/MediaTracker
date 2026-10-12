package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.features.tv.domain.WatchedWhen
import com.hub.media.features.tv.domain.isPickableDay
import com.hub.media.features.tv.domain.pickableUpToYear
import com.hub.media.features.tv.domain.watchedOnPickedDay
import com.hub.media.ui.PendingTick

/**
 * The "Mark … watched?" prompt every tick goes through (#131): Now, On air date, Pick a date…, or
 * Cancel, with an optional catch-up checkbox. Stateless apart from its own UI state (the checkbox
 * and whether the date picker is showing); what to write comes in as [tick] and goes out through
 * [onConfirm].
 *
 * - The choices are **stacked full-width buttons**: four in a row do not fit at a large font scale.
 * - "On air date" is shown only when it can be honoured for what would be written, so ticking the
 *   catch-up box can hide it ([PendingTick.airDateAvailableWithEarlier]).
 * - The date picker disables future days and opens on the episode's air date when there is one.
 *   Material's picker works in UTC-midnight millis; [watchedOnPickedDay] reads the day back in UTC,
 *   and turning it into a stored time is [com.hub.media.features.tv.domain.resolveWatchedAt]'s job.
 *   All date arithmetic lives in `:shared`, so this module needs no date library.
 */
@OptIn(ExperimentalMaterial3Api::class, kotlin.time.ExperimentalTime::class)
@Composable
fun MarkWatchedDialog(
    tick: PendingTick,
    onConfirm: (WatchedWhen, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var includeEarlier by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val airDateShown = if (includeEarlier) tick.airDateAvailableWithEarlier else tick.airDateAvailable

    if (picking) {
        val maxYear = pickableUpToYear()
        val state =
            rememberDatePickerState(
                initialSelectedDateMillis = tick.initialPickMillis,
                selectableDates =
                    object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long): Boolean = isPickableDay(utcTimeMillis)

                        override fun isSelectableYear(year: Int): Boolean = year <= maxYear
                    },
            )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            onConfirm(watchedOnPickedDay(millis), includeEarlier)
                        }
                    },
                ) { Text(stringResource(R.string.mark_watched_pick_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel_button)) }
            },
        ) { DatePicker(state = state) }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (tick.isSeason) {
                    stringResource(R.string.mark_watched_title_season, tick.targetIds.size, tick.seasonNumber)
                } else {
                    stringResource(
                        R.string.mark_watched_title_episode,
                        tick.title?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.up_next_untitled_format, tick.episodeNumber),
                    )
                },
            )
        },
        text = {
            if (tick.earlierIds.isNotEmpty()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = includeEarlier, role = Role.Checkbox) { includeEarlier = it },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeEarlier, onCheckedChange = null)
                    Text(
                        pluralStringResource(
                            R.plurals.mark_watched_include_earlier,
                            tick.earlierIds.size,
                            tick.earlierIds.size,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { onConfirm(WatchedWhen.Now, includeEarlier) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.mark_watched_now))
                }
                if (airDateShown) {
                    OutlinedButton(
                        onClick = { onConfirm(WatchedWhen.OnAirDate, includeEarlier) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.mark_watched_on_air_date)) }
                }
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.mark_watched_pick_date))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        },
    )
}
