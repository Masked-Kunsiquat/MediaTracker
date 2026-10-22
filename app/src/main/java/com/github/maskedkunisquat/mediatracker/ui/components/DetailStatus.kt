package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.style.TextOverflow
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
        /** An action listed below the statuses, e.g. a book's Read again (#230); `null` for none. */
        val menuAction: StatusAction? = null,
        /** A button beside the chip -- a book's Rate, once its latest read ends unrated (#231 review). */
        val sideAction: StatusAction? = null,
    ) : DetailStatus

    /**
     * @param label The derived status, already resolved to display text by the domain mapper.
     * @param action An optional side action (e.g. show's Abandon/Resume), rendered as a [TextButton]
     *   beside the chip. `null` omits it.
     */
    data class ReadOnly(
        val label: String,
        val action: StatusAction? = null,
        /** More actions beside [action] -- a show's Watch again and Rate (#231). */
        val moreActions: List<StatusAction> = emptyList(),
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
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StatusDropdownChip(
            value = status.value,
            options = status.options,
            label = status.label,
            onSelect = status.onSelect,
            onClickLabel = status.onClickLabel,
            modifier = Modifier.weight(1f, fill = false),
            menuAction = status.menuAction,
        )
        status.sideAction?.let { side ->
            TextButton(onClick = side.onClick) {
                Text(side.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A chip in [StatusDropdownChip]'s shape and colours, but not one: no dropdown arrow, and `enabled =
 * false` so a press does not ripple as though something were about to happen. The disabled colours
 * are mapped to the enabled ones, because this chip is not a muted control -- it is a label the
 * design draws identically either way.
 *
 * [Modifier.clearAndSetSemantics] then leaves only [text]: not the click action, and not the disabled
 * state either, which would have TalkBack announce a status display as an unavailable control. It
 * also re-adds the label, since clearing drops the child [Text]'s own semantics with everything else.
 *
 * When [DetailStatus.ReadOnly.action] exists, it renders as a [TextButton] beside the chip -- the
 * chip first, per #141's step-2 decision (Abandon/Resume sits next to the read-only chip rather than
 * as a full-width button further down the screen).
 *
 * A [FlowRow], not a Row (#231 review): with [DetailStatus.ReadOnly.moreActions] a show can carry
 * Abandon, Watch again and Rate at once, and in one Row at a large font the buttons -- measured first --
 * crushed the chip to nothing. Here whatever does not fit starts a new line.
 */
@Composable
private fun ReadOnlyStatusChip(
    status: DetailStatus.ReadOnly,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        itemVerticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AssistChip(
            onClick = {},
            enabled = false,
            label = {
                Text(status.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            colors =
                AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    disabledLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            border = null,
            // No weight: in a FlowRow a long label takes a line of its own and ellipsizes there,
            // rather than squeezing the actions beside it (#163).
            modifier =
                Modifier.clearAndSetSemantics {
                    text = AnnotatedString(status.label)
                },
        )
        if (status.action != null) {
            TextButton(onClick = status.action.onClick) {
                Text(status.action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        status.moreActions.forEach { extra ->
            TextButton(onClick = extra.onClick) {
                Text(extra.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
