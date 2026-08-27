package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.screens.AddMovieScreen
import com.hub.media.ui.AddMovieUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Occlusion guards for the Add Movie screen's form.
 *
 * [AddMovieUiState.Idle] is the only state that renders the actual entry form ([AddMovieUiState]'s
 * other cases are Saving/Saved/Error, none of which add fields), so it is used here rather than a
 * fabricated field population: this screen's title/year/runtime/price fields are held locally via
 * `rememberSaveable` inside the composable itself (see [AddMovieScreen]'s KDoc), so there is no way
 * to seed typed text into them from outside without performing text input, which this harness does
 * not do. The form's full set of fields, status chips, and save button are all present and laid out
 * in the Idle state regardless of their text content, which is what determines whether anything ends
 * up behind the keyboard.
 *
 * Every field, chip, and the save button live in the one `verticalScroll` `Column` that makes up
 * this screen's whole body -- which is exactly the shape that made the first version of this test
 * assert nothing at all. The harness then exempted everything inside a scroller, so deleting
 * `contentWindowInsets` from this screen changed no assertion and the test stayed green. It now
 * scrolls that container to its end before measuring, which is the highest position the save button
 * at the foot of the form can reach -- if it is still under the keyboard there, no scroll offset
 * retrieves it. Confirmed by deleting that argument and watching this fail.
 *
 * There is deliberately **no navigation bar test** on this screen. Its form does not reach the
 * bottom of the display without a keyboard, so nothing measurable is ever near the bar: the rule
 * was tried here and passed with `barPadding` returning zero, which makes it a green no-op of the
 * kind AGENTS.md section 7 rejects. The keyboard is the inset that actually squeezes this screen,
 * and the test above is falsified against it. If the form grows past the display, add it back --
 * and prove it fails first.
 */
@RunWith(RobolectricTestRunner::class)
class AddMovieScreenOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withTheKeyboardUp_theFormStaysReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheKeyboard(expectedTags = TAGS) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        AddMovieScreen(
            uiState = AddMovieUiState.Idle,
            onSave = { _, _, _, _, _ -> },
            onErrorShown = {},
            onNavigateBack = {},
        )
    }

    private companion object {
        /** Named here rather than centrally so a dropped tag fails the screen that owns it. */
        val TAGS =
            listOf(
                TestTags.AddMovie.FORM,
                TestTags.AddMovie.SAVE_BUTTON,
            )
    }
}
