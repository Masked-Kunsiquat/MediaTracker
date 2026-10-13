package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The watched control everywhere an episode or season is ticked: a big check-circle in the spirit
 * of TV Time's, replacing Material's 18dp checkbox. A filled disc with a check when [checked]; an
 * outlined ring with a faint check when not, so the tap target reads as one even when empty.
 *
 * **Still a checkbox to everything that is not a pair of eyes:** `Role.Checkbox`, toggleable on/off
 * state and [contentDescription] all sit on the one 48dp node, so accessibility services and every
 * test that found the old checkbox find this. The visible disc is 36dp inside a 48dp touch target.
 *
 * Tapping always reports the *requested* state through [onCheckedChange]; whether that applies at
 * once or opens the "Mark … watched?" prompt (#131) is the caller's decision.
 */
@Composable
fun WatchedCircle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (checked) colors.primary else Color.Transparent, label = "watchedFill")
    val tick by animateColorAsState(
        if (checked) colors.onPrimary else colors.outline.copy(alpha = 0.6f),
        label = "watchedTick",
    )
    val ring = if (checked) colors.primary else colors.outline
    Box(
        modifier =
            modifier
                .size(48.dp)
                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
                .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(fill)
                    .border(2.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = tick,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
