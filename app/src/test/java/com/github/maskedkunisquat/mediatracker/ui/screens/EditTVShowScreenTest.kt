package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.ui.EditTVShowUiState
import org.junit.Assert.assertEquals
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

    /** The Save button: a Button with no content description, unlike the top bar's back icon. */
    private val saveButtonMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and
            SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription)

    private fun editing(
        title: String = "Chernobyl",
        releaseYear: String = "2019",
        totalSeasons: String = "1",
        purchasePrice: String = "19.99",
        saveError: String? = null,
    ) = EditTVShowUiState.Editing(
        title = title,
        releaseYear = releaseYear,
        totalSeasons = totalSeasons,
        purchasePrice = purchasePrice,
        saveError = saveError,
    )

    private fun setContent(
        uiState: EditTVShowUiState,
        onSave: () -> Unit = {},
        onErrorShown: () -> Unit = {},
        onTotalSeasonsChange: (String) -> Unit = {},
        onPurchasePriceChange: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                EditTVShowScreen(
                    uiState = uiState,
                    onTitleChange = {},
                    onReleaseYearChange = {},
                    onTotalSeasonsChange = onTotalSeasonsChange,
                    onPurchasePriceChange = onPurchasePriceChange,
                    onSave = onSave,
                    onErrorShown = onErrorShown,
                    onRetry = {},
                    onNavigateBack = {},
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
}
