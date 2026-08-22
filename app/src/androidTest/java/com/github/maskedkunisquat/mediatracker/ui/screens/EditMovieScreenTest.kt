package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.ui.EditMovieUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Behavioural tests for the Edit Movie screen (ROADMAP Task 13 Phase B).
 *
 * Drives the stateless [EditMovieScreen] with fabricated [EditMovieUiState] and fake callbacks,
 * the same way [AddMovieScreenTest] drives the add form.
 *
 * The focus is the save control's enabled/disabled halves, because this screen's numeric fields
 * carry three states that two of them collapse under a naive reading: blank ("unknown", a
 * legitimate thing to save), parseable, and *unparseable*. Sending the third as `null` would erase
 * the stored value rather than correct it -- and this is the only screen where that erases
 * something, since a movie edited here already exists. Both halves are asserted: a form that never
 * enabled its button would pass a disabled-only check while being useless.
 *
 * Typing is deliberately not used to set up these cases. [EditMovieScreen] is stateless -- its
 * fields render `uiState`, and edits leave via callbacks -- so a typed character would not come
 * back as a new value unless the test re-fed the state itself. Constructing the state directly
 * states the case being tested without that round trip.
 */
class EditMovieScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    /**
     * Matches the "Save changes" button specifically -- [Role.Button] alone also matches the top
     * bar's back [androidx.compose.material3.IconButton], which is told apart by the content
     * description the save button does not have. Needed for the saving-state test, where the label
     * is replaced by a [androidx.compose.material3.CircularProgressIndicator] and cannot be found
     * by text. Same matcher [AddMovieScreenTest] uses, for the same reason.
     */
    private val saveButtonMatcher =
        hasRole(Role.Button) and SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription)

    private fun editing(
        title: String = "Interstellar",
        releaseYear: String = "2014",
        runtimeMinutes: String = "169",
        purchasePrice: String = "14.99",
        status: WatchStatus = WatchStatus.WATCHLIST,
        isSaving: Boolean = false,
        saveError: String? = null,
    ) = EditMovieUiState.Editing(
        title = title,
        releaseYear = releaseYear,
        runtimeMinutes = runtimeMinutes,
        purchasePrice = purchasePrice,
        status = status,
        isSaving = isSaving,
        saveError = saveError,
    )

    private fun setContent(
        uiState: EditMovieUiState,
        onSave: () -> Unit = {},
        onErrorShown: () -> Unit = {},
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                EditMovieScreen(
                    uiState = uiState,
                    onTitleChange = {},
                    onReleaseYearChange = {},
                    onRuntimeChange = {},
                    onPurchasePriceChange = {},
                    onStatusChange = {},
                    onSave = onSave,
                    onErrorShown = onErrorShown,
                    onNavigateBack = {},
                )
            }
        }
    }

    @Test
    fun aFullyValidForm_canBeSaved() {
        setContent(editing())

        composeRule.onNode(saveButtonMatcher).assertIsEnabled()
    }

    @Test
    fun clearedNumericFields_canStillBeSaved() {
        // Emptying a field means "unknown" and is a correction in its own right -- if the
        // parseability gate blocked this, it would have traded a silent erase for a stuck form.
        setContent(editing(releaseYear = "", runtimeMinutes = "", purchasePrice = ""))

        composeRule.onNode(saveButtonMatcher).assertIsEnabled()
    }

    @Test
    fun blankTitle_blocksSaving() {
        setContent(editing(title = "   "))

        composeRule.onNode(saveButtonMatcher).assertIsNotEnabled()
    }

    @Test
    fun aReleaseYearTooLargeToParse_blocksSavingRatherThanErasingTheStoredYear() {
        // Digits only, so the field's own input filter admits it, but it does not fit in an Int.
        // toIntOrNull()'s null is this codebase's spelling of "unknown", so saving would replace
        // the stored 2014 with nothing and say so nowhere.
        setContent(editing(releaseYear = "19999999999"))

        composeRule.onNode(saveButtonMatcher).assertIsNotEnabled()
    }

    @Test
    fun anUnreadablePurchasePrice_blocksSavingRatherThanErasingTheStoredPrice() {
        setContent(editing(purchasePrice = "14.9.9"))

        composeRule.onNode(saveButtonMatcher).assertIsNotEnabled()
    }

    @Test
    fun anUnreadableField_marksThatFieldRatherThanFailingSilently() {
        // A disabled save button with no indication of why is its own dead end: the error state is
        // what tells the user which of four fields to go fix.
        setContent(editing(runtimeMinutes = "19999999999"))

        composeRule.onNodeWithText(context.getString(R.string.add_movie_runtime_invalid_error)).assertIsDisplayed()
    }

    @Test
    fun whileSaving_theWholeFormIsDisabled_notJustTheSaveButton() {
        setContent(editing(isSaving = true))

        composeRule.onNode(saveButtonMatcher).assertIsNotEnabled()

        // The fields and chips matter as much as the button. The values being written were read
        // when save was pressed, so an edit accepted mid-save is one the in-flight write cannot
        // include -- and this screen leaves on success, so that edit would be discarded by a
        // navigation the user reads as confirmation.
        val titleLabel = context.getString(R.string.add_movie_field_title)
        composeRule.onNode(hasText(titleLabel) and hasSetTextAction()).assertDoesNotExist()

        composeRule.onNodeWithText(context.getString(R.string.watch_status_watching)).assertIsNotEnabled()
    }

    @Test
    fun saveError_surfacesTheMessageAndInvokesOnErrorShown() {
        var errorShownCount = 0
        setContent(
            editing(saveError = "Could not save movie"),
            onErrorShown = { errorShownCount++ },
        )

        composeRule.onNodeWithText("Could not save movie").assertIsDisplayed()

        // onErrorShown() fires only once showSnackbar()'s suspend call returns, i.e. after the
        // snackbar's duration elapses -- so this waits rather than asserting immediately.
        composeRule.waitUntil(timeoutMillis = 10_000) { errorShownCount == 1 }
        assertEquals(1, errorShownCount)
    }

    @Test
    fun loadingState_showsNoFormAndNoSaveControl() {
        setContent(EditMovieUiState.Loading)

        composeRule.onNode(saveButtonMatcher).assertDoesNotExist()
    }

    @Test
    fun tappingSave_invokesOnSave() {
        var saves = 0
        setContent(editing(), onSave = { saves++ })

        composeRule.onNode(saveButtonMatcher).performClick()

        assertEquals(1, saves)
    }
}
