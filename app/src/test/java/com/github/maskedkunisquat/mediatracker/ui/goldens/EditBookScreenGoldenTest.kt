package com.github.maskedkunisquat.mediatracker.ui.goldens

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
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline golden for edit book — the one screen that is part scrolling form, part pinned bar.
 *
 * This is the arrangement #99 found a real defect in and fixed on a device: with the keyboard up,
 * the pinned Save/Cancel `Surface` had already been lifted clear by `imePadding()`, and the bar
 * inset was being added on top of that, leaving a dead strip of elevated surface below the buttons.
 * The fix was to subtract the IME from the bar inset, and what it produced is a *visual*
 * relationship — an elevated bar that reaches the window edge while its buttons sit above the
 * navigation bar. A bounds assertion says the buttons are reachable; only the image says the
 * surface behind them is not a floating slab with a gap under it.
 *
 * Recorded with no keyboard, which is the state the strip appeared in. The keyboard half is not
 * screenshotable here at all — Robolectric reports an IME inset but renders no keyboard, so a
 * golden of that state would be a picture of a form with unexplained empty space.
 *
 * Paired with a tag assertion (#102 rule 1), falsified by dropping `TestTags.EditBook.SAVE_BUTTON`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditBookScreenGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editBook() {
        composeRule.captureGolden(
            name = "edit-book",
            alsoAssert = {
                assertTagsExist(
                    TestTags.EditBook.FORM,
                    TestTags.EditBook.SAVE_BUTTON,
                    TestTags.EditBook.CANCEL_BUTTON,
                )
            },
        ) { Fixture() }
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
}
