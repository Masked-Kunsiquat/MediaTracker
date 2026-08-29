package com.github.maskedkunisquat.mediatracker.ui.goldens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.screens.ChangelogScreen
import com.hub.media.features.changelog.parseChangelog
import com.hub.media.ui.ChangelogUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline golden for the changelog, which is also the app's "What's new" surface.
 *
 * Worth a picture because it is the one screen whose content is *parsed* rather than laid out from
 * a data class: `parseChangelog` turns markdown into version headers and entries, and a mistake in
 * that parse renders as plausible-looking text in the wrong style. No bounds assertion can see the
 * difference between a heading and a paragraph that merely occupies the same rectangle.
 *
 * Recorded collapsed, matching `ChangelogScreenOcclusionTest`'s fixture, so the image is a list of
 * version headers rather than one expanded version's body text.
 *
 * Paired with a text assertion rather than a tag (#102 rule 1): this screen carries no test tags,
 * and per AGENTS.md section 7 a semantic matcher is the better guard anyway. Falsified by renaming
 * the version heading, which fails on the assertion rather than on the image.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChangelogScreenGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun changelog() {
        composeRule.captureGolden(
            name = "changelog",
            // Version headers, not the preamble prose beneath them. Recording collapsed means
            // "Batch N" is composed by nothing -- a first draft asserted it and failed, which is
            // the pairing doing its job on its own author.
            alsoAssert = { assertTextIsShown("0.12.0", "0.11.0") },
        ) { Fixture() }
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
        /** Enough versions to fill the display, left collapsed so the image is the header list. */
        const val VERSION_COUNT = 12

        val UI_STATE =
            ChangelogUiState(
                document =
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
                    ),
                isLoading = false,
                expandedVersions = emptySet(),
                expandedEntries = emptySet(),
            )
    }
}
