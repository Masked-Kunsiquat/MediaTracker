package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import java.time.Month
import java.time.format.TextStyle

/**
 * One bar per value, each labelled underneath (#219). Drawn with a plain [Canvas] rather than a
 * chart library: a handful of rectangles does not justify a dependency (AGENTS.md section 5).
 *
 * A zero value draws a thin baseline rather than nothing, so an empty slot reads as "none" and not
 * as a gap. The tallest bar fills the height; the rest scale to it.
 *
 * The whole chart is one accessibility node carrying [description]: a bar means nothing on its own
 * to TalkBack, so callers describe the chart in words ("January: 1 book, June: 2 books").
 *
 * @param values One per bar, left to right; never negative.
 * @param labels One short label per bar, the same length as [values].
 */
@Composable
fun BarChart(
    values: List<Long>,
    labels: List<String>,
    description: String,
    modifier: Modifier = Modifier,
    barHeight: Dp = 120.dp,
) {
    require(values.size == labels.size) { "One label per bar: ${values.size} values, ${labels.size} labels" }
    val barColor = MaterialTheme.colorScheme.primary
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)

    Column(modifier = modifier.clearAndSetSemantics { contentDescription = description }) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(barHeight),
        ) {
            val slot = size.width / values.size
            val barWidth = slot * 0.6f
            val baseline = 2.dp.toPx()
            values.forEachIndexed { index, value ->
                val left = index * slot + (slot - barWidth) / 2
                if (value == 0L) {
                    drawRect(baselineColor, Offset(left, size.height - baseline), Size(barWidth, baseline))
                } else {
                    val height = (size.height * value / max).coerceAtLeast(baseline * 2)
                    drawRect(barColor, Offset(left, size.height - height), Size(barWidth, height))
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            for (label in labels) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The Year view's books finished per month (#219): twelve [BarChart] bars, January first, labelled
 * with each month's initial. TalkBack hears the months that have finishes ("January: 1 book, June: 2
 * books"), or that there are none yet. Month names come from the device's locale.
 *
 * @param counts Exactly twelve counts, January first.
 */
@Composable
fun MonthChart(
    counts: List<Int>,
    modifier: Modifier = Modifier,
) {
    require(counts.size == 12) { "One count per month, got ${counts.size}" }
    val locale = LocalConfiguration.current.locales[0]
    BarChart(
        values = counts.map { it.toLong() },
        labels = Month.entries.map { it.getDisplayName(TextStyle.NARROW, locale) },
        description = monthsDescription(counts),
        modifier = modifier,
    )
}

/**
 * The months with something, read aloud ("January: 1 book, June: 2 books"), or that there is
 * nothing yet. Shared by [MonthChart] and its tile (#217).
 */
@Composable
fun monthsDescription(counts: List<Int>): String {
    val locale = LocalConfiguration.current.locales[0]
    val entries =
        counts.mapIndexedNotNull { index, count ->
            if (count == 0) {
                null
            } else {
                val month = Month.of(index + 1).getDisplayName(TextStyle.FULL, locale)
                pluralStringResource(R.plurals.stats_month_chart_entry, count, month, count)
            }
        }
    return if (entries.isEmpty()) stringResource(R.string.stats_month_chart_empty) else entries.joinToString()
}
