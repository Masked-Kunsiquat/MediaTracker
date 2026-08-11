package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.storage.LogEntry
import com.hub.media.core.util.LogLevel
import com.hub.media.ui.LogViewerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Behavioural tests for [LogViewerScreen] (ROADMAP: Compose UI test harness).
 *
 * ### Why this file exists
 * `:app` was previously verified only by whether it compiled. Two bugs shipped past that during
 * Task 15 Phase B2 — a control wired to a no-op lambda, and an effect that scrolled to an
 * unmeasured extent — and both are invisible to a ViewModel unit test, because in both cases the
 * ViewModel was correct. These tests assert what a *screen* does, which is the gap.
 *
 * Instrumented, so they need a device or emulator and cannot join the
 * `:shared:jvmTest`/`:shared:testDebugUnitTest` gate AGENTS.md §7 mandates. Run with
 * `./gradlew :app:connectedDebugAndroidTest`.
 *
 * The stateless [LogViewerScreen] is driven directly with fake callbacks rather than going through
 * the route composable: this asserts the screen honours the contract it is given. Whether the
 * *route* passes real callbacks is a different failure (the one that actually shipped) and is
 * covered separately by the navigation test.
 */
class LogViewerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun entry(seq: Long, message: String) = LogEntry(
        seq = seq,
        timestampMillis = 1_700_000_000_000L + seq,
        level = LogLevel.WARN,
        tag = "T",
        message = message,
    )

    private fun setContent(
        uiState: LogViewerUiState,
        onRefresh: () -> Unit = {},
        onExport: () -> Unit = {},
        onNavigateBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                LogViewerScreen(
                    uiState = uiState,
                    snackbarHostState = SnackbarHostState(),
                    onRefresh = onRefresh,
                    onExport = onExport,
                    onNavigateBack = onNavigateBack,
                )
            }
        }
    }

    @Test
    fun refreshAction_whenTapped_invokesTheCallbackItWasGiven() {
        var refreshes = 0
        setContent(
            uiState = LogViewerUiState(entries = listOf(entry(1, "hello"))),
            onRefresh = { refreshes++ },
        )

        composeRule.onNodeWithText("Refresh").performClick()

        assertEquals("Refresh must reach its callback, not render as a dead control", 1, refreshes)
    }

    @Test
    fun exportAction_whenTapped_invokesTheCallbackItWasGiven() {
        var exports = 0
        setContent(
            uiState = LogViewerUiState(entries = listOf(entry(1, "hello"))),
            onExport = { exports++ },
        )

        composeRule.onNodeWithText("Export full log").performClick()

        assertEquals(1, exports)
    }

    @Test
    fun entries_areRenderedAndSelectableAsText() {
        // Fixture messages are deliberately not ordinary words. They were "first"/"second", which
        // silently started matching the "Oldest first -- newest at the bottom" caption as well, so
        // the substring assertion failed on ambiguity rather than on absence -- a failure that
        // reads as "the entry is missing" when the entry is fine.
        setContent(uiState = LogViewerUiState(entries = listOf(entry(1, "alpha-msg"), entry(2, "bravo-msg"))))

        composeRule.onNodeWithText("alpha-msg", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("bravo-msg", substring = true).assertIsDisplayed()
    }

    @Test
    fun entryList_statesWhichDirectionTimeRuns() {
        // Entries carry no timestamp, and the screen opens scrolled to the newest one -- so without
        // a stated direction the first thing on screen looks like the top of the log when it is
        // actually the bottom. Reported from a device; nothing in the suite could have caught it.
        setContent(uiState = LogViewerUiState(entries = listOf(entry(1, "alpha-msg"), entry(2, "bravo-msg"))))

        composeRule.onNodeWithText("Oldest first", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("End of log").assertIsDisplayed()
    }

    @Test
    fun emptySnapshot_showsNoDirectionHint() {
        // The hint belongs to a list; with nothing to order it would be noise contradicting the
        // "no entries recorded yet" message.
        setContent(uiState = LogViewerUiState(entries = emptyList(), isLoading = false))

        composeRule.onNodeWithText("Oldest first", substring = true).assertDoesNotExist()
    }

    @Test
    fun emptySnapshot_showsTheEmptyMessageRatherThanABlankScreen() {
        setContent(uiState = LogViewerUiState(entries = emptyList(), isLoading = false))

        composeRule.onNodeWithText("No log entries recorded yet.").assertIsDisplayed()
    }

    @Test
    fun refreshBoundary_rendersTheNewEntriesDividerAboveTheFirstNewEntry() {
        // The divider is the screen's one genuinely stateful piece of rendering. A wrong boundary
        // still renders, it just lies about which entries are new -- so "it displays" is not enough
        // and the position is asserted against the entry it must precede.
        setContent(
            uiState = LogViewerUiState(
                // Message text chosen to collide with nothing else on screen: "fresh" would
                // have matched the divider's own "New since refresh" and the toolbar's "Refresh".
                entries = listOf(entry(1, "alpha-entry"), entry(2, "bravo-entry")),
                newEntryBoundary = 1L,
            ),
        )

        // useUnmergedTree: the entries live inside a SelectionContainer, which merges its
        // descendants' text into the parent node, so an exact-match lookup finds nothing in the
        // merged tree. The unmerged tree is also what position comparisons need -- merged nodes
        // report the bounds of the whole group, not of the individual Text.
        val divider = composeRule
            .onNodeWithText("New since refresh", useUnmergedTree = true).fetchSemanticsNode()
        val oldEntry = composeRule
            .onNodeWithText("alpha-entry", substring = true, useUnmergedTree = true).fetchSemanticsNode()
        val newEntry = composeRule
            .onNodeWithText("bravo-entry", substring = true, useUnmergedTree = true).fetchSemanticsNode()

        assertTrue(
            "divider must sit below the old entry",
            divider.positionInRoot.y > oldEntry.positionInRoot.y,
        )
        assertTrue(
            "divider must sit above the new entry",
            divider.positionInRoot.y < newEntry.positionInRoot.y,
        )
    }

    @Test
    fun noBoundary_drawsNoDivider() {
        setContent(uiState = LogViewerUiState(entries = listOf(entry(1, "only"))))

        // Positive control: prove the screen actually rendered the entry before trusting the
        // divider's absence -- otherwise a blank screen would pass this test too.
        composeRule.onNodeWithText("only", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("New since refresh", useUnmergedTree = true).assertDoesNotExist()
    }
}
