package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.screens.EditMovieScreen
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.ui.EditMovieUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Occlusion guards for the Edit Movie screen's form.
 *
 * [EditMovieUiState.Editing] is used with every field populated (mirroring
 * `EditMovieScreenTest`'s own `editing()` fixture) rather than [EditMovieUiState.Loading], which
 * renders only a [androidx.compose.material3.CircularProgressIndicator] and no fields at all -- a
 * state that would pass this check regardless of inset handling.
 *
 * Every field, status chip, and the save button live in the one `verticalScroll` `Column` that
 * makes up this screen's whole body. The harness scrolls that container to its end and measures
 * there rather than exempting what is inside it, because the end of the scroll is as high as the
 * save button will ever get -- still under the keyboard there means unreachable however far you
 * scroll. Confirmed by deleting `contentWindowInsets` from this screen and watching this fail.
 *
 * There is deliberately **no navigation bar test** on this screen. Its form does not reach the
 * bottom of the display without a keyboard, so nothing measurable is ever near the bar: the rule
 * was tried here and passed with `barPadding` returning zero, which makes it a green no-op of the
 * kind AGENTS.md section 7 rejects. The keyboard is the inset that actually squeezes this screen,
 * and the test above is falsified against it. If the form grows past the display, add it back --
 * and prove it fails first.
 */
@RunWith(RobolectricTestRunner::class)
class EditMovieScreenOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withTheKeyboardUp_theFormStaysReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheKeyboard(expectedTags = TAGS) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        EditMovieScreen(
            uiState =
                EditMovieUiState.Editing(
                    title = "Interstellar",
                    releaseYear = "2014",
                    runtimeMinutes = "169",
                    purchasePrice = "14.99",
                    status = WatchStatus.WATCHLIST,
                ),
            onTitleChange = {},
            onReleaseYearChange = {},
            onRuntimeChange = {},
            onPurchasePriceChange = {},
            onStatusChange = {},
            onSave = {},
            onErrorShown = {},
            onNavigateBack = {},
        )
    }

    private companion object {
        /** Named here rather than centrally so a dropped tag fails the screen that owns it. */
        val TAGS =
            listOf(
                TestTags.EditMovie.FORM,
                TestTags.EditMovie.SAVE_BUTTON,
            )
    }
}
