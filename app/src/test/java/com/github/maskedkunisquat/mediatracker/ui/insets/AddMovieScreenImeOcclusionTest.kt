package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.screens.AddMovieScreen
import com.hub.media.ui.AddMovieUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * IME-occlusion guard for the Add Movie screen's form.
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
 */
@RunWith(RobolectricTestRunner::class)
class AddMovieScreenImeOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withTheKeyboardUp_theFormStaysReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheKeyboard {
            AddMovieScreen(
                uiState = AddMovieUiState.Idle,
                onSave = { _, _, _, _, _ -> },
                onErrorShown = {},
                onNavigateBack = {},
            )
        }
    }
}
