package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/**
 * Per-domain status chip model (#141 step 2). Screens stop hand-wiring [StatusDropdownChip]
 * directly and instead build one of these through a domain mapper (e.g. film's `movieStatusControl`,
 * show's `showStatusControl`), so what a status *means* for a domain lives in one place rather than
 * inside a screen.
 *
 * - [Editable]: a dropdown over that domain's own options, rendered as today's [StatusDropdownChip]
 *   unchanged -- film and book (step 3) both offer a picker, since their stored status is the real
 *   answer.
 * - [ReadOnly]: a label with an optional side action, for a domain whose status is *derived* rather
 *   than picked -- a show's place on the shelf comes from [com.hub.media.ui.LibraryStatusFilter.ofShow],
 *   not from a four-way choice the header could offer and the library would then ignore.
 */
sealed interface DetailStatus {
    /**
     * @param value The currently selected status, shown as the chip's label via [label].
     * @param options Every selectable status, in menu order.
     * @param label Renders a status as its display string.
     * @param onSelect Invoked with the tapped option.
     * @param onClickLabel The accessibility action label for opening the menu, e.g. "Change status".
     */
    data class Editable<T>(
        val value: T,
        val options: List<T>,
        val label: @Composable (T) -> String,
        val onSelect: (T) -> Unit,
        val onClickLabel: String,
    ) : DetailStatus

    /**
     * @param label The derived status, already resolved to display text by the domain mapper.
     * @param action An optional side action (e.g. show's Abandon/Resume), rendered as a [TextButton]
     *   beside the chip. `null` omits it.
     */
    data class ReadOnly(
        val label: String,
        val action: StatusAction? = null,
    ) : DetailStatus

    /** One [ReadOnly] side action: its label and what tapping it does. */
    data class StatusAction(
        val label: String,
        val onClick: () -> Unit,
    )
}

/**
 * Renders a [DetailStatus] of either shape -- the one composable [DetailHeader]'s `statusControl`
 * slot needs, regardless of which domain built the model.
 */
@Composable
fun DetailStatusChip(
    status: DetailStatus,
    modifier: Modifier = Modifier,
) {
    when (status) {
        is DetailStatus.Editable<*> -> EditableStatusChip(status, modifier)
        is DetailStatus.ReadOnly -> ReadOnlyStatusChip(status, modifier)
    }
}

/** Breaks out [DetailStatus.Editable]'s captured type parameter so [StatusDropdownChip] can be called. */
@Composable
private fun <T> EditableStatusChip(
    status: DetailStatus.Editable<T>,
    modifier: Modifier,
) {
    StatusDropdownChip(
        value = status.value,
        options = status.options,
        label = status.label,
        onSelect = status.onSelect,
        onClickLabel = status.onClickLabel,
        modifier = modifier,
    )
}

/**
 * A chip in [StatusDropdownChip]'s shape and colours, but not one: no dropdown arrow, and its own
 * click handler is a no-op that [Modifier.clearAndSetSemantics] then strips out entirely, rather than
 * merely disabling it -- disabling would also mute [AssistChip]'s colours, which the design keeps
 * identical between the editable and read-only chip. [text] re-adds the label so it is still
 * announced, since `clearAndSetSemantics` drops the child [Text]'s own merged semantics along with
 * the click action.
 *
 * When [DetailStatus.ReadOnly.action] exists, it renders as a [TextButton] beside the chip -- the
 * chip first, per #141's step-2 decision (Abandon/Resume sits next to the read-only chip rather than
 * as a full-width button further down the screen).
 */
@Composable
private fun ReadOnlyStatusChip(
    status: DetailStatus.ReadOnly,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AssistChip(
            onClick = {},
            label = { Text(status.label) },
            colors =
                AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            border = null,
            modifier =
                Modifier.clearAndSetSemantics {
                    text = AnnotatedString(status.label)
                },
        )
        if (status.action != null) {
            TextButton(onClick = status.action.onClick) {
                Text(status.action.label)
            }
        }
    }
}
