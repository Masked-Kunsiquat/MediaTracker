package com.github.maskedkunisquat.mediatracker.ui.goldens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.screens.HubScreen
import com.hub.media.ui.HubUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens for the hub (#126), the screen the app now opens on. The default size shows the cards'
 * shape and their numbers; the large font scale is what an invariant cannot state well -- that each
 * card's name and number wrap inside the card rather than clipping or overlapping the emoji.
 *
 * There is deliberately no navigation-bar occlusion test for the hub. One was written at font scale 2
 * and passed with the content's bottom inset removed: even there the cards and rows end well above
 * the bar, so the rule could not fail. If the hub grows past the screen, add it back -- and prove it
 * fails first.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HubScreenGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun hub() {
        composeRule.captureGolden(
            name = "hub",
            alsoAssert = { assertTagsExist(*TAGS) },
        ) { Fixture() }
    }

    @Test
    fun hubLargeFont() {
        composeRule.captureGolden(
            name = "hub-large-font",
            fontScale = LARGE_FONT_SCALE,
            alsoAssert = { assertTagsExist(*TAGS) },
        ) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        HubScreen(
            uiState = HubUiState(isLoading = false, readingBooks = 3, watchlistFilms = 5, showsUpNext = 2),
            onOpenDomain = {},
            onOpenAllMedia = {},
            onOpenStats = {},
            onOpenYearReview = {},
            onOpenSettings = {},
        )
    }

    private companion object {
        /** The same scale the library's large-font golden uses. */
        const val LARGE_FONT_SCALE = 2.0f

        val TAGS =
            arrayOf(
                TestTags.Hub.BOOKS,
                TestTags.Hub.FILMS,
                TestTags.Hub.SHOWS,
                TestTags.Hub.ALL_MEDIA,
                TestTags.Hub.STATS,
            )
    }
}
