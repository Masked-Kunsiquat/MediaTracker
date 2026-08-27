package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.screens.LogViewerScreen
import com.hub.media.core.storage.LogEntry
import com.hub.media.core.util.LogLevel
import com.hub.media.ui.LogViewerUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Navigation-bar occlusion guard for the log viewer.
 *
 * No keyboard counterpart: this screen has no text field -- [androidx.compose.foundation.text.selection.SelectionContainer]
 * offers selection, not entry -- so an IME rule would render the identical layout and assert
 * nothing beyond what this test already does.
 *
 * Sixty entries (well past a realistic single-refresh window) with a
 * [LogViewerUiState.newEntryBoundary] partway through, so the fixture renders the same shape a real
 * open-then-refresh session does -- direction hint, divider, entries, "new entries" marker, end
 * marker -- rather than the short single-entry list [LogViewerScreenTest] uses for its own
 * behavioural assertions, which does not reach far enough down the screen for this harness's
 * positive control to accept (see [Occlusion]'s KDoc).
 *
 * Like [StatsScreenOcclusionTest], every entry row here is a plain, non-clickable
 * [androidx.compose.material3.Text] -- Refresh and Export are this screen's only controls, and both
 * are fixed in the `TopAppBar`, nowhere near the bar. This asserts the same invariant every screen
 * in this lane asserts, ready to catch a future per-entry control (e.g. a tap-to-copy row) the
 * moment one is added, exactly as [LogViewerScreenTest]'s own KDoc explains this lane exists for
 * geometry a semantics-only test cannot see.
 */
@RunWith(RobolectricTestRunner::class)
class LogViewerScreenOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withTheNavigationBarShowing_theLogEntriesStayReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheNavigationBar { Fixture() }
    }

    @Composable
    private fun Fixture() {
        LogViewerScreen(
            uiState = LogViewerUiState(entries = SAMPLE_ENTRIES, newEntryBoundary = 45),
            snackbarHostState = SnackbarHostState(),
            onRefresh = {},
            onExport = {},
            onNavigateBack = {},
        )
    }

    private companion object {
        val LEVELS = LogLevel.values()

        val SAMPLE_ENTRIES =
            (1..60L).map { seq ->
                LogEntry(
                    seq = seq,
                    timestampMillis = 1_700_000_000_000L + seq * 1_000L,
                    level = LEVELS[(seq % LEVELS.size).toInt()],
                    tag = "BookRepository",
                    message = "Sample log entry #$seq with enough text to run onto more than one line on a phone.",
                )
            }
    }
}
