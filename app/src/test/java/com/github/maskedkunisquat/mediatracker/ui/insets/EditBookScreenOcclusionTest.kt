package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.screens.EditBookScreen
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.ui.EditBookUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * IME-occlusion guard for the Edit Book screen's form.
 *
 * This is the closest analogue on this screen list to the FAB regression
 * [LibraryScreenOcclusionTest] guards: [EditBookScreen]'s Save/Cancel action bar
 * (`EditBookBottomBar`) is a pinned, non-scrolling `Surface` sitting as a sibling of --  not nested
 * inside -- the form's scrolling `Column`, so those two buttons are not exempt from the occlusion
 * check the way a scrolled field would be. If the keyboard covered them, this is the test that would
 * catch it.
 *
 * Uses [EditBookUiState.Ready] with every field populated (mirroring this file's own
 * `PREVIEW_READY_STATE` preview fixture) so the form renders its full four-section layout rather
 * than the sparser "several fields blank" shape.
 */
@RunWith(RobolectricTestRunner::class)
class EditBookScreenOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withTheKeyboardUp_theFormAndSaveCancelBarStayReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheKeyboard(expectedTags = TAGS) { Fixture() }
    }

    /**
     * The navigation bar half, added with #99.
     *
     * Not redundant with the test above: after #99 this screen sends the two insets down different
     * paths -- `imePadding()` on the container for the keyboard, `barPadding(Bottom)` on the
     * Save/Cancel `Surface` for the bar -- so getting one right says nothing about the other.
     * Deleting the `Surface`'s bar padding leaves the keyboard test green and fails this one.
     */
    @Test
    fun withTheNavigationBarShowing_theSaveCancelBarStaysAboveIt() {
        composeRule.assertNoInteractiveNodeIsBehindTheNavigationBar(expectedTags = TAGS) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        EditBookScreen(
            uiState =
                EditBookUiState.Ready(
                    title = "The Great Gatsby",
                    releaseYear = 1925,
                    purchasePrice = 9.99,
                    totalPages = 180,
                    format = BookFormat.PHYSICAL,
                    status = ReadingStatus.TO_READ,
                    trackingMode = TrackingMode.PAGES,
                ),
            onNavigateBack = {},
            onSave = { _, _, _, _, _, _, _ -> },
        )
    }

    private companion object {
        /** Named here rather than centrally so a dropped tag fails the screen that owns it. */
        val TAGS =
            listOf(
                TestTags.EditBook.FORM,
                TestTags.EditBook.SAVE_BUTTON,
                TestTags.EditBook.CANCEL_BUTTON,
            )
    }
}
