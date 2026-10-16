package com.github.maskedkunisquat.mediatracker.ui

import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.MainActivity
import com.github.maskedkunisquat.mediatracker.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The hub's route wiring (#126), from the real [MainActivity]. Here rather than in the Robolectric
 * lane because only a test that starts at the app's own entry point proves the start destination is
 * the hub and that a card hands the library a real domain (AGENTS.md section 7: "a route actually
 * being wired"). The hub's own behaviour is covered statelessly in `HubScreenTest`.
 */
@RunWith(AndroidJUnit4::class)
class HubNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun string(
        @StringRes id: Int,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun theAppOpensOnTheHub_andBooksOpensTheBooksLibrary_andBackReturns() {
        composeRule.onNodeWithTag(TestTags.Hub.BOOKS).assertIsDisplayed()

        composeRule.onNodeWithTag(TestTags.Hub.BOOKS).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.library_title_books)).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(string(R.string.navigate_back)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.Hub.BOOKS).assertIsDisplayed()
    }
}
