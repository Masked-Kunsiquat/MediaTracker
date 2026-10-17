package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * A chart in miniature (#217): a square tile showing only the chart's shape, with a one-line
 * [caption] underneath ("Evenings", "Sundays"). Tapping it calls [onOpen], which shows the same chart
 * larger, with its numbers, in a [ChartDialog]. Tiles sit two to a row, so Stats shows the shape of
 * every chart at a glance without each one taking a screen's height.
 *
 * @param description The tile in words, its numbers included: the one thing TalkBack reads.
 * @param openLabel What a tap does, for TalkBack ("Show larger").
 * @param tileModifier Applied to the tappable square itself, e.g. its test tag.
 */
@Composable
fun ChartTile(
    caption: String,
    description: String,
    openLabel: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    tileModifier: Modifier = Modifier,
    chart: @Composable () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The tag goes first: clearAndSetSemantics clears everything after it, tags included.
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier =
                tileModifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
                    // One node for TalkBack: the chart's shape means nothing read bar by bar.
                    .clearAndSetSemantics {
                        contentDescription = description
                        role = Role.Button
                        onClick(label = openLabel) {
                            onOpen()
                            true
                        }
                    },
        ) {
            Box(modifier = Modifier.padding(10.dp), contentAlignment = Alignment.Center) { chart() }
        }
        Text(
            text = caption,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The larger view of a [ChartTile] (#217): the chart at full width, its legend, and a close button.
 * A plain [Dialog] rather than a bottom sheet: it holds no text field, so it is testable in the
 * Robolectric lane (Graphban `m_6729e5145c`).
 */
@Composable
fun ChartDialog(
    title: String,
    closeLabel: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onClose) { ChartDialogContent(title, closeLabel, onClose, content) }
}

/** [ChartDialog]'s body, separate so previews and goldens can draw it without a window. */
@Composable
fun ChartDialogContent(
    title: String,
    closeLabel: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            content()
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text(closeLabel) }
        }
    }
}
