@file:OptIn(ExperimentalTime::class)

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
import com.hub.media.features.tv.data.NewEpisode
import com.hub.media.features.tv.data.SeasonEpisodes
import com.hub.media.features.tv.domain.TmdbShowMapping
import com.hub.media.ui.ShowReview
import com.hub.media.ui.toShowForm
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.ExperimentalTime

/**
 * The review a TMDB show opens into before anything is saved (#214) -- the show counterpart of
 * [MovieReviewScreenTest]. What is show-specific is the Seasons card: TMDB's seasons arrive whole,
 * with episode titles, so the card summarises them instead of offering quick-fill rows that could
 * hold only a count.
 */
@RunWith(RobolectricTestRunner::class)
class TVShowReviewScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = composeRule.activity

    private val chernobyl =
        TmdbShowMapping(
            title = "Chernobyl",
            releaseYear = 2019,
            totalSeasons = 1,
            seasons = listOf(SeasonEpisodes(1, listOf(NewEpisode(1), NewEpisode(2)))),
        )

    private fun review(releaseYear: String? = null) =
        ShowReview(
            mapping = chernobyl,
            form = chernobyl.toShowForm().let { if (releaseYear != null) it.copy(releaseYear = releaseYear) else it },
        )

    private fun setContent(
        review: ShowReview,
        onSave: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                TVShowReviewScreen(
                    review = review,
                    onTitleChange = {},
                    onReleaseYearChange = {},
                    onTotalSeasonsChange = {},
                    onPurchasePriceChange = {},
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

        val titleLabel = context.getString(R.string.add_tv_show_field_title)
        composeRule.onNode(hasText("Chernobyl") and hasText(titleLabel) and hasSetTextAction()).assertExists()
        composeRule.onNode(hasText("2019") and hasSetTextAction()).assertExists()
        composeRule.onNodeWithTag(TestTags.TVShowSearch.REVIEW_SAVE).assertIsEnabled()
    }

    @Test
    fun review_summarisesTmdbsSeasons_andOffersNoQuickFillRows() {
        setContent(review())

        composeRule.onNodeWithText("1 season, 2 episodes from TMDB").assertExists()
        composeRule.onNodeWithText(context.getString(R.string.add_tv_show_add_season)).assertDoesNotExist()
    }

    @Test
    fun review_aYearOutOfRange_blocksSave() {
        setContent(review(releaseYear = "1700"))

        composeRule.onNodeWithTag(TestTags.TVShowSearch.REVIEW_SAVE).assertIsNotEnabled()
    }

    @Test
    fun save_isSent() {
        var saves = 0
        setContent(review(), onSave = { saves++ })

        composeRule.onNodeWithTag(TestTags.TVShowSearch.REVIEW_SAVE).performClick()

        assertEquals(1, saves)
    }

    @Test
    fun cancel_cancelsTheReview() {
        var cancels = 0
        setContent(review(), onCancel = { cancels++ })

        composeRule.onNodeWithTag(TestTags.TVShowSearch.REVIEW_CANCEL).performClick()

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
