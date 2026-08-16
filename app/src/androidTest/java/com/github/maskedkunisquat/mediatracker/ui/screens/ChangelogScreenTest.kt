package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.features.changelog.parseChangelog
import com.hub.media.ui.ChangelogUiState
import com.hub.media.ui.entryKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Behavioural tests for [ChangelogScreen] (ROADMAP: Compose UI test harness). See
 * [LogViewerScreenTest]'s KDoc for why this file exists and how to run it.
 *
 * The fold is the whole point of this screen, and folding is exactly the kind of behaviour a
 * ViewModel test cannot see: the state transitions are unit-tested in `ChangelogViewModelTest`,
 * but whether collapsed content is actually *hidden* is a rendering question.
 */
class ChangelogScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val document =
        parseChangelog(
            """
            ## [0.9.0] - 2026-08-09

            A summary that is always visible once the version is open.

            ### Added

            - **A folded entry** — detail that stays hidden until expanded.
            - a flat one-liner with no bold lead
            """.trimIndent(),
        )

    private fun setContent(
        uiState: ChangelogUiState,
        onToggleVersion: (String) -> Unit = {},
        onToggleEntry: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                ChangelogScreen(
                    uiState = uiState,
                    onToggleVersion = onToggleVersion,
                    onToggleEntry = onToggleEntry,
                    onNavigateBack = {},
                )
            }
        }
    }

    @Test
    fun collapsedVersion_showsItsHeadingButNoneOfItsContent() {
        setContent(ChangelogUiState(document = document, isLoading = false))

        composeRule.onNodeWithText("0.9.0", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("A summary", substring = true).assertDoesNotExist()
    }

    @Test
    fun expandedVersion_showsItsPreambleWithoutNeedingAnyFurtherTap() {
        // The preamble is deliberately not behind its own expander: it is the plain-language
        // summary, which is the thing worth reading before deciding to expand any detail.
        setContent(
            ChangelogUiState(
                document = document,
                expandedVersions = setOf("0.9.0"),
                isLoading = false,
            ),
        )

        composeRule.onNodeWithText("A summary", substring = true).assertIsDisplayed()
    }

    @Test
    fun expandedVersion_stillHidesEachEntrysDetailUntilThatEntryIsExpanded() {
        setContent(
            ChangelogUiState(
                document = document,
                expandedVersions = setOf("0.9.0"),
                isLoading = false,
            ),
        )

        composeRule.onNodeWithText("A folded entry").assertIsDisplayed()
        composeRule.onNodeWithText("stays hidden", substring = true).assertDoesNotExist()
    }

    @Test
    fun expandedEntry_revealsItsDetail() {
        setContent(
            ChangelogUiState(
                document = document,
                expandedVersions = setOf("0.9.0"),
                expandedEntries = setOf(entryKey("0.9.0", "Added", 0)),
                isLoading = false,
            ),
        )

        composeRule.onNodeWithText("stays hidden", substring = true).assertIsDisplayed()
    }

    @Test
    fun entryWithoutABoldLead_isRenderedFlatRatherThanBehindAnExpander() {
        // The 12-of-70 case: short one-liners have no heading to fold behind, so they show in
        // place. If this ever regressed they would vanish entirely, since there would be no
        // expander to reveal them.
        setContent(
            ChangelogUiState(
                document = document,
                expandedVersions = setOf("0.9.0"),
                isLoading = false,
            ),
        )

        composeRule.onNodeWithText("a flat one-liner", substring = true).assertIsDisplayed()
    }

    @Test
    fun tappingAVersionHeading_invokesTheToggleCallbackWithThatVersion() {
        val toggled = mutableListOf<String>()
        setContent(ChangelogUiState(document = document, isLoading = false), onToggleVersion = { toggled += it })

        composeRule.onNodeWithText("0.9.0", substring = true).performClick()

        assertEquals(listOf("0.9.0"), toggled)
    }

    @Test
    fun tappingAnEntryHeading_invokesTheToggleCallbackWithThatEntrysKey() {
        val toggled = mutableListOf<String>()
        setContent(
            ChangelogUiState(document = document, expandedVersions = setOf("0.9.0"), isLoading = false),
            onToggleEntry = { toggled += it },
        )

        composeRule.onNodeWithText("A folded entry").performClick()

        assertEquals(listOf(entryKey("0.9.0", "Added", 0)), toggled)
    }

    @Test
    fun unreadableChangelog_saysSoRatherThanShowingAnEmptyScreen() {
        setContent(ChangelogUiState(isLoading = false, failedToLoad = true))

        composeRule.onNodeWithText("Release notes are unavailable in this build.").assertIsDisplayed()
    }
}
