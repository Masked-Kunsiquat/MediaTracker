package com.github.maskedkunisquat.mediatracker.ui

import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.BuildConfig
import com.github.maskedkunisquat.mediatracker.MainActivity
import com.github.maskedkunisquat.mediatracker.MediaTrackerApplication
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.features.settings.data.clearGoogleBooksApiKey
import com.hub.media.features.settings.data.setGoogleBooksApiKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
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

    private val application: MediaTrackerApplication
        get() =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.applicationContext as MediaTrackerApplication

    /** The app's own copy, so a copy edit to one of these strings cannot silently stop matching. */
    private fun string(
        @StringRes id: Int,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    /**
     * Removes any stored Google Books key, through the repository rather than through the screen.
     *
     * Runs before and after every test in this class, and the choice of layer is the point in both
     * directions:
     *
     * - **Before**, because the row renders differently once a key is stored -- the Save button
     *   relabels to "Replace key" and Clear appears at all -- so a key left behind by an earlier
     *   run makes [googleBooksApiKeySave_isWiredToARealCallback] drive a screen it was not written
     *   against, and makes its "a key is saved" assertion pass without proving anything. Asserting
     *   the precondition is not enough; the run has to be able to establish it.
     * - **After**, because this runs against the debug app's *real* database. Cleanup that goes
     *   through the UI can only work when the UI is in the state the test expected, which is
     *   precisely what a failing test cannot promise -- and the thing left behind is a credential
     *   the next real Google Books lookup on this install would use. The `finally` this replaces
     *   also *asserted*, so every failure in the body was discarded in favour of the cleanup's own
     *   exception: #114 spent its life reported as the wrong failure on the wrong line.
     */
    private fun removeStoredApiKey() =
        runBlocking {
            application.appContainer.settingsRepository.clearGoogleBooksApiKey()
        }

    @Before
    fun clearApiKeyBefore() = removeStoredApiKey()

    @After
    fun clearApiKeyAfter() = removeStoredApiKey()

    /**
     * Brings the Google Books API key section into view.
     *
     * It is the last section on a `LazyColumn`, so on a shorter screen -- or a larger display size,
     * or a longer translation -- it is not composed when Settings opens, and nothing in it can be
     * found by text at all. `performScrollTo()` cannot fix that: it needs the node to already exist.
     * Scrolling the list itself to a node that matches works either way, which is what
     * [TestTags.Settings.LIST] is there for.
     *
     * Without this the API key tests pass or fail by screen geometry, which is precisely the class
     * of "fails on someone else's device" that #114 was.
     */
    private fun scrollToApiKeyRow() {
        composeRule
            .onNodeWithTag(TestTags.Settings.LIST)
            .performScrollToNode(hasTestTag(TestTags.Settings.API_KEY_FIELD))
    }

    /**
     * Polls until the status line reads [text], then returns; fails with the assertion's own
     * message if it never does.
     *
     * ### Why a hand-rolled poll and not `waitForIdle()` or `waitUntil {}`
     * The status line is downstream of a Room write and a `Flow` emission back out through
     * `SettingsViewModel`'s `combine`. `waitForIdle()` synchronises Compose recomposition and the
     * main-thread queue and waits for neither of those, so the bare assert that used to follow it
     * was racing the database -- which is what made #114 fail on a clean `main`, from both ends
     * depending on which side of the race a run landed on: with the save not yet visible the Clear
     * button had not been composed at all, and with the clear not yet visible the status still read
     * "A key is saved".
     *
     * `composeRule.waitUntil {}` is the obvious replacement and does **not** work here: given the
     * identical condition it times out after ten seconds, while this loop -- same query, real
     * `Thread.sleep` between polls -- satisfies it in well under one. That was measured on this
     * screen, not reasoned about, and the mechanism was not chased further than establishing which
     * of the two waits actually observes the write. Prefer `waitUntil` elsewhere; if it ever times
     * out against a condition you can show is true, this is the fallback that sees it.
     */
    private fun awaitStatus(text: String) {
        repeat(POLL_ATTEMPTS) {
            if (composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()) {
                return
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        composeRule.onNodeWithText(text, substring = true).assertIsDisplayed()
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
        //
        // Read from BuildConfig rather than written literally. Hardcoding "0.9.0" here meant this
        // test broke the moment v0.10.0 was tagged -- a release should not require editing a test
        // that is not about the release.
        composeRule.onNodeWithText(BuildConfig.VERSION_NAME, substring = true).assertIsDisplayed()
    }

    /**
     * Saving a Google Books API key is wired to a real callback.
     *
     * A route-level test rather than a stateless screen-level one, deliberately, and for this
     * control specifically: the screen half is already covered without a device -- `SettingsViewModel`
     * writes and clears through a real repository in `SettingsViewModelTest`, and the composable
     * has no logic beyond calling what it is handed. What no JVM test can see is whether
     * `SettingsScreenRoute` hands it `viewModel::setGoogleBooksApiKey` or a `{}` stub, which is
     * exactly the failure this file was created for (see the class KDoc) and exactly the shape of
     * edit that produced it: a bulk edit adding parameters to every call site, five of which are
     * previews where a stub is correct.
     *
     * The status line is the assertion target rather than the field, because it is downstream of a
     * real write: it flips only once the key has reached `app_settings` and come back out through
     * the repository's reactive accessor. A stubbed callback leaves it saying "No key saved" while
     * everything on screen still looks right.
     */
    @Test
    fun googleBooksApiKeySave_isWiredToARealCallback() {
        openSettings()
        scrollToApiKeyRow()

        // Asserted, not assumed. clearApiKeyBefore establishes it, so a failure here means the
        // status line does not track the repository at all -- a different bug from a broken Save,
        // and worth being told apart from it.
        composeRule
            .onNodeWithText(string(R.string.settings_google_books_key_not_saved), substring = true)
            .assertIsDisplayed()

        composeRule
            .onNodeWithTag(TestTags.Settings.API_KEY_FIELD)
            .performTextInput(TEST_KEY)

        // Save is `enabled = entered.isNotBlank()`, so text entry that silently landed nowhere
        // would otherwise show up as a click that did nothing and read as a broken callback --
        // the exact conclusion this test exists to draw, reached for the wrong reason.
        composeRule
            .onNodeWithText(string(R.string.settings_google_books_key_save_button))
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        awaitStatus(string(R.string.settings_google_books_key_saved))
    }

    /**
     * Clearing a stored Google Books API key is wired to a real callback.
     *
     * Split from the save test rather than continuing it, for two reasons.
     *
     * **A failure names one callback.** As one test, a broken Save failed the Clear half too (Clear
     * is only composed when a key is stored, so it was not merely unasserted -- it was absent), and
     * the report pointed at whichever line the cleanup happened to reach.
     *
     * **Clearing straight after a save does not work, and waiting it out is not available.** With
     * the save's "Google Books API key saved" snackbar up -- and the API key row is the last section
     * on this screen, so the two overlap -- a Clear tap was observed to leave the status line
     * unchanged. Whether the snackbar swallows the tap or something else about that moment does is
     * not established here; what is established is that the snackbar does not retire under this
     * harness (ten seconds of polling still found it in the tree), so a test cannot simply wait for
     * a clean screen. Arranging the stored key through the repository never raises the snackbar at
     * all, and is the honest arrangement anyway -- this test is about Clear, not about Save.
     */
    @Test
    fun googleBooksApiKeyClear_isWiredToARealCallback() {
        runBlocking { application.appContainer.settingsRepository.setGoogleBooksApiKey(TEST_KEY) }

        openSettings()
        scrollToApiKeyRow()

        // The row has to see the arranged key before clearing it means anything.
        awaitStatus(string(R.string.settings_google_books_key_saved))

        // Clear is only composed once a key is stored, so reaching it at all is half the assertion.
        composeRule
            .onNodeWithText(string(R.string.settings_google_books_key_clear_button))
            .performScrollTo()
            .performClick()

        awaitStatus(string(R.string.settings_google_books_key_not_saved))
    }

    private companion object {
        const val TEST_KEY = "test-key-not-a-real-one"

        /** 10s total, which is an order of magnitude more than the observed sub-second settle. */
        const val POLL_ATTEMPTS = 50
        const val POLL_INTERVAL_MILLIS = 200L
    }
}
