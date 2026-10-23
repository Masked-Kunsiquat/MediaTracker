package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.features.movies.domain.TmdbMovieMapping
import com.hub.media.ui.FilmReview
import com.hub.media.ui.toFilmForm
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The review a TMDB film opens into before anything is saved (#214).
 *
 * Drives the stateless [MovieReviewScreen] with a fabricated [FilmReview]. The form inside is the
 * shared film form, whose field rules `EditMovieScreenTest` already covers; what is asserted here is
 * this screen's own contract -- it opens prefilled, Save and Cancel are pinned and wired, and Back
 * cancels the review rather than leaving the add flow.
 *
 * `createAndroidComposeRule` rather than `createComposeRule` so the test can press the system Back
 * through the activity's own dispatcher, which is what a `BackHandler` listens to.
 */
@RunWith(RobolectricTestRunner::class)
class MovieReviewScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = composeRule.activity

    private val matrix =
        TmdbMovieMapping(title = "The Matrix", releaseYear = 1999, runtimeMinutes = 136)

    private fun review(
        mapping: TmdbMovieMapping = matrix,
        releaseYear: String? = null,
        isSaving: Boolean = false,
    ) = FilmReview(
        mapping = mapping,
        form = mapping.toFilmForm().let { if (releaseYear != null) it.copy(releaseYear = releaseYear) else it },
        isSaving = isSaving,
    )

    private fun setContent(
        review: FilmReview,
        onSave: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                MovieReviewScreen(
                    review = review,
                    onTitleChange = {},
                    onReleaseYearChange = {},
                    onRuntimeChange = {},
                    onPurchasePriceChange = {},
                    onStatusChange = {},
                    onSave = onSave,
                    onCancel = onCancel,
                    onErrorShown = {},
                )
            }
        }
    }

    @Test
    fun review_showsTmdbsValuesInTheFormsCards() {
        setContent(review())

        val titleLabel = context.getString(R.string.add_movie_field_title)
        composeRule.onNode(hasText("The Matrix") and hasText(titleLabel) and hasSetTextAction()).assertExists()
        composeRule.onNode(hasText("1999") and hasSetTextAction()).assertExists()
        composeRule.onNode(hasText("136") and hasSetTextAction()).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.edit_section_status)).assertExists()
        composeRule.onNodeWithTag(TestTags.MovieSearch.REVIEW_SAVE).assertIsEnabled()
    }

    @Test
    fun review_aYearOutOfRange_showsItsErrorAndBlocksSave() {
        setContent(review(releaseYear = "1700"))

        composeRule.onNodeWithTag(TestTags.MovieSearch.REVIEW_SAVE).assertIsNotEnabled()
    }

    @Test
    fun save_isSent() {
        var saves = 0
        setContent(review(), onSave = { saves++ })

        composeRule.onNodeWithTag(TestTags.MovieSearch.REVIEW_SAVE).performClick()

        assertEquals(1, saves)
    }

    @Test
    fun cancel_cancelsTheReview() {
        var cancels = 0
        setContent(review(), onCancel = { cancels++ })

        composeRule.onNodeWithTag(TestTags.MovieSearch.REVIEW_CANCEL).performClick()

        assertEquals(1, cancels)
    }

    @Test
    fun systemBack_cancelsTheReview_ratherThanLeavingTheAddFlow() {
        var cancels = 0
        setContent(review(), onCancel = { cancels++ })

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(1, cancels)
    }
}
