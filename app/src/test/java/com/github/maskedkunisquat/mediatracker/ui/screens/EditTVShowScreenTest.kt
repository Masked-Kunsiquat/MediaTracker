package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.ui.EditTVShowUiState
import com.hub.media.ui.ShowFormState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Behavioural tests for the Edit Show screen (#173), driving the stateless [EditTVShowScreen] the
 * way [EditMovieScreenTest] drives the film form. Both halves of the save control are asserted:
 * a form that never enabled Save would pass a disabled-only check while being useless.
 */
@RunWith(RobolectricTestRunner::class)
class EditTVShowScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Save, by tag. "A button with no content description" told it apart from the back arrow
     * until #174 put Cancel beside it, which matches that too -- a test could pass by tapping Cancel.
     */
    private val saveButtonMatcher = hasTestTag(TestTags.EditTVShow.SAVE_BUTTON)

    private fun editing(
        title: String = "Chernobyl",
        releaseYear: String = "2019",
        totalSeasons: String = "1",
        purchasePrice: String = "19.99",
        saveError: String? = null,
    ) = EditTVShowUiState.Editing(
        form =
            ShowFormState(
                title = title,
                releaseYear = releaseYear,
                totalSeasons = totalSeasons,
                purchasePrice = purchasePrice,
            ),
        saveError = saveError,
    )

    private fun setContent(
        uiState: EditTVShowUiState,
        onSave: () -> Unit = {},
        onErrorShown: () -> Unit = {},
        onTotalSeasonsChange: (String) -> Unit = {},
        onPurchasePriceChange: (String) -> Unit = {},
        onNavigateBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                EditTVShowScreen(
                    uiState = uiState,
                    coverStorageDir = "",
                    onTitleChange = {},
                    onReleaseYearChange = {},
                    onTotalSeasonsChange = onTotalSeasonsChange,
                    onPurchasePriceChange = onPurchasePriceChange,
                    onSave = onSave,
                    onErrorShown = onErrorShown,
                    onRetry = {},
                    onNavigateBack = onNavigateBack,
                )
            }
        }
    }

    @Test
    fun aValidForm_canBeSaved_andTappingSaveInvokesOnSave() {
        var saves = 0
        setContent(editing(), onSave = { saves++ })

        composeRule.onNode(saveButtonMatcher).assertIsEnabled().performClick()

        assertEquals(1, saves)
    }

    @Test
    fun blankTitle_disablesSave() {
        setContent(editing(title = "   "))

        composeRule.onNode(saveButtonMatcher).assertIsNotEnabled()
    }

    @Test
    fun clearedNumbers_canStillBeSaved() {
        setContent(editing(releaseYear = "", totalSeasons = "", purchasePrice = ""))

        composeRule.onNode(saveButtonMatcher).assertIsEnabled()
    }

    @Test
    fun unreadableTotalSeasons_disablesSave_andSaysWhy() {
        setContent(editing(totalSeasons = "99999999999"))

        composeRule.onNode(saveButtonMatcher).assertIsNotEnabled()
        composeRule
            .onNodeWithText(context.getString(R.string.add_tv_show_total_seasons_invalid_error))
            .assertExists()
    }

    @Test
    fun typingIntoTotalSeasons_forwardsDigitsOnly() {
        var captured: String? = null
        setContent(editing(totalSeasons = ""), onTotalSeasonsChange = { captured = it })

        val label = context.getString(R.string.add_tv_show_field_total_seasons)
        composeRule.onNode(hasText(label) and hasSetTextAction()).performTextInput("3a")

        assertEquals("3", captured)
    }

    @Test
    fun typingIntoThePriceField_forwardsFilteredText() {
        // A comma is a thousands separator in the test device's en-US locale, so it is dropped
        // rather than read as a decimal point (same case as EditMovieScreenTest).
        var captured: String? = null
        setContent(editing(purchasePrice = ""), onPurchasePriceChange = { captured = it })

        val label = context.getString(R.string.add_tv_show_field_price)
        composeRule.onNode(hasText(label) and hasSetTextAction()).performTextInput("1,499.50")

        assertEquals("1499.50", captured)
    }

    @Test
    fun saveError_isShownInASnackbar_andAcknowledged() {
        var acknowledged = 0
        setContent(editing(saveError = "Seasons must be positive"), onErrorShown = { acknowledged++ })

        composeRule.onNodeWithText("Seasons must be positive").assertExists()
        // onErrorShown fires only once showSnackbar's suspend call returns.
        composeRule.waitUntil(timeoutMillis = 10_000) { acknowledged == 1 }
    }

    @Test
    fun header_keepsTheSavedTitle_whileTheFieldSaysSomethingElse() {
        setContent(editing(title = "Chernobil").copy(savedTitle = "Chernobyl", savedYear = 2019))

        composeRule.onNode(hasText("Chernobyl") and isHeading()).assertExists()
        composeRule.onNodeWithText("TV show · 2019").assertExists()
    }

    @Test
    fun cancel_isInTheBottomBar_andNavigatesBack() {
        var backs = 0
        setContent(editing(), onNavigateBack = { backs++ })

        composeRule.onNodeWithTag(TestTags.EditTVShow.CANCEL_BUTTON).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun theFieldsAreGroupedUnderDetailsAndPurchase_withNoStatusSection() {
        setContent(editing())

        composeRule.onNodeWithText(context.getString(R.string.edit_section_details)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.edit_section_purchase)).assertExists()
        // The detail screen owns abandoning a show, so this form has no status to group.
        composeRule.onNodeWithText(context.getString(R.string.edit_section_status)).assertDoesNotExist()
    }

    @Test
    fun saveError_theSnackbarSitsAboveTheSaveCancelBar_soSaveStaysTappable() {
        setContent(editing(saveError = "Could not save"))

        // The bar is pinned in the content since #174, so a Scaffold-placed snackbar lands on top of
        // it -- and a Surface swallows taps, leaving Save unpressable until the snackbar times out.
        val message = composeRule.onNodeWithText("Could not save").fetchSemanticsNode().boundsInRoot
        val save = composeRule.onNodeWithTag(TestTags.EditTVShow.SAVE_BUTTON).fetchSemanticsNode().boundsInRoot
        assertTrue("snackbar $message overlaps Save $save", message.bottom <= save.top)
    }
}
