package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.screens.AddBookReviewScreen
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.features.books.domain.BookDraft
import com.hub.media.features.books.network.BookMetadata
import com.hub.media.ui.AddBookUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Occlusion guards for the add-book review (#214): Edit book's shape, a scrolling form above a
 * Cancel/Save bar pinned to the bottom edge, so both the keyboard and the navigation bar can strand
 * a control. The navigation-bar case was falsified by zeroing the pinned bar's padding.
 */
@RunWith(RobolectricTestRunner::class)
class AddBookReviewScreenOcclusionTest {
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
        AddBookReviewScreen(
            reviewing =
                AddBookUiState.Reviewing(
                    BookDraft(
                        normalizedIsbn = "9780547928227",
                        metadata =
                            BookMetadata(
                                title = "The Hobbit",
                                releaseYear = 2012,
                                pageCount = 310,
                                provider = IdentifierProvider.OPEN_LIBRARY,
                            ),
                        coverBytes = null,
                    ),
                ),
            onSave = {},
            onCancel = {},
        )
    }

    private companion object {
        /** Named here rather than centrally so a dropped tag fails the screen that owns it. */
        val TAGS =
            listOf(
                TestTags.AddBook.REVIEW_FORM,
                TestTags.AddBook.REVIEW_SAVE,
                TestTags.AddBook.REVIEW_CANCEL,
            )
    }
}
