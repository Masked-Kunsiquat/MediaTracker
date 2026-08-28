package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.screens.ChangelogScreen
import com.hub.media.features.changelog.parseChangelog
import com.hub.media.ui.ChangelogUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Navigation-bar occlusion guard for the "What's new" changelog viewer.
 *
 * No keyboard counterpart: this screen has no text field, so an IME rule would render the identical
 * layout and assert nothing beyond what this test already does.
 *
 * Twenty-five versions, parsed with the same [parseChangelog] the screen itself uses on
 * `CHANGELOG.md` (mirroring [ChangelogScreenTest]'s own fixture) rather than a hand-built
 * [ChangelogUiState], so this exercises the real section/entry shape. Every one of them is left
 * **collapsed**, which is the opposite of what this fixture started as -- see the note on
 * `VERSION_COUNT` for why the expanded version of it asserted nothing at all. Collapsed, every row
 * on screen is a clickable version `ExpanderRow`, so the bottom of this list is something the rule
 * can measure and the bar can strand.
 */
@RunWith(RobolectricTestRunner::class)
class ChangelogScreenOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withTheNavigationBarShowing_theLastVersionsExpandersStayReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheNavigationBar { Fixture() }
    }

    @Composable
    private fun Fixture() {
        ChangelogScreen(
            uiState = UI_STATE,
            onToggleVersion = {},
            onToggleEntry = {},
            onNavigateBack = {},
        )
    }

    private companion object {
        /**
         * Enough versions to run past the bottom of the test display, left **collapsed**.
         *
         * Both halves of that matter, and the first draft of this fixture had neither. It held
         * four versions with every entry expanded, which is longer but useless here: the harness
         * measures interactive nodes, and with a version open the bottom-most thing on screen is
         * body text, while the clickable headers sit well above it. Nothing measurable came near
         * the navigation bar and the test passed with the padding deleted.
         *
         * Collapsed, every row is a clickable version header, so the last row is both at the
         * bottom edge and something this rule can see.
         */
        const val VERSION_COUNT = 25

        val DOCUMENT =
            parseChangelog(
                buildString {
                    (VERSION_COUNT downTo 1).forEach { n ->
                        appendLine("## [0.$n.0] - 2026-01-01")
                        appendLine()
                        appendLine("Batch $n.")
                        appendLine()
                        appendLine("### Fixed")
                        appendLine()
                        appendLine("- **Something was wrong and now is not** -- one entry is enough.")
                        appendLine()
                    }
                },
            )

        val UI_STATE =
            ChangelogUiState(
                document = DOCUMENT,
                isLoading = false,
                expandedVersions = emptySet(),
                expandedEntries = emptySet(),
            )
    }
}
