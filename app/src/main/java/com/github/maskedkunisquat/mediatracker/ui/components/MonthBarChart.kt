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
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import java.time.Month
import java.time.format.TextStyle

/**
 * Twelve bars, January first, one per month's count in [counts] (#219). Drawn with a plain [Canvas]
 * rather than a chart library: twelve rectangles do not justify a dependency (AGENTS.md section 5).
 *
 * A month with no finishes draws a thin baseline rather than nothing, so an empty month reads as
 * "none" and not as a gap in the chart. The tallest bar fills the height; the rest scale to it.
 *
 * The whole chart is one accessibility node: a bar has no meaning on its own to TalkBack, so the
 * description lists the months that have finishes ("January: 1 book, June: 2 books"), or says there
 * are none yet. Month names come from the device's locale.
 *
 * @param counts Exactly twelve counts, January first.
 */
@Composable
fun MonthBarChart(
    counts: List<Int>,
    modifier: Modifier = Modifier,
) {
    require(counts.size == 12) { "One count per month, got ${counts.size}" }
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
    val description =
        if (entries.isEmpty()) stringResource(R.string.stats_month_chart_empty) else entries.joinToString()
    val barColor = MaterialTheme.colorScheme.primary
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val max = counts.max().coerceAtLeast(1)

    Column(modifier = modifier.clearAndSetSemantics { contentDescription = description }) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(120.dp),
        ) {
            val slot = size.width / 12
            val barWidth = slot * 0.6f
            val baseline = 2.dp.toPx()
            counts.forEachIndexed { index, count ->
                val left = index * slot + (slot - barWidth) / 2
                if (count == 0) {
                    drawRect(baselineColor, Offset(left, size.height - baseline), Size(barWidth, baseline))
                } else {
                    val height = (size.height * count / max).coerceAtLeast(baseline * 2)
                    drawRect(barColor, Offset(left, size.height - height), Size(barWidth, height))
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            for (month in Month.entries) {
                Text(
                    text = month.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
