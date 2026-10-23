@file:OptIn(ExperimentalTime::class)

package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.screens.TVShowReviewScreen
import com.hub.media.features.tv.domain.TmdbShowMapping
import com.hub.media.ui.ShowReview
import com.hub.media.ui.toShowForm
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.ExperimentalTime

/**
 * Occlusion guards for the TMDB show review (#214): Edit show's shape, a scrolling form above a
 * Cancel/Save bar pinned to the bottom edge, so both the keyboard and the navigation bar can strand
 * a control. The navigation-bar case was falsified by zeroing the pinned bar's padding.
 */
@RunWith(RobolectricTestRunner::class)
class TVShowReviewScreenOcclusionTest {
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
        val mapping = TmdbShowMapping(title = "Chernobyl", releaseYear = 2019, totalSeasons = 1)
        TVShowReviewScreen(
            review = ShowReview(mapping, mapping.toShowForm()),
            onTitleChange = {},
            onReleaseYearChange = {},
            onTotalSeasonsChange = {},
            onPurchasePriceChange = {},
            onSave = {},
            onCancel = {},
            onErrorShown = {},
        )
    }

    private companion object {
        /** Named here rather than centrally so a dropped tag fails the screen that owns it. */
        val TAGS =
            listOf(
                TestTags.TVShowSearch.REVIEW_FORM,
                TestTags.TVShowSearch.REVIEW_SAVE,
                TestTags.TVShowSearch.REVIEW_CANCEL,
            )
    }
}
