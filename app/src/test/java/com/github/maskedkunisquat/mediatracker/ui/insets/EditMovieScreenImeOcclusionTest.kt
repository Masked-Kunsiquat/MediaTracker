package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.screens.EditMovieScreen
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.ui.EditMovieUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * IME-occlusion guard for the Edit Movie screen's form.
 *
 * [EditMovieUiState.Editing] is used with every field populated (mirroring
 * `EditMovieScreenTest`'s own `editing()` fixture) rather than [EditMovieUiState.Loading], which
 * renders only a [androidx.compose.material3.CircularProgressIndicator] and no fields at all -- a
 * state that would pass this check regardless of inset handling.
 *
 * Every field, status chip, and the save button live in the one `verticalScroll` `Column` that
 * makes up this screen's whole body. The harness checks that container's own bottom edge rather
 * than exempting what is inside it, because scrolling stops at the content's end -- so a viewport
 * running under the keyboard leaves the save button unreachable however far you scroll. Confirmed
 * by deleting `contentWindowInsets` from this screen and watching this fail.
 */
@RunWith(RobolectricTestRunner::class)
class EditMovieScreenImeOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withTheKeyboardUp_theFormStaysReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheKeyboard {
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
    }
}
