package com.github.maskedkunisquat.mediatracker.ui.screens

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.components.BarChart
import com.github.maskedkunisquat.mediatracker.ui.components.ChartDialog
import com.github.maskedkunisquat.mediatracker.ui.components.ChartTile
import com.github.maskedkunisquat.mediatracker.ui.components.ClockDial
import com.github.maskedkunisquat.mediatracker.ui.components.MonthChart
import com.github.maskedkunisquat.mediatracker.ui.components.monthsDescription
import com.hub.media.features.stats.data.DayPart
import java.time.DayOfWeek
import java.time.format.TextStyle

/**
 * Everything [ChartsCard] draws, in one shape for every tab (#217): Books passes seconds, Films and
 * Shows pass viewings, and [formatValue] turns a value into words ("2h 0m", "12 viewings").
 *
 * @property hours 24 values, midnight first, for the clock dial.
 * @property dayParts 4 values in [DayPart] order, for the dial's legend and its busiest part.
 * @property weekdays ISO day numbers from the week start; [weekdayValues] follows that order.
 * @property months Twelve counts on the Year view, `null` otherwise.
 */
internal data class ChartsData(
    val hours: List<Long>,
    val dayParts: List<Long>,
    val weekdays: List<Int>,
    val weekdayValues: List<Long>,
    val months: List<Int>?,
    val formatValue: @Composable (Long) -> String,
)

private enum class OpenChart { TIME_OF_DAY, WEEKDAYS, MONTHS }

/**
 * Every chart of a tab as small square tiles, two to a row, each opening larger when tapped (#217,
 * owner-approved renders). The dial shows only when something has a real time; the weekdays only
 * when something happened; the months only on Year. With no tile at all it shows [emptyText].
 *
 * @param whenTitle The time-of-day dialog's title ("When you read").
 * @param monthsTitle The by-month dialog's title ("Books finished by month").
 */
@Composable
internal fun ChartsCard(
    data: ChartsData,
    whenTitle: String,
    monthsTitle: String,
    emptyText: String,
) {
    var open by rememberSaveable { mutableStateOf<OpenChart?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    val showLarger = stringResource(R.string.stats_show_larger)
    val partNames = DayPart.entries.map { stringResource(it.label()) }
    val busiestPart = DayPart.entries[data.dayParts.indices.maxBy { data.dayParts[it] }]
    val busiestPartName = stringResource(busiestPart.pluralLabel())
    val dayNames = data.weekdays.map { DayOfWeek.of(it).getDisplayName(TextStyle.FULL, locale) }
    val busiestDay =
        dayNames
            .getOrNull(
                data.weekdayValues.indices.maxByOrNull { data.weekdayValues[it] } ?: -1,
            ).orEmpty()

    val tiles = mutableListOf<@Composable (Modifier) -> Unit>()
    if (data.hours.sum() > 0) {
        tiles += { modifier ->
            ChartTile(
                caption = busiestPartName,
                description =
                    stringResource(
                        R.string.stats_tile_description,
                        stringResource(R.string.stats_chart_time_of_day),
                        busiestPartName,
                        chartDescription(partNames, data.dayParts, data.formatValue),
                    ),
                openLabel = showLarger,
                onOpen = { open = OpenChart.TIME_OF_DAY },
                modifier = modifier,
                tileModifier = Modifier.testTag(TestTags.Stats.DAYPART_CHART),
            ) {
                ClockDial(hours = data.hours, busiest = busiestPartName, description = "", mini = true)
            }
        }
    }
    if (data.weekdayValues.sum() > 0) {
        tiles += { modifier ->
            ChartTile(
                caption = busiestDay,
                description =
                    stringResource(
                        R.string.stats_tile_description,
                        stringResource(R.string.stats_chart_weekdays),
                        busiestDay,
                        chartDescription(dayNames, data.weekdayValues, data.formatValue),
                    ),
                openLabel = showLarger,
                onOpen = { open = OpenChart.WEEKDAYS },
                modifier = modifier,
                tileModifier = Modifier.testTag(TestTags.Stats.WEEKDAY_CHART),
            ) {
                BarChart(
                    values = data.weekdayValues,
                    labels = data.weekdays.map { DayOfWeek.of(it).getDisplayName(TextStyle.NARROW, locale) },
                    description = "",
                    barHeight = 92.dp,
                )
            }
        }
    }
    data.months?.let { months ->
        tiles += { modifier ->
            ChartTile(
                caption = stringResource(R.string.stats_chart_by_month),
                description =
                    stringResource(
                        R.string.stats_tile_description,
                        monthsTitle,
                        stringResource(R.string.stats_chart_by_month),
                        monthsDescription(months),
                    ),
                openLabel = showLarger,
                onOpen = { open = OpenChart.MONTHS },
                modifier = modifier,
                tileModifier = Modifier.testTag(TestTags.Stats.MONTH_CHART),
            ) {
                BarChart(
                    values = months.map { it.toLong() },
                    // Quarter markers only: twelve initials run together at tile size.
                    labels = List(12) { if (it % 3 == 0) monthInitial(it, locale) else "" },
                    description = "",
                    barHeight = 92.dp,
                )
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth().testTag(TestTags.Stats.CHARTS_CARD)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(R.string.stats_charts_title), style = MaterialTheme.typography.titleMedium)
            if (data.hours.sum() == 0L && data.weekdayValues.sum() == 0L) {
                Text(text = emptyText, style = MaterialTheme.typography.bodyMedium)
            }
            for (pair in tiles.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair[0](Modifier.weight(1f))
                    // An odd tile keeps its size; its neighbour's slot stays empty.
                    if (pair.size == 2) pair[1](Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    when (open) {
        OpenChart.TIME_OF_DAY ->
            ChartDialog(
                title = whenTitle,
                closeLabel = stringResource(R.string.stats_close),
                onClose = { open = null },
            ) {
                ClockDial(hours = data.hours, busiest = busiestPartName, description = "")
                for ((index, name) in partNames.withIndex()) {
                    BreakdownRow(label = name, values = listOf(data.formatValue(data.dayParts[index])))
                }
            }
        OpenChart.WEEKDAYS ->
            ChartDialog(
                title = stringResource(R.string.stats_chart_weekdays),
                closeLabel = stringResource(R.string.stats_close),
                onClose = { open = null },
            ) {
                BarChart(
                    values = data.weekdayValues,
                    labels = data.weekdays.map { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale) },
                    description = "",
                )
                for ((index, name) in dayNames.withIndex()) {
                    BreakdownRow(label = name, values = listOf(data.formatValue(data.weekdayValues[index])))
                }
            }
        OpenChart.MONTHS ->
            data.months?.let { months ->
                ChartDialog(title = monthsTitle, closeLabel = stringResource(R.string.stats_close), onClose = {
                    open =
                        null
                }) {
                    MonthChart(counts = months)
                }
            }
        null -> Unit
    }
}

/** "Morning: 2h 10m, Evening: 5h 30m": the slots with something, read aloud. */
@Composable
private fun chartDescription(
    labels: List<String>,
    values: List<Long>,
    formatValue: @Composable (Long) -> String,
): String =
    labels
        .zip(values)
        .filter { (_, value) -> value > 0 }
        .map { (label, value) -> stringResource(R.string.stats_chart_entry, label, formatValue(value)) }
        .joinToString()

private fun monthInitial(
    index: Int,
    locale: java.util.Locale,
): String =
    java.time.Month
        .of(index + 1)
        .getDisplayName(TextStyle.NARROW, locale)

private fun DayPart.pluralLabel(): Int =
    when (this) {
        DayPart.MORNING -> R.string.stats_daypart_mornings
        DayPart.AFTERNOON -> R.string.stats_daypart_afternoons
        DayPart.EVENING -> R.string.stats_daypart_evenings
        DayPart.NIGHT -> R.string.stats_daypart_nights
    }
