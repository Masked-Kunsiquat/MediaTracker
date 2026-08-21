package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.ui.AddMovieUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Behavioural tests for the Add Movie screen (ROADMAP Task 13 Phase B).
 *
 * Drives the stateless [AddMovieScreen] with fabricated state and fake callbacks. Two things get
 * the most attention here because they are the ones a naive implementation gets wrong silently:
 * the save control's enabled/disabled halves (a button that never enables would pass an
 * enabled-only check), and blank-numeric-field handling, where this codebase treats `null`
 * ("unknown") and `0` (a real, claimed value) as meaningfully different -- see
 * [com.hub.media.core.database.entities.MovieDetailsEntity.runtimeMinutes]'s KDoc.
 *
 * Title trimming is deliberately **not** asserted here: reading [AddMovieScreen]'s save handler
 * shows it forwards the typed title as-is (`onSave(title, ...)`, no `.trim()`); the trim happens
 * one layer up, in `AddMovieViewModel.save()`. Asserting a trim at this layer would be testing a
 * contract the stateless screen does not actually have.
 */
class AddMovieScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    /**
     * Matches the "Save movie" button specifically. [Role.Button] alone is ambiguous -- the
     * top bar's back [androidx.compose.material3.IconButton] is a [Role.Button] too -- so this
     * also requires the absence of a content description, which the back button has
     * ("Navigate back") and the save button does not. Needed for the Saving-state test, where the
     * button's label is swapped for a [androidx.compose.material3.CircularProgressIndicator] and
     * so cannot be located by text.
     */
    private val saveButtonMatcher =
        hasRole(Role.Button) and SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription)

    private fun setContent(
        uiState: AddMovieUiState = AddMovieUiState.Idle,
        onSave: (String, Int?, Int?, Double?, WatchStatus) -> Unit = { _, _, _, _, _ -> },
        onErrorShown: () -> Unit = {},
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                AddMovieScreen(
                    uiState = uiState,
                    onSave = onSave,
                    onErrorShown = onErrorShown,
                    onNavigateBack = {},
                )
            }
        }
    }

    private fun typeIntoField(
        labelRes: Int,
        text: String,
    ) {
        val label = context.getString(labelRes)
        composeRule.onNode(hasText(label) and hasSetTextAction()).performTextInput(text)
    }

    @Test
    fun saveButton_isDisabledWhenTitleIsBlank_andEnabledOnceATitleIsEntered() {
        setContent()

        composeRule.onNode(saveButtonMatcher).assertIsNotEnabled()

        typeIntoField(R.string.add_movie_field_title, "Dune")

        composeRule.onNode(saveButtonMatcher).assertIsEnabled()
    }

    @Test
    fun savingWithBlankNumericFields_passesNullRatherThanZero() {
        var capturedYear: Int? = -1
        var capturedRuntime: Int? = -1
        var capturedPrice: Double? = -1.0
        setContent(
            onSave = { _, year, runtime, price, _ ->
                capturedYear = year
                capturedRuntime = runtime
                capturedPrice = price
            },
        )

        // Title is the only required field; year, runtime and price are left blank.
        typeIntoField(R.string.add_movie_field_title, "Arrival")

        val saveText = context.getString(R.string.add_movie_save)
        composeRule.onNode(hasText(saveText) and hasClickAction()).performClick()

        assertNull("blank release year must arrive as null, not 0", capturedYear)
        assertNull("blank runtime must arrive as null, not 0", capturedRuntime)
        assertNull("blank price must arrive as null, not 0", capturedPrice)
    }

    @Test
    fun savingWithFilledFields_passesThemParsedAsIntAndDouble() {
        var capturedTitle = ""
        var capturedYear: Int? = null
        var capturedRuntime: Int? = null
        var capturedPrice: Double? = null
        setContent(
            onSave = { title, year, runtime, price, _ ->
                capturedTitle = title
                capturedYear = year
                capturedRuntime = runtime
                capturedPrice = price
            },
        )

        typeIntoField(R.string.add_movie_field_title, "Dune Part Two")
        typeIntoField(R.string.add_movie_field_year, "2024")
        typeIntoField(R.string.add_movie_field_runtime, "166")
        typeIntoField(R.string.add_movie_field_price, "19.99")

        val saveText = context.getString(R.string.add_movie_save)
        composeRule.onNode(hasText(saveText) and hasClickAction()).performClick()

        assertEquals("Dune Part Two", capturedTitle)
        assertEquals(2024, capturedYear)
        assertEquals(166, capturedRuntime)
        assertEquals(19.99, capturedPrice!!, 0.0001)
    }

    @Test
    fun selectingAStatusChip_changesTheStatusPassedToOnSave() {
        var capturedStatus: WatchStatus? = null
        setContent(onSave = { _, _, _, _, status -> capturedStatus = status })

        typeIntoField(R.string.add_movie_field_title, "Oppenheimer")

        // WATCHLIST is the default selection; picking a different chip must change what is saved.
        val watchingLabel = context.getString(R.string.watch_status_watching)
        composeRule.onNodeWithText(watchingLabel).performClick()

        val saveText = context.getString(R.string.add_movie_save)
        composeRule.onNode(hasText(saveText) and hasClickAction()).performClick()

        assertEquals(WatchStatus.WATCHING, capturedStatus)
    }

    @Test
    fun whenStateIsSaving_theSaveControlIsDisabledEvenWithATitleTyped() {
        setContent(uiState = AddMovieUiState.Saving)

        // The text fields are not gated on isSaving, only the button is -- confirm a typed title
        // still cannot be submitted while a save is already in flight (no double-submit).
        typeIntoField(R.string.add_movie_field_title, "Dune")

        composeRule.onNode(saveButtonMatcher).assertIsNotEnabled()
    }

    @Test
    fun whenStateIsError_surfacesTheMessageAndInvokesOnErrorShown() {
        var errorShownCount = 0
        setContent(
            uiState = AddMovieUiState.Error("Could not save movie"),
            onErrorShown = { errorShownCount++ },
        )

        composeRule.onNodeWithText("Could not save movie").assertIsDisplayed()

        // onErrorShown() fires only after the snackbar's showSnackbar() suspend call returns
        // (i.e. once its duration elapses), so this needs to wait rather than assert immediately.
        composeRule.waitUntil(timeoutMillis = 10_000) { errorShownCount == 1 }
        assertEquals(1, errorShownCount)
    }
}
