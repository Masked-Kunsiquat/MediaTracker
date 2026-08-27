package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.screens.StatsScreen
import com.hub.media.ui.StatsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Navigation-bar occlusion guard for the stats screen.
 *
 * No keyboard counterpart: this screen carries no text field, so an IME rule would render the
 * identical layout and assert nothing beyond what this test already does.
 *
 * Populated with real week/month/streak/lifetime numbers -- mirroring `StatsScreenPopulatedPreview`
 * in `StatsScreen.kt`, extended with non-zero `booksFinished`/`lifetimeBooksFinished` so all four
 * cards ([StatsUiState.week]/[StatsUiState.month]/streak/lifetime) render their real-content shape
 * rather than the loading spinner or the all-zero "unknown value" shape, either of which this
 * harness's positive control rejects (see [Occlusion]'s KDoc on why a fixture that cannot
 * demonstrate anything is worse than no fixture at all).
 *
 * Every row on this screen is a static [androidx.compose.material3.Card] -- the back icon is this
 * screen's only clickable control, and it is fixed at the top, nowhere near the bar. So unlike
 * [LibraryScreenOcclusionTest]'s FAB or [ChangelogScreenOcclusionTest]'s expanders, there is no
 * single control this test is watching for; it exists to hold the same invariant every screen in
 * this lane holds, and to catch a future control (e.g. a tap-to-filter card) landing under the bar
 * the moment one is added.
 */
@RunWith(RobolectricTestRunner::class)
class StatsScreenOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withTheNavigationBarShowing_theStatsListStaysReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheNavigationBar { Fixture() }
    }

    @Composable
    private fun Fixture() {
        StatsScreen(
            uiState =
                StatsUiState(
                    isLoading = false,
                    week =
                        StatsUiState.Period(
                            timeReadSeconds = 5_400,
                            sessionCount = 4,
                            pagesRead = 120,
                            booksFinished = 1,
                        ),
                    month =
                        StatsUiState.Period(
                            timeReadSeconds = 27_000,
                            sessionCount = 15,
                            pagesRead = 640,
                            booksFinished = 3,
                        ),
                    currentStreakDays = 5,
                    lifetimeBooksFinished = 42,
                ),
            onNavigateBack = {},
        )
    }
}
