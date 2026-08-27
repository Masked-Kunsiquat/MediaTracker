package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.screens.ChangelogScreen
import com.hub.media.features.changelog.parseChangelog
import com.hub.media.ui.ChangelogUiState
import com.hub.media.ui.entryKey
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
 * Four versions, parsed with the same [parseChangelog] the screen itself uses on `CHANGELOG.md`
 * (mirroring [ChangelogScreenTest]'s own fixture) rather than a hand-built [ChangelogUiState], so
 * this exercises the real section/entry shape. Every version is expanded and every folded
 * (`**bold**`-led) entry within them is too -- unlike [ChangelogScreenTest], which deliberately
 * keeps content collapsed to assert the fold itself, this needs the opposite: content long enough
 * to reach the bottom of the screen, which a collapsed version card does not do. Each version's
 * `ExpanderRow` and each entry's `ExpanderRow` are genuinely clickable, so -- unlike
 * [StatsScreenOcclusionTest]/[LogViewerScreenOcclusionTest] -- this screen has real controls near
 * the bottom of the list for the bar to strand.
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
        val DOCUMENT =
            parseChangelog(
                """
                ## [0.20.0] - 2026-08-20

                Fifth batch of polish work.

                ### Added

                - **A sync status indicator** — shows the current backup state at a glance on the
                  settings screen, replacing a text-only summary most people never noticed.
                - **Bulk episode marking** — mark a whole season watched or unwatched in one tap
                  instead of ticking every episode by hand.
                - a small tweak to the empty-library illustration

                ### Fixed

                - **Season overflow menu clipped on narrow phones** — the season header now
                  truncates its title instead of pushing the menu button off-screen.

                ## [0.19.0] - 2026-08-06

                Fourth batch.

                ### Added

                - **Reading streak card** — shows the current consecutive-day streak on the stats
                  screen, alongside the existing week/month totals.

                ### Changed

                - **Log viewer now opens scrolled to the newest entry** — matches a terminal's tail
                  behaviour instead of dropping you at the oldest line every time.

                ## [0.18.0] - 2026-07-20

                Third batch.

                ### Fixed

                - **Cover re-fetch no longer duplicates files on disk** — the content hash is now
                  checked before writing, matching the dedup rule every other cover write follows.
                - a one-line dependency bump

                ## [0.17.0] - 2026-07-01

                Second batch.

                ### Added

                - **CSV export now includes purchase price** — previously silently dropped from the
                  exported columns.
                """.trimIndent(),
            )

        val UI_STATE =
            ChangelogUiState(
                document = DOCUMENT,
                isLoading = false,
                expandedVersions = DOCUMENT.versions.map { it.version }.toSet(),
                expandedEntries =
                    DOCUMENT.versions
                        .flatMap { version ->
                            version.sections.flatMap { section ->
                                section.entries.mapIndexedNotNull { index, entry ->
                                    if (entry.heading != null) entryKey(version.version, section.title, index) else null
                                }
                            }
                        }.toSet(),
            )
    }
}
