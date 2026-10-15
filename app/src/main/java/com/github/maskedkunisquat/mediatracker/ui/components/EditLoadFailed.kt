package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R

/**
 * What an edit screen shows when its item could not be read: a message, Retry and Go back.
 *
 * Shared by the book, film and show edit screens so the three fail the same way. Before it, a
 * database error while loading escaped the ViewModel and crashed the app (#173 review).
 */
@Composable
fun EditLoadFailed(
    onRetry: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.edit_load_failed),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onNavigateBack) { Text(stringResource(R.string.edit_load_back)) }
            Button(onClick = onRetry) { Text(stringResource(R.string.edit_load_retry)) }
        }
    }
}
