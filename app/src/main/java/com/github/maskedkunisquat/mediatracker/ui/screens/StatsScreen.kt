package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import com.github.maskedkunisquat.mediatracker.ui.components.MonthBarChart
import com.github.maskedkunisquat.mediatracker.ui.components.ReadingGoalDialog
import com.github.maskedkunisquat.mediatracker.ui.insets.scrollingContentPadding
import com.github.maskedkunisquat.mediatracker.ui.text.formatDuration
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.features.stats.data.GoalProgress
import com.hub.media.features.stats.data.GoalStatus
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
        onSetGoal = viewModel::setGoal,
        onClearGoal = viewModel::clearGoal,
        onNavigateBack = onNavigateBack,
    )
}

/**
 * Stateless stats screen composable (AGENTS.md §5 State Hoisting).
 *
 * Renders [StatsUiState.isLoading] as a centered [CircularProgressIndicator]; otherwise the period
 * picker ([StatsPeriodRow]), one [PeriodCard] for the chosen [StatsUiState.period], and the current
 * reading streak ([StreakCard]) (#91). Since #219 the Year view adds a [MonthChartCard], and every
 * period shows this year's reading goal ([GoalCard]), which opens [ReadingGoalDialog] when tapped.
 *
 * A `null` [StatsUiState.Period.timeReadSeconds]/[StatsUiState.Period.pagesRead] means no session
 * in the period has a known value at all (schema v2 — see that class's KDoc) and is rendered as
 * the [R.string.stats_unknown_value] marker ("—"), distinct from a real `0` (e.g. a session
 * logged with 0 pages read). [StatsUiState.Period.sessionCount] is always a real, non-negative
 * count, so it is never rendered with the unknown marker, including when it is legitimately `0`.
 *
 * @param uiState Current [StatsUiState].
 * @param onNavigateBack Called when the back icon is pressed.
 *
 * @param onSelectPeriod Called with the chip the user picked; the view model remembers it.
 * @param onSetGoal Called with the books the user saved in [ReadingGoalDialog] (#219).
 * @param onClearGoal Called when the user removes the goal in [ReadingGoalDialog].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    uiState: StatsUiState,
    onSelectPeriod: (StatsPeriod) -> Unit,
    onSetGoal: (Int) -> Unit,
    onClearGoal: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    // Whether the goal dialog is open is the screen's own concern; the goal itself is the view model's.
    var showGoalDialog by rememberSaveable { mutableStateOf(false) }
    if (showGoalDialog && !uiState.isLoading) {
        ReadingGoalDialog(
            year = uiState.goalYear,
            currentGoal = uiState.goal?.goal,
            onSave = {
                onSetGoal(it)
                showGoalDialog = false
            },
            onRemove = {
                onClearGoal()
                showGoalDialog = false
            },
            onDismiss = { showGoalDialog = false },
        )
    }
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
                    uiState.finishesByMonth?.let { months ->
                        item {
                            MonthChartCard(months)
                        }
                    }
                    item {
                        GoalCard(goal = uiState.goal, goalYear = uiState.goalYear, onClick = { showGoalDialog = true })
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
 * The chosen period's card: time read, sessions, pages read and books finished. See
 * [StatsScreen]'s KDoc for the null-vs-zero rendering rule.
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
            Text(
                text =
                    stringResource(
                        R.string.stats_speed_label,
                        period.pagesPerHour?.let { stringResource(R.string.stats_pages_per_hour, it) }
                            ?: stringResource(R.string.stats_unknown_value),
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text =
                    stringResource(
                        R.string.stats_average_session_label,
                        formatTimeReadOrUnknown(period.averageSessionSeconds),
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text =
                    stringResource(
                        R.string.stats_longest_session_label,
                        formatTimeReadOrUnknown(period.longestSessionSeconds),
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** The Year view's chart of books finished each month (#219). */
@Composable
private fun MonthChartCard(months: List<Int>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(R.string.stats_month_chart_title), style = MaterialTheme.typography.titleMedium)
            MonthBarChart(counts = months, modifier = Modifier.testTag(TestTags.Stats.MONTH_CHART))
        }
    }
}

/**
 * This year's reading goal (#219): an offer to set one, or progress against it and whether it is on
 * pace. The whole card is the control that opens [ReadingGoalDialog], whichever state it is in.
 */
@Composable
private fun GoalCard(
    goal: GoalProgress?,
    goalYear: Int,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(TestTags.Stats.GOAL_CARD),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (goal == null) {
                Text(
                    text = stringResource(R.string.stats_goal_set_prompt, goalYear),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(text = stringResource(R.string.stats_goal_set_hint), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    text = stringResource(R.string.stats_goal_title, goalYear),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = pluralStringResource(R.plurals.stats_goal_progress, goal.goal, goal.finished, goal.goal),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { (goal.finished.toFloat() / goal.goal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text =
                        when (val status = goal.status) {
                            GoalStatus.OnPace -> stringResource(R.string.stats_goal_on_pace)
                            GoalStatus.Reached -> stringResource(R.string.stats_goal_reached)
                            is GoalStatus.Behind ->
                                pluralStringResource(R.plurals.stats_goal_behind, status.books, status.books)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

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
    // On a narrow phone the last chips start off the edge, so a remembered All time opened with its
    // own chip hidden. The selected chip asks to be brought into view once, on first composition:
    // that scrolls only if it is not already fully visible, and only as far as needed, so a row
    // that already shows it is left alone and later taps never make it jump.
    val bringSelectedIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) {
        // After the first frame: before the row is laid out there is nothing to scroll.
        withFrameNanos { }
        bringSelectedIntoView.bringIntoView()
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        // One choice, not five toggles: a FilterChip alone announces as an independent checkbox.
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .selectableGroup()
                .testTag(TestTags.Stats.PERIODS),
    ) {
        StatsPeriod.entries.forEach { period ->
            FilterChip(
                selected = period == selected,
                onClick = { onSelect(period) },
                label = { Text(stringResource(period.chipLabel())) },
                modifier =
                    if (period == selected) {
                        Modifier.bringIntoViewRequester(bringSelectedIntoView)
                    } else {
                        Modifier
                    },
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
 * Formats a known duration as "22h 48m" ([formatDuration], #219), or
 * [R.string.stats_unknown_value] when `null` -- see [StatsScreen]'s KDoc on why `null` must never
 * be coerced to a real value here.
 */
@Composable
private fun formatTimeReadOrUnknown(totalSeconds: Long?): String {
    if (totalSeconds == null) return stringResource(R.string.stats_unknown_value)
    return formatDuration(totalSeconds)
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
            onSetGoal = {},
            onClearGoal = {},
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
            onSetGoal = {},
            onClearGoal = {},
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
            onSetGoal = {},
            onClearGoal = {},
            onNavigateBack = {},
        )
    }
}
