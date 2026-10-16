package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.features.settings.data.MAX_READING_GOAL
import com.hub.media.ui.filterIntegerInput
import com.hub.media.ui.parseRequiredInt

/**
 * Sets this year's reading goal (#219): how many books, 1 to [MAX_READING_GOAL]. Opened from the
 * stats screen's goal card. Save stays disabled until the field holds a number in range, so a goal
 * of zero -- which would read as "reached" on 1 January -- cannot be saved. Remove is offered only
 * when [currentGoal] exists.
 *
 * edge-to-edge-exempt: an [AlertDialog] is its own window and repositions itself for the keyboard,
 * so the stats screen's `Scaffold` needs no `contentWindowInsets` for this field. Kept in its own
 * file so the screen file holds no text field at all.
 *
 * @param year The year the goal applies to, for the title.
 * @param currentGoal The goal already set, pre-filled; `null` when there is none.
 */
@Composable
fun ReadingGoalDialog(
    year: Int,
    currentGoal: Int?,
    onSave: (Int) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(currentGoal?.toString().orEmpty()) }
    val books = parseRequiredInt(text)?.takeIf { it in 1..MAX_READING_GOAL }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stats_goal_dialog_title, year)) },
        text = {
            OutlinedTextField(
                value = text,
                // Three digits at most: the largest goal accepted is 999.
                onValueChange = { text = it.filterIntegerInput().take(MAX_READING_GOAL.toString().length) },
                label = { Text(stringResource(R.string.stats_goal_dialog_field)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { books?.let(onSave) }, enabled = books != null) {
                Text(stringResource(R.string.save_button))
            }
        },
        dismissButton = {
            if (currentGoal != null) {
                TextButton(onClick = onRemove) { Text(stringResource(R.string.stats_goal_remove)) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
        },
    )
}
