package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.text.formatDuration
import com.hub.media.core.database.entities.MediaType
import com.hub.media.features.stats.data.TitleTime
import com.hub.media.ui.ViewingStats

/*
 * The Films and Shows tabs (#217): the period card, the charts and, for Shows, the top shows. Split
 * out of StatsScreen.kt by region (AGENTS.md section 6). Every number comes from one
 * [com.hub.media.features.stats.data.ViewingBreakdown], so a tab's cards always agree.
 */

/**
 * What was watched in the period. Time watched is unknown ("—") when no viewing's film or episode
 * has a runtime -- never 0 -- and names the viewings left out of it when only some do.
 */
@Composable
internal fun ViewingPeriodCard(
    title: String,
    domain: MediaType,
    stats: ViewingStats,
) {
    val numbers = stats.breakdown
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            val watched =
                when {
                    domain == MediaType.TV_SHOW ->
                        pluralStringResource(
                            R.plurals.stats_episodes_watched,
                            numbers.titles,
                            numbers.viewings,
                            numbers.titles,
                        )
                    numbers.rewatches > 0 ->
                        pluralStringResource(
                            R.plurals.stats_films_watched_rewatches,
                            numbers.rewatches,
                            numbers.viewings,
                            numbers.rewatches,
                        )
                    else -> stringResource(R.string.stats_films_watched, numbers.viewings)
                }
            Text(text = watched, style = MaterialTheme.typography.bodyMedium)
            val time =
                when {
                    numbers.knownSeconds == null -> stringResource(R.string.stats_unknown_value)
                    numbers.unknownRuntime > 0 ->
                        stringResource(
                            R.string.stats_time_with_unknown,
                            formatDuration(numbers.knownSeconds!!),
                            numbers.unknownRuntime,
                        )
                    else -> formatDuration(numbers.knownSeconds!!)
                }
            Text(
                text = stringResource(R.string.stats_time_watched_label, time),
                style = MaterialTheme.typography.bodyMedium,
            )
            // Shows on All time only: a show has no "finished on" date.
            stats.showsFinished?.let {
                Text(
                    text = stringResource(R.string.stats_shows_finished_label, it),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** The tab's charts as tiles, counting viewings rather than time (a runtime may be unknown). */
@Composable
internal fun ViewingChartsCard(
    domain: MediaType,
    stats: ViewingStats,
) {
    val numbers = stats.breakdown
    ChartsCard(
        data =
            ChartsData(
                hours = numbers.hours.map { it.toLong() },
                dayParts = numbers.dayParts.map { it.toLong() },
                weekdays = numbers.weekdays,
                weekdayValues = numbers.weekdayCounts.map { it.toLong() },
                months = numbers.months,
                formatValue = { pluralStringResource(R.plurals.stats_viewings_count, it.toInt(), it.toInt()) },
            ),
        whenTitle = stringResource(R.string.stats_when_watch_title),
        monthsTitle =
            stringResource(
                if (domain == MediaType.TV_SHOW) R.string.stats_episodes_by_month else R.string.stats_films_by_month,
            ),
        emptyText = stringResource(R.string.stats_nothing_watched),
        // Picked dates count by day but not on the clock; say how many, so the dial is not a mystery.
        footnote =
            if (numbers.untimed > 0) {
                pluralStringResource(R.plurals.stats_untimed, numbers.untimed, numbers.untimed)
            } else {
                null
            },
    )
}

/** Up to five shows by known time watched; a long title is cut short so the time stays on the row. */
@Composable
internal fun TopShowsCard(titles: List<TitleTime>) {
    Card(modifier = Modifier.fillMaxWidth().testTag(TestTags.Stats.TOP_SHOWS_CARD)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = stringResource(R.string.stats_top_shows_title), style = MaterialTheme.typography.titleMedium)
            if (titles.isEmpty()) {
                Text(text = stringResource(R.string.stats_nothing_watched), style = MaterialTheme.typography.bodyMedium)
            }
            for (title in titles) {
                BreakdownRow(label = title.title, values = listOf(formatDuration(title.seconds)))
            }
        }
    }
}
