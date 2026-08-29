package com.github.maskedkunisquat.mediatracker.ui.goldens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.screens.StatsScreen
import com.hub.media.ui.StatsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline golden for stats — the screen the occlusion lane cannot say anything about.
 *
 * It has no interactive node inside its scroller at all, only top-bar buttons, so the navigation
 * bar rule was written for it and then deleted as a guaranteed pass (see `Occlusion.kt`'s exemption
 * list). What #99 actually risks here is a last card clipped by the bar, which is visual by
 * definition. This golden is the assertion that lane could not make, and the reason #102 exists
 * alongside #96 rather than instead of it.
 *
 * The fixture carries a **`null` `timeReadSeconds` on the month period** deliberately.
 * `StatsRepository`'s contract distinguishes "unknown" from "zero" — a session with no recorded
 * duration must not become a zero that corrupts a total — and the screen renders that distinction
 * as different text. A golden that only ever showed populated numbers would not notice the day
 * "unknown" started rendering as "0m", which is the silent-wrong-value shape this repository has
 * already shipped once, in #78's comma-decimal price.
 *
 * Paired with a text assertion rather than a tag (#102 rule 1): this screen carries none, and a
 * semantic matcher is the better guard per AGENTS.md section 7.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsScreenGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stats() {
        composeRule.captureGolden(
            name = "stats",
            alsoAssert = { assertTextIsShown("Streak", "Books finished") },
        ) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        StatsScreen(
            uiState =
                StatsUiState(
                    isLoading = false,
                    week =
                        StatsUiState.Period(
                            timeReadSeconds = 5 * 3600L + 20 * 60L,
                            sessionCount = 7,
                            pagesRead = 214,
                            booksFinished = 1,
                        ),
                    // timeReadSeconds left null: "unknown", not "zero" -- see this class's KDoc.
                    month =
                        StatsUiState.Period(
                            timeReadSeconds = null,
                            sessionCount = 23,
                            pagesRead = 902,
                            booksFinished = 4,
                        ),
                    currentStreakDays = 12,
                    lifetimeBooksFinished = 87,
                ),
            onNavigateBack = {},
        )
    }
}
