package com.github.maskedkunisquat.mediatracker.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.github.maskedkunisquat.mediatracker.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The attribution screen (#137), tested for the things a licence term actually requires.
 *
 * This is an unusual test in that most of it asserts **exact strings**, which AGENTS.md section 7
 * would normally push back on: pinning copy makes a test fail on a harmless rewording. That is the
 * point here. The TMDB notice and the TMDB name are not copy -- they are quoted from terms that say
 * what they must be, and the failure mode this guards is precisely somebody improving the wording.
 * A test that tolerated a reword would tolerate the breach.
 *
 * The one string that is *not* pinned is the app's own name, and that exception is load-bearing:
 * see [appName].
 *
 * What is not asserted here: the exact prominence of the logo. That is a real requirement (their
 * logo must be less prominent than the app's own mark) and it is a visual property, so it belongs
 * to `AboutScreenGoldenTest` and the device pass rather than to a bounds comparison that would pass
 * on any two rectangles.
 */
@RunWith(RobolectricTestRunner::class)
class AboutScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theTmdbNoticeAppearsWordForWord() {
        setContent()

        // TMDB's FAQ: "You shall place the following notice prominently on your application."
        // Their sentence, not ours. If this fails, check their terms before changing the string.
        composeRule
            .onNodeWithText("This product uses the TMDB API but is not endorsed or certified by TMDB.")
            .assertIsDisplayed()
    }

    @Test
    fun theLogoIsAnnouncedByAPermittedName() {
        setContent()

        // "When referring to TMDB, you should use either the acronym 'TMDB' or the full name
        // 'The Movie Database'. Any other name is not acceptable." A content description is a
        // user-facing name like any other, and it is the one a sighted reviewer never sees.
        composeRule
            .onNodeWithContentDescription("The Movie Database")
            .assertIsDisplayed()
    }

    @Test
    fun googleBooksIsCreditedInGooglesOwnWords() {
        setContent()

        // The conservative reading of "Google attribution is required" -- see AboutScreen's KDoc
        // for why this app credits a provider whose branding rules were written for a surface it
        // does not have.
        composeRule.onNodeWithText("Powered by Google").assertIsDisplayed()
    }

    @Test
    fun openLibraryIsCreditedAndSaysItDidNotHaveToBe() {
        setContent()

        composeRule.onNodeWithText("Open Library").assertIsDisplayed()
        // The sentence that stops a later reader mistaking a courtesy for an obligation, or
        // deleting it as redundant. Substring rather than exact: this half is ours to reword.
        composeRule
            .onNodeWithText("asks for no credit", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun theAppsOwnMarkAndVersionAreShown() {
        setContent()

        // The app's own mark. Its presence is what makes "less prominent than" satisfiable at all:
        // remove it and the most prominent mark on the screen becomes TMDB's.
        composeRule.onNodeWithText(appName).assertIsDisplayed()
        composeRule.onNodeWithText("Version $PINNED_VERSION").assertIsDisplayed()
    }

    private fun setContent() {
        composeRule.setContent {
            AboutScreen(versionName = PINNED_VERSION, onNavigateBack = {})
        }
    }

    /**
     * The app's name **as this build type spells it**, resolved rather than written out.
     *
     * A literal was the first draft and it failed, for a reason worth keeping: `src/debug/res`
     * overrides `app_name` so debug can install alongside release (ROADMAP Task 16), and the unit
     * test lane builds the debug variant -- so the screen under test renders "MediaTracker Debug"
     * and never the release label.
     *
     * Resolving it is also the more honest assertion. The requirement is that the app's own mark
     * out-ranks the TMDB logo, and each build's mark is its own label; asserting the release string
     * would have been checking a name this build does not use.
     */
    private val appName: String
        get() =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getString(R.string.app_name)

    private companion object {
        /** Fixed, not `BuildConfig.VERSION_NAME`: a release must not be able to fail this test. */
        const val PINNED_VERSION = "9.9.9"
    }
}
