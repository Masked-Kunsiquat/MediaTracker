package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.ui.test.junit4.createComposeRule
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
 * [LibraryScreenImeOcclusionTest] guards: [EditBookScreen]'s Save/Cancel action bar
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
class EditBookScreenImeOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withTheKeyboardUp_theFormAndSaveCancelBarStayReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheKeyboard {
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
    }
}
