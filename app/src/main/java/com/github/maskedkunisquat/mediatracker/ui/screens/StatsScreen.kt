package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.StatsViewModelFactory
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
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
    val viewModel: StatsViewModel = viewModel(
        factory = StatsViewModelFactory(appContainer),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    StatsScreen(
        uiState = uiState,
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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        PeriodCard(
                            title = stringResource(R.string.stats_this_week_title),
                            period = uiState.week,
                        )
                    }
                    item {
                        PeriodCard(
                            title = stringResource(R.string.stats_this_month_title),
                            period = uiState.month,
                        )
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(
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
                text = stringResource(
                    R.string.stats_pages_read_label,
                    formatCountOrUnknown(period.pagesRead),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Current reading streak card. A `0`-day streak renders [R.string.stats_no_streak] ("No current
 * streak") rather than "0 day streak" -- see
 * [com.hub.media.features.stats.data.StatsRepository.observeReadingStreak] for the exact streak
 * rule. A positive streak renders via the [R.plurals.stats_streak_days] plurals resource.
 */
@Composable
private fun StreakCard(streakDays: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (streakDays > 0) {
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
            onNavigateBack = {},
        )
    }
}

/** Preview of the stats screen with populated week/month totals and an active streak. */
@Preview(showBackground = true)
@Composable
private fun StatsScreenPopulatedPreview() {
    MediaTrackerTheme {
        StatsScreen(
            uiState = StatsUiState(
                isLoading = false,
                week = StatsUiState.Period(timeReadSeconds = 5_400, sessionCount = 4, pagesRead = 120),
                month = StatsUiState.Period(timeReadSeconds = 27_000, sessionCount = 15, pagesRead = 640),
                currentStreakDays = 5,
            ),
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
            uiState = StatsUiState(
                isLoading = false,
                week = StatsUiState.Period(timeReadSeconds = null, sessionCount = 0, pagesRead = null),
                month = StatsUiState.Period(timeReadSeconds = null, sessionCount = 0, pagesRead = null),
                currentStreakDays = 0,
            ),
            onNavigateBack = {},
        )
    }
}
