package com.github.maskedkunisquat.mediatracker.ui

import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.MainActivity
import com.github.maskedkunisquat.mediatracker.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Each library's stats button opens its own Stats tab (#217), from the real [MainActivity]. Here
 * rather than in the Robolectric lane because only a test that starts at the app's entry point proves
 * the route hands Stats the library's domain rather than nothing (AGENTS.md section 7: "a route
 * actually being wired"). The tabs' own behaviour is covered statelessly in `StatsScreenTest`.
 */
@RunWith(AndroidJUnit4::class)
class StatsNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun string(
        @StringRes id: Int,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun openStatsFrom(hubCard: String) {
        composeRule.onNodeWithTag(hubCard).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(string(R.string.stats_content_description)).performClick()
        composeRule.waitForIdle()
    }

    private fun tab(
        @StringRes label: Int,
    ) = composeRule.onNode(hasText(string(label)) and hasClickAction())

    @Test
    fun theFilmsLibrary_opensTheFilmsTab() {
        openStatsFrom(TestTags.Hub.FILMS)

        tab(R.string.stats_domain_films).assertIsSelected()
    }

    @Test
    fun theShowsLibrary_opensTheShowsTab() {
        openStatsFrom(TestTags.Hub.SHOWS)

        tab(R.string.stats_domain_shows).assertIsSelected()
    }
}
