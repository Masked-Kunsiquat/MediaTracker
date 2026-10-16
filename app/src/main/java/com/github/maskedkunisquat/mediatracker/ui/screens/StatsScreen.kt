package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.StatsViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.insets.scrollingContentPadding
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.features.stats.data.StatsPeriod
import com.hub.media.ui.AppContainer
import com.hub.media.ui.StatsUiState
import com.hub.media.ui.StatsViewModel

/**
 * Route-level composable for the stats screen (ROADMAP Task 5 Phase C).
 * Connects the [StatsViewModel] to the stateless [StatsScreen] and handles navigation.
 *
 * @param appContainer The dependency container for creating ViewModels.
 * @param onNavigateBack Callback to navigate back (TopAppBar back icon).
 */
@Composable
fun StatsScreenRoute(
    appContainer: AppContainer,
    onNavigateBack: () -> Unit,
) {
    val viewModel: StatsViewModel =
        viewModel(
            factory = StatsViewModelFactory(appContainer),
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    StatsScreen(
        uiState = uiState,
        onSelectPeriod = viewModel::selectPeriod,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * Stateless stats screen composable (AGENTS.md §5 State Hoisting).
 *
 * Renders [StatsUiState.isLoading] as a centered [CircularProgressIndicator], otherwise three
 * cards: "This week" and "This month" (each showing time read, session count, and pages read via
 * [PeriodCard]), and the current reading streak (via [StreakCard]).
 *
 * A `null` [StatsUiState.Period.timeReadSeconds]/[StatsUiState.Period.pagesRead] means no session
 * in the period has a known value at all (schema v2 — see that class's KDoc) and is rendered as
 * the [R.string.stats_unknown_value] marker ("—"), distinct from a real `0` (e.g. a session
 * logged with 0 pages read). [StatsUiState.Period.sessionCount] is always a real, non-negative
 * count, so it is never rendered with the unknown marker, including when it is legitimately `0`.
 *
 * @param uiState Current [StatsUiState].
 * @param onNavigateBack Called when the back icon is pressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    uiState: StatsUiState,
    onSelectPeriod: (StatsPeriod) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            // No padding here: the list below draws behind the bars and re-adds the space as
            // contentPadding. Padding this Box is what stops that happening.
            modifier =
                Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding),
        ) {
            if (uiState.isLoading) {
                // The spinner is not a scrolling container and has no reason to sit behind
                // anything, so it keeps real padding and centres in the content area rather than
                // in the window.
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag(TestTags.Stats.LIST),
                    contentPadding = scrollingContentPadding(innerPadding, PaddingValues(16.dp)),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        StatsPeriodRow(selected = uiState.period, onSelect = onSelectPeriod)
                    }
                    item {
                        PeriodCard(title = stringResource(uiState.period.cardTitle()), period = uiState.stats)
                    }
                    item {
                        StreakCard(streakDays = uiState.currentStreakDays)
                    }
                }
            }
        }
    }
}

/**
 * A single period ("This week"/"This month") stats card: time read, session count, and pages
 * read. See [StatsScreen]'s KDoc for the null-vs-zero rendering rule.
 */
@Composable
private fun PeriodCard(
    title: String,
    period: StatsUiState.Period,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text =
                    stringResource(
                        R.string.stats_time_read_label,
                        formatTimeReadOrUnknown(period.timeReadSeconds),
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.stats_sessions_label, period.sessionCount),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text =
                    stringResource(
                        R.string.stats_pages_read_label,
                        formatCountOrUnknown(period.pagesRead),
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.stats_books_finished_label, period.booksFinished),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Lifetime books-finished total (ROADMAP Task 6 Phase C) — see
 * [com.hub.media.features.stats.data.StatsRepository]'s "Books-finished stat" KDoc for why this
 * lifetime count is shown as its own card rather than folded into [PeriodCard]: it needs no
 * [com.hub.media.core.database.entities.BookDetailsEntity.finishedAt] timestamp and so is exact
 * from the moment schema v3 exists, unlike [StatsUiState.Period.booksFinished] which can only ever
 * reflect finishes recorded after this phase shipped.
 */

/**
 * The period picker (#91): a scrollable row of chips, in the library status filter's style. Chips
 * rather than a segmented control because five labels do not fit a phone's width as fixed segments,
 * least of all at a large font scale; a chip row scrolls instead.
 */
@Composable
private fun StatsPeriodRow(
    selected: StatsPeriod,
    onSelect: (StatsPeriod) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().testTag(TestTags.Stats.PERIODS),
    ) {
        items(StatsPeriod.entries) { period ->
            FilterChip(
                selected = period == selected,
                onClick = { onSelect(period) },
                label = { Text(stringResource(period.chipLabel())) },
            )
        }
    }
}

private fun StatsPeriod.chipLabel(): Int =
    when (this) {
        StatsPeriod.WEEK -> R.string.stats_period_week
        StatsPeriod.MONTH -> R.string.stats_period_month
        StatsPeriod.LAST_30_DAYS -> R.string.stats_period_30_days
        StatsPeriod.YEAR -> R.string.stats_period_year
        StatsPeriod.ALL_TIME -> R.string.stats_period_all
    }

private fun StatsPeriod.cardTitle(): Int =
    when (this) {
        StatsPeriod.WEEK -> R.string.stats_this_week_title
        StatsPeriod.MONTH -> R.string.stats_this_month_title
        StatsPeriod.LAST_30_DAYS -> R.string.stats_last_30_days_title
        StatsPeriod.YEAR -> R.string.stats_this_year_title
        StatsPeriod.ALL_TIME -> R.string.stats_all_time_title
    }

@Composable
private fun StreakCard(streakDays: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text =
                    if (streakDays > 0) {
                        pluralStringResource(R.plurals.stats_streak_days, streakDays, streakDays)
                    } else {
                        stringResource(R.string.stats_no_streak)
                    },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * Formats a period's known-duration total as `H:MM` (hours unpadded, minutes zero-padded), or
 * [R.string.stats_unknown_value] when `null` -- see [StatsScreen]'s KDoc on why `null` must never
 * be coerced to a real value here.
 */
@Composable
private fun formatTimeReadOrUnknown(totalSeconds: Long?): String {
    if (totalSeconds == null) return stringResource(R.string.stats_unknown_value)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return "%d:%02d".format(java.util.Locale.ROOT, hours, minutes)
}

/**
 * Formats a nullable aggregate count (e.g. pages read) as its decimal string, or
 * [R.string.stats_unknown_value] when `null`.
 */
@Composable
private fun formatCountOrUnknown(count: Int?): String {
    if (count == null) return stringResource(R.string.stats_unknown_value)
    return count.toString()
}

/** Preview of the stats screen while the initial aggregate queries are still loading. */
@Preview(showBackground = true)
@Composable
private fun StatsScreenLoadingPreview() {
    MediaTrackerTheme {
        StatsScreen(
            uiState = StatsUiState(isLoading = true),
            onSelectPeriod = {},
            onNavigateBack = {},
        )
    }
}

/** Preview of the stats screen on This month, with populated totals and an active streak. */
@Preview(showBackground = true)
@Composable
private fun StatsScreenPopulatedPreview() {
    MediaTrackerTheme {
        StatsScreen(
            uiState =
                StatsUiState(
                    isLoading = false,
                    period = StatsPeriod.MONTH,
                    stats = StatsUiState.Period(timeReadSeconds = 27_000, sessionCount = 15, pagesRead = 640),
                    currentStreakDays = 5,
                ),
            onSelectPeriod = {},
            onNavigateBack = {},
        )
    }
}

/**
 * Preview of the stats screen with no sessions logged: zero session counts, `null` time/pages
 * sums (unknown marker), and no current streak.
 */
@Preview(showBackground = true)
@Composable
private fun StatsScreenEmptyPreview() {
    MediaTrackerTheme {
        StatsScreen(
            uiState =
                StatsUiState(
                    isLoading = false,
                    stats = StatsUiState.Period(timeReadSeconds = null, sessionCount = 0, pagesRead = null),
                    currentStreakDays = 0,
                ),
            onSelectPeriod = {},
            onNavigateBack = {},
        )
    }
}
