package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.screens.AddMovieScreen
import com.hub.media.ui.AddMovieUiState
import com.hub.media.ui.FilmFormState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Occlusion guards for adding a film by hand.
 *
 * Since #214 this screen is Edit film's shape: a scrolling form and a Cancel/Save bar pinned to the
 * bottom edge. That bar is what makes the navigation bar an inset this screen can lose a control
 * behind. Before it, Save sat at the foot of the form, which ended well short of the bar, and a
 * navigation-bar test here passed with `barPadding` returning zero -- a green no-op -- so there was
 * none. The one below was falsified by zeroing the pinned bar's padding.
 */
@RunWith(RobolectricTestRunner::class)
class AddMovieScreenOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withTheKeyboardUp_theFormStaysReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheKeyboard(expectedTags = TAGS) { Fixture() }
    }

    @Test
    fun withTheNavigationBarShowing_theSaveCancelBarStaysAboveIt() {
        composeRule.assertNoInteractiveNodeIsBehindTheNavigationBar(expectedTags = TAGS) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        AddMovieScreen(
            uiState = AddMovieUiState.Idle,
            form = FilmFormState(title = "Dune", releaseYear = "2021", runtimeMinutes = "155"),
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
                TestTags.AddMovie.FORM,
                TestTags.AddMovie.SAVE_BUTTON,
                TestTags.AddMovie.CANCEL_BUTTON,
            )
    }
}
