package com.github.maskedkunisquat.mediatracker.ui.screens

import android.util.Base64
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
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.features.books.domain.BookDraft
import com.hub.media.features.books.domain.BookFormValues
import com.hub.media.features.books.network.BookMetadata
import com.hub.media.ui.AddBookUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The review a looked-up book opens into before it is added (#214).
 *
 * Drives the stateless [AddBookReviewScreen] with a fabricated [AddBookUiState.Reviewing]. The form
 * inside is Edit book's, whose field rules `EditBookScreenTest` covers; what is asserted here is
 * this screen's contract -- it opens filled in from the lookup, shows the cover the lookup fetched,
 * hands the edited values to Save, and Back cancels the review rather than leaving Add book.
 */
@RunWith(RobolectricTestRunner::class)
class AddBookReviewScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = composeRule.activity

    private fun reviewing(
        pageCount: Int? = 310,
        coverBytes: ByteArray? = null,
    ) = AddBookUiState.Reviewing(
        draft =
            BookDraft(
                normalizedIsbn = "9780547928227",
                metadata =
                    BookMetadata(
                        title = "The Hobbit",
                        releaseYear = 2012,
                        pageCount = pageCount,
                        provider = IdentifierProvider.OPEN_LIBRARY,
                    ),
                coverBytes = coverBytes,
            ),
    )

    private fun setContent(
        state: AddBookUiState.Reviewing,
        onSave: (BookFormValues) -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                AddBookReviewScreen(reviewing = state, onSave = onSave, onCancel = onCancel)
            }
        }
    }

    @Test
    fun review_showsTheLookupsValuesInTheFormsCards() {
        setContent(reviewing())

        val titleLabel = context.getString(R.string.edit_title_label)
        composeRule.onNode(hasText("The Hobbit") and hasText(titleLabel) and hasSetTextAction()).assertExists()
        composeRule.onNode(hasText("310") and hasSetTextAction()).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.edit_section_purchase)).assertExists()
        composeRule.onNodeWithTag(TestTags.AddBook.REVIEW_SAVE).assertIsEnabled()
    }

    /** Open Library sometimes reports 0 pages; the field says so before Save rather than after. */
    @Test
    fun review_aZeroPageCount_showsItsErrorAndBlocksSave() {
        setContent(reviewing(pageCount = 0))

        composeRule.onNodeWithText(context.getString(R.string.edit_total_pages_invalid_error)).assertExists()
        composeRule.onNodeWithTag(TestTags.AddBook.REVIEW_SAVE).assertIsNotEnabled()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun review_showsTheCoverTheLookupFetched() {
        setContent(reviewing(coverBytes = Base64.decode(ONE_PIXEL_PNG, Base64.DEFAULT)))

        composeRule.onNodeWithTag(TestTags.EDIT_HEADER_UNSAVED_COVER).assertExists()
    }

    @Test
    fun review_withNoCover_showsNoArtwork() {
        setContent(reviewing(coverBytes = null))

        composeRule.onNodeWithTag(TestTags.EDIT_HEADER_UNSAVED_COVER).assertDoesNotExist()
        // Positive control: the review itself rendered.
        composeRule.onNodeWithTag(TestTags.AddBook.REVIEW_SAVE).assertExists()
    }

    @Test
    fun save_sendsTheFormsValues() {
        var saved: BookFormValues? = null
        setContent(reviewing(), onSave = { saved = it })

        composeRule.onNodeWithTag(TestTags.AddBook.REVIEW_SAVE).performClick()

        assertEquals("The Hobbit", saved?.title)
        assertEquals(310, saved?.totalPages)
        assertEquals(2012, saved?.releaseYear)
    }

    @Test
    fun cancel_cancelsTheReview() {
        var cancels = 0
        setContent(reviewing(), onCancel = { cancels++ })

        composeRule.onNodeWithTag(TestTags.AddBook.REVIEW_CANCEL).performClick()

        assertEquals(1, cancels)
    }

    @Test
    fun systemBack_cancelsTheReview_ratherThanLeavingAddBook() {
        var cancels = 0
        setContent(reviewing(), onCancel = { cancels++ })

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(1, cancels)
    }

    private companion object {
        /** A valid 1x1 PNG, so the header has real bytes to decode. */
        const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
    }
}
