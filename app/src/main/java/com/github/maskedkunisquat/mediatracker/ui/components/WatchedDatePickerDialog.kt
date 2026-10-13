package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.features.tv.domain.WatchedWhen
import com.hub.media.features.tv.domain.isPickableDay
import com.hub.media.features.tv.domain.pickableUpToYear
import com.hub.media.features.tv.domain.watchedOnPickedDay

/**
 * The "when did you watch it" date picker (#131), shared by [MarkWatchedDialog]'s "Pick a date…"
 * and the episode sheet's "Edit date" (#90).
 *
 * Future days are not selectable. Material's picker works in UTC-midnight millis: [initialPickMillis]
 * is one (an episode's air date, or a viewing's own date), and the chosen day is read back in UTC by
 * [watchedOnPickedDay]. Turning that day into a stored time -- noon local, clamped to now -- is
 * [com.hub.media.features.tv.domain.resolveWatchedAt]'s job, so every caller stores the same thing.
 */
@OptIn(ExperimentalMaterial3Api::class, kotlin.time.ExperimentalTime::class)
@Composable
fun WatchedDatePickerDialog(
    initialPickMillis: Long?,
    confirmLabel: String,
    onPicked: (WatchedWhen) -> Unit,
    onDismiss: () -> Unit,
) {
    val maxYear = pickableUpToYear()
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = initialPickMillis,
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean = isPickableDay(utcTimeMillis)

                    override fun isSelectableYear(year: Int): Boolean = year <= maxYear
                },
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = { state.selectedDateMillis?.let { millis -> onPicked(watchedOnPickedDay(millis)) } },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
        },
    ) { DatePicker(state = state) }
}
