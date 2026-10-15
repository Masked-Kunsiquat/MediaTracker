package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

/**
 * One secondary action in a detail screen's "More options" menu (#172).
 *
 * @property supportingText Shown under the label when present, e.g. why a disabled action is
 *   unavailable.
 * @property inProgress The action is running: it is disabled and shows a small progress indicator.
 */
data class DetailMenuAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val supportingText: String? = null,
    val inProgress: Boolean = false,
)

/**
 * The top-bar actions every detail screen shares (#172): Edit as an icon, then a "More options" menu
 * holding [secondary] actions and, always last and in the error colour, Delete.
 *
 * Delete lives in the menu rather than beside Edit because it is the one destructive action on these
 * screens; one tap away beside harmless ones, only its confirmation dialog stood between a mis-tap
 * and losing an item. [onDelete] is the screen's existing "show the confirmation dialog", unchanged.
 *
 * The caller shows these only once the item has loaded -- there is nothing to edit or delete before
 * then -- so this composable has no loading state of its own. Choosing any item closes the menu
 * before its action runs, so the menu never sits over the dialog an action opens.
 */
@Composable
fun DetailTopBarActions(
    editContentDescription: String,
    onEdit: () -> Unit,
    secondary: List<DetailMenuAction>,
    deleteLabel: String,
    onDelete: () -> Unit,
) {
    IconButton(onClick = onEdit) {
        Icon(Icons.Filled.Edit, contentDescription = editContentDescription)
    }
    Box {
        var expanded by rememberSaveable { mutableStateOf(false) }
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag(TestTags.DetailTopBar.OVERFLOW)) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.detail_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            secondary.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(action.label)
                            action.supportingText?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    // Always a 24dp slot -- empty, or the spinner -- so every item's text lines up
                    // with Delete's (which has an icon) and does not shift when progress appears.
                    leadingIcon = {
                        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            if (action.inProgress) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        }
                    },
                    enabled = action.enabled && !action.inProgress,
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                )
            }
            if (secondary.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    expanded = false
                    onDelete()
                },
                modifier = Modifier.testTag(TestTags.DetailTopBar.DELETE),
            )
        }
    }
}
