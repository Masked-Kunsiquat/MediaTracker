package com.github.maskedkunisquat.mediatracker.ui.goldens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.screens.MismatchReviewScreen
import com.hub.media.features.media.domain.MismatchReviewRow
import com.hub.media.ui.MismatchReviewUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline golden for the episode-count reconciliation screen (#123).
 *
 * The fixture is the two shapes a real library actually produced, not invented ones: an **empty**
 * season (Fleabag S2, 0 against 6) and a **partial** one (Judy Justice S4, 82 against 90), plus an
 * over-count that must render as a sentence rather than a button.
 *
 * That third row is why this golden is worth having. "There is no button here" is the safety
 * property this screen rests on — removing episodes destroys watch dates, and #88 showed TMDB's own
 * counts can be lower than the truth — and it is invisible to an assertion that only checks what
 * *is* present. A regression that added an action to that card would leave every text assertion
 * passing and change this image.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MismatchReviewScreenGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mismatchReview() {
        composeRule.captureGolden(
            name = "mismatch-review",
            alsoAssert = {
                assertTagsExist(TestTags.MismatchReview.LIST)
                // Both add labels, because they are deliberately different sentences: an empty
                // season reads "Add all 6" and a partial one "Add 8 missing". Wording them the same
                // would make the empty case sound like a top-up, and only this catches it.
                assertTextIsShown("Add all 6", "Add 8 missing")
            },
        ) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        MismatchReviewScreen(
            uiState =
                MismatchReviewUiState(
                    rows =
                        listOf(
                            MismatchReviewRow(
                                mediaId = "fleabag",
                                showTitle = "Fleabag",
                                seasonNumber = 2,
                                localEpisodes = 0,
                                providerEpisodes = 6,
                            ),
                            MismatchReviewRow(
                                mediaId = "judy",
                                showTitle = "Judy Justice",
                                seasonNumber = 4,
                                localEpisodes = 82,
                                providerEpisodes = 90,
                            ),
                            MismatchReviewRow(
                                mediaId = "expanse",
                                showTitle = "The Expanse",
                                seasonNumber = 1,
                                localEpisodes = 12,
                                providerEpisodes = 10,
                            ),
                        ),
                    isLoading = false,
                ),
            onAddMissing = {},
            onNavigateBack = {},
        )
    }
}
