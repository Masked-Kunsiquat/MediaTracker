package com.github.maskedkunisquat.mediatracker.ui.goldens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.screens.AboutScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline golden for the attribution screen (#137).
 *
 * **This is the one screen where the picture is the requirement.** TMDB's branding rules constrain
 * how their logo is *drawn* — unmodified in colour, not stretched, not rotated, and less prominent
 * than the app's own mark — and none of that is visible to a text assertion. A stretched logo, a
 * gradient that failed to convert out of the SVG, or an app name that shrank below the logo would
 * all pass [AboutScreenTest] and all break the terms. Here they change the image.
 *
 * Recorded in [Theme.DARK] as well, which most screens do not get. The reason is specific: the
 * logo is a fixed-colour vector rather than a themed one, so it is drawn identically over a light
 * and a dark surface, and the dark golden is the only place a contrast failure would show up. TMDB
 * publish white and black variants precisely because that problem exists; if the dark image ever
 * looks wrong, swapping in an approved variant is the fix, not recolouring this one.
 *
 * Paired with the notice assertion rather than a tag: the screen exists to display that sentence,
 * so a golden of it that did not check the sentence was present would be asserting the frame around
 * an empty obligation.
 *
 * **The recorded images say "MediaTracker Debug", and that is correct.** `src/debug/res` overrides
 * `app_name` so debug can install alongside release (ROADMAP Task 16), and this lane builds debug.
 * A reviewer comparing the golden to a release screenshot will see a different app name and the
 * same everything else; the label is the only thing that varies, and pinning the release string
 * here would only mean asserting a name this build never renders.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutScreenGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun about() {
        composeRule.captureGolden(
            name = "about",
            alsoAssert = { assertTextIsShown(TMDB_NOTICE, APP_NAME) },
        ) { Fixture() }
    }

    @Test
    fun aboutDark() {
        composeRule.captureGolden(
            name = "about-dark",
            theme = Theme.DARK,
            alsoAssert = { assertTextIsShown(TMDB_NOTICE, APP_NAME) },
        ) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        AboutScreen(versionName = PINNED_VERSION, onNavigateBack = {})
    }

    private companion object {
        /**
         * Fixed rather than `BuildConfig.VERSION_NAME`.
         *
         * Reading the real version here would put a string that changes every release into a
         * recorded image, so every release would fail the golden and be "fixed" by re-recording it.
         * A lane that is routinely re-recorded is a lane nobody reads, which is the failure #102
         * was opened about.
         */
        const val PINNED_VERSION = "9.9.9"

        const val TMDB_NOTICE =
            "This product uses the TMDB API but is not endorsed or certified by TMDB."

        /**
         * The debug variant's label, because that is the variant this lane builds. See the class
         * KDoc: it is not a typo, and "Media Tracker" would be the wrong assertion here.
         */
        const val APP_NAME = "MediaTracker Debug"
    }
}
