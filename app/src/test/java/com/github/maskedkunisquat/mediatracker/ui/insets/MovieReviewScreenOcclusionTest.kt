package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.screens.MovieReviewScreen
import com.hub.media.features.movies.domain.TmdbMovieMapping
import com.hub.media.ui.FilmReview
import com.hub.media.ui.toFilmForm
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Occlusion guards for the TMDB film review (#214): Edit film's shape, a scrolling form above a
 * Cancel/Save bar pinned to the bottom edge, so both the keyboard and the navigation bar can strand
 * a control. The navigation-bar case was falsified by zeroing the pinned bar's padding.
 */
@RunWith(RobolectricTestRunner::class)
class MovieReviewScreenOcclusionTest {
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
        val mapping = TmdbMovieMapping(title = "The Matrix", releaseYear = 1999, runtimeMinutes = 136)
        MovieReviewScreen(
            review = FilmReview(mapping, mapping.toFilmForm()),
            onTitleChange = {},
            onReleaseYearChange = {},
            onRuntimeChange = {},
            onPurchasePriceChange = {},
            onStatusChange = {},
            onSave = {},
            onCancel = {},
            onErrorShown = {},
        )
    }

    private companion object {
        /** Named here rather than centrally so a dropped tag fails the screen that owns it. */
        val TAGS =
            listOf(
                TestTags.MovieSearch.REVIEW_FORM,
                TestTags.MovieSearch.REVIEW_SAVE,
                TestTags.MovieSearch.REVIEW_CANCEL,
            )
    }
}
