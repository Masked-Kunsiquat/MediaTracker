package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.annotation.PluralsRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.components.BarChart
import com.github.maskedkunisquat.mediatracker.ui.components.ChartDialog
import com.github.maskedkunisquat.mediatracker.ui.components.ChartTile
import com.github.maskedkunisquat.mediatracker.ui.components.RatingStars
import com.github.maskedkunisquat.mediatracker.ui.components.formatAverage
import com.github.maskedkunisquat.mediatracker.ui.components.formatStars
import com.hub.media.features.stats.data.RatingStats

/**
 * "Your ratings" (#232): the period's average and how many titles are rated, the top five, and the spread
 * as a chart tile that opens larger. Each title counts once, at its score for the period.
 *
 * @param summary The plural for "Average 4.1 · 12 films rated", naming the tab's medium.
 */
@Composable
internal fun RatingsCard(
    stats: RatingStats,
    @PluralsRes summary: Int,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val mostly = stringResource(R.string.stats_ratings_mostly, formatStars(stats.mostCommonHalves))
    // Whole stars only under the bars: ten labels run together at tile size.
    val starLabels = List(10) { if (it % 2 == 1) ((it + 1) / 2).toString() else "" }
    val spread = stats.spread.map { it.toLong() }
    Card(modifier = Modifier.fillMaxWidth().testTag(TestTags.Stats.RATINGS_CARD)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(R.string.stats_ratings_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = pluralStringResource(summary, stats.titles, formatAverage(stats.averageStars), stats.titles),
                style = MaterialTheme.typography.bodyMedium,
            )
            stats.top.forEachIndexed { index, title ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = "${index + 1}", style = MaterialTheme.typography.bodyMedium)
                    // Weighted and ellipsized, so a long title never pushes its stars off the card (#163).
                    Text(
                        text = title.title,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    RatingStars(halves = title.roundedHalves, starSize = 16.dp)
                    // The stars show halves; a score between them is printed too, so they never disagree.
                    if (title.showsExactScore) {
                        Text(text = formatAverage(title.stars), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChartTile(
                    caption = mostly,
                    description =
                        stringResource(
                            R.string.stats_tile_description,
                            stringResource(R.string.stats_chart_rating_spread),
                            mostly,
                            spreadDescription(stats.spread),
                        ),
                    openLabel = stringResource(R.string.stats_show_larger),
                    onOpen = { open = true },
                    modifier = Modifier.weight(1f),
                    tileModifier = Modifier.testTag(TestTags.Stats.RATING_SPREAD_CHART),
                ) {
                    BarChart(values = spread, labels = starLabels, description = "", barHeight = 92.dp)
                }
                // One tile keeps a tile's size, as an odd tile does in the charts card.
                Spacer(Modifier.weight(1f))
            }
        }
    }
    if (open) {
        ChartDialog(
            title = stringResource(R.string.stats_chart_rating_spread),
            closeLabel = stringResource(R.string.stats_close),
            onClose = { open = false },
        ) {
            BarChart(values = spread, labels = starLabels, description = "")
            stats.spread.withIndex().filter { it.value > 0 }.reversed().forEach { (index, count) ->
                BreakdownRow(
                    label = formatStars(index + 1) + "★",
                    values = listOf(pluralStringResource(R.plurals.stats_rating_spread_titles, count, count)),
                )
            }
        }
    }
}

/** "5★: 3 titles, 4.5★: 1 title": the filled bars, highest first, read aloud. */
@Composable
private fun spreadDescription(spread: List<Int>): String =
    spread
        .withIndex()
        .filter { it.value > 0 }
        .reversed()
        .map { (index, count) ->
            stringResource(
                R.string.stats_chart_entry,
                formatStars(index + 1) + "★",
                pluralStringResource(R.plurals.stats_rating_spread_titles, count, count),
            )
        }.joinToString()
