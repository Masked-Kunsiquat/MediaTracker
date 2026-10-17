package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.text.formatDuration
import com.hub.media.features.stats.data.AuthorTime
import com.hub.media.features.stats.data.DayPart
import com.hub.media.features.stats.data.FormatTime
import kotlin.math.roundToInt

/*
 * Books stats part 2 (#219): the cards built from one read of the period's timed sessions -- top
 * authors and formats. When you read moved into [ChartsCard]'s tiles (#217). Split out of StatsScreen.kt by region (AGENTS.md section
 * 6). All three show the same one-line empty state when the period has no timed reading, rather than
 * empty charts or lists.
 */

/** Up to five authors by time read; a long name is cut short so the time stays on the row. */
@Composable
internal fun TopAuthorsCard(
    authors: List<AuthorTime>,
    isEmpty: Boolean,
) {
    BreakdownCard(
        title = stringResource(R.string.stats_authors_title),
        isEmpty = isEmpty,
        tag = TestTags.Stats.AUTHORS_CARD,
    ) {
        if (authors.isEmpty()) {
            // Time was read, but none of those books has an author on record.
            Text(text = stringResource(R.string.stats_no_authors), style = MaterialTheme.typography.bodyMedium)
        }
        for (author in authors) {
            BreakdownRow(label = author.name, values = listOf(formatDuration(author.seconds)))
        }
    }
}

/** Each format with time: how long, and its share of the period's timed reading. */
@Composable
internal fun FormatsCard(
    formats: List<FormatTime>,
    totalSeconds: Long,
) {
    BreakdownCard(
        title = stringResource(R.string.stats_formats_title),
        isEmpty = totalSeconds == 0L,
        tag = TestTags.Stats.FORMATS_CARD,
    ) {
        for (format in formats) {
            val percent = (format.seconds * 100.0 / totalSeconds).roundToInt()
            BreakdownRow(
                label = format.format.displayLabel(),
                values =
                    listOf(
                        formatDuration(format.seconds),
                        shareLabel(percent, hasPart = true, hasRest = format.seconds < totalSeconds),
                    ),
            )
        }
    }
}

@Composable
private fun BreakdownCard(
    title: String,
    isEmpty: Boolean,
    tag: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (isEmpty) {
                Text(
                    text = stringResource(R.string.stats_no_timed_reading),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                content()
            }
        }
    }
}

@Composable
internal fun BreakdownRow(
    label: String,
    values: List<String>,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        for (value in values) {
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * A share as a whole percent, never rounding a sliver away: "<1%" when there is some of it but it
 * rounds to 0, ">99%" when it rounds to 100 but something else remains. Without that, a format read
 * for a minute shows "0%" and the format beside it "100%" (#219 review).
 */
@Composable
internal fun shareLabel(
    percent: Int,
    hasPart: Boolean,
    hasRest: Boolean,
): String =
    when {
        percent == 0 && hasPart -> stringResource(R.string.stats_percent_under_one)
        percent == 100 && hasRest -> stringResource(R.string.stats_percent_over_99)
        else -> stringResource(R.string.stats_percent, percent)
    }

internal fun DayPart.label(): Int =
    when (this) {
        DayPart.MORNING -> R.string.stats_daypart_morning
        DayPart.AFTERNOON -> R.string.stats_daypart_afternoon
        DayPart.EVENING -> R.string.stats_daypart_evening
        DayPart.NIGHT -> R.string.stats_daypart_night
    }
