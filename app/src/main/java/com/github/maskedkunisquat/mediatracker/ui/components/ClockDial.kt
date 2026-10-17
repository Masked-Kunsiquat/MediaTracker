package com.github.maskedkunisquat.mediatracker.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A 24-hour heat dial (#217): one wedge per hour, midnight at the top, clockwise, each shaded by how
 * much happened in that hour against the busiest hour. Drawn on a plain [Canvas] in the theme's own
 * colours, with no chart library (AGENTS.md section 5).
 *
 * An empty hour is a faint wedge rather than a gap, so the ring always reads as a whole clock. The
 * centre names [busiest] (e.g. "Evenings"). The whole dial is one accessibility node carrying
 * [description]: shading means nothing to TalkBack, so the caller describes it in words and shows
 * the numbers in a legend beside it.
 *
 * @param hours Exactly 24 values, midnight first; never negative.
 * @param use24Hour Quarter labels as "00 06 12 18" rather than "12a 6a 12p 6p". Defaults to the
 *   phone's own clock setting.
 * @param mini The ring alone, for a [ChartTile]: no hour labels and no centre text, since the
 *   tile's caption names the busiest part.
 */
@Composable
fun ClockDial(
    hours: List<Long>,
    busiest: String,
    description: String,
    modifier: Modifier = Modifier,
    use24Hour: Boolean = DateFormat.is24HourFormat(LocalContext.current),
    mini: Boolean = false,
) {
    require(hours.size == 24) { "One value per hour, got ${hours.size}" }
    val filled = MaterialTheme.colorScheme.primary
    // A tint of the text colour rather than a surface colour: the card behind is itself a surface
    // colour, so an empty hour drawn in one disappears and the ring looks broken.
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    val max = hours.max().coerceAtLeast(1L)
    val quarters = if (use24Hour) listOf("00", "06", "12", "18") else listOf("12a", "6a", "12p", "6p")

    Box(
        modifier = modifier.fillMaxWidth().aspectRatio(1f).clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val labelRoom = if (mini) 0f else 22.dp.toPx()
            val outer = min(size.width, size.height) / 2 - labelRoom
            val thickness = outer * 0.34f
            val radius = outer - thickness / 2
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            val gap = 1.6f
            hours.forEachIndexed { hour, value ->
                // Canvas angles start at 3 o'clock; -90 puts midnight at the top.
                val start = -90f + hour * 15f + gap / 2
                val color =
                    if (value == 0L) {
                        empty
                    } else {
                        // A floor, so the quietest real hour is still visibly more than nothing.
                        filled.copy(alpha = 0.22f + 0.78f * value / max)
                    }
                drawArc(
                    color = color,
                    startAngle = start,
                    sweepAngle = 15f - gap,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = thickness),
                )
            }
            if (!mini) {
                quarters.forEachIndexed { index, label ->
                    val angle = Math.toRadians(-90.0 + index * 90.0)
                    val at = outer + labelRoom / 2
                    val layout = measurer.measure(label, labelStyle)
                    drawText(
                        layout,
                        topLeft =
                            Offset(
                                center.x + (at * cos(angle)).toFloat() - layout.size.width / 2,
                                center.y + (at * sin(angle)).toFloat() - layout.size.height / 2,
                            ),
                    )
                }
            }
        }
        if (!mini) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = busiest, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(
                    text = stringResource(R.string.stats_busiest),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val previewHours =
    listOf<Long>(
        1_200,
        300,
        0,
        0,
        0,
        0,
        0,
        600,
        900,
        300,
        0,
        0,
        1_500,
        2_400,
        1_800,
        600,
        0,
        1_200,
        2_700,
        4_200,
        5_400,
        3_600,
        2_400,
        1_800,
    )

@Composable
private fun ClockDialPreviewCard(
    hours: List<Long>,
    busiest: String,
) {
    Card(modifier = Modifier.padding(16.dp)) {
        ClockDial(
            hours = hours,
            busiest = busiest,
            description = "",
            modifier = Modifier.padding(16.dp),
            use24Hour = false,
        )
    }
}

/** A busy evening, a lunchtime bump and an empty early morning (#217 preview). */
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ClockDialPreview() {
    MediaTrackerTheme(darkTheme = false, dynamicColor = false) {
        ClockDialPreviewCard(previewHours, "Evenings")
    }
}

@Preview(showBackground = true, widthDp = 360, backgroundColor = 0xFF101418)
@Composable
private fun ClockDialDarkPreview() {
    MediaTrackerTheme(darkTheme = true, dynamicColor = false) {
        ClockDialPreviewCard(previewHours, "Evenings")
    }
}

/** A single viewing at 21:00: one wedge, the rest an empty ring. */
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ClockDialSparsePreview() {
    MediaTrackerTheme(darkTheme = false, dynamicColor = false) {
        ClockDialPreviewCard(List(24) { if (it == 21) 1L else 0L }, "Nights")
    }
}
