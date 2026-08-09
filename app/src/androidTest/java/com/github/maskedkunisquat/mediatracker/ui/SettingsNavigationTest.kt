package com.github.maskedkunisquat.mediatracker.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.maskedkunisquat.mediatracker.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end navigation smoke tests through the real app (ROADMAP: Compose UI test harness).
 *
 * ### This is the file that would have caught the bug
 * The screen-level tests ([com.github.maskedkunisquat.mediatracker.ui.screens.LogViewerScreenTest],
 * [com.github.maskedkunisquat.mediatracker.ui.screens.ChangelogScreenTest]) drive stateless
 * composables with fake callbacks, so they prove a screen honours the contract it is handed. They
 * cannot prove the *route* hands it a real one.
 *
 * That is exactly what shipped during Task 15 Phase B2: a bulk edit added new parameters to every
 * `SettingsScreen` call site at once, stubbing the real route with `{}` no-op lambdas alongside the
 * five previews where stubs are correct. Both controls rendered perfectly and did nothing, and the
 * whole build was green. Only a test that starts at the real `MainActivity` and taps through can
 * see it, which is why this file exists despite being the slowest kind of test here.
 *
 * Instrumented: needs a device, so it cannot join AGENTS.md §7's gate. Run with
 * `./gradlew :app:connectedDebugAndroidTest`.
 *
 * Deliberately a smoke test, not a comprehensive suite. It asserts that each Settings destination
 * is genuinely reachable; what each screen then does is covered by the screen-level tests, which
 * are far cheaper to run and to read.
 */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun openSettings() {
        composeRule.onNodeWithContentDescription("Open settings").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun settings_isReachableFromTheLibraryScreen() {
        openSettings()

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun viewLogButton_actuallyNavigatesToTheLogViewer() {
        openSettings()

        composeRule.onNodeWithText("View log").performScrollTo().performClick()
        composeRule.waitForIdle()

        // The log viewer's own title, not the Settings row's label -- asserting on the row would
        // pass even if the tap did nothing at all.
        composeRule.onNodeWithText("Application log").assertIsDisplayed()
    }

    @Test
    fun viewChangelogButton_actuallyNavigatesToTheChangelogViewer() {
        openSettings()

        composeRule.onNodeWithText("View changelog").performScrollTo().performClick()
        composeRule.waitForIdle()

        // "What's new" is both the Settings row label and the destination's title, so this asserts
        // on content only the destination has: the running version's own changelog section.
        composeRule.onNodeWithText("0.9.0", substring = true).assertIsDisplayed()
    }
}
