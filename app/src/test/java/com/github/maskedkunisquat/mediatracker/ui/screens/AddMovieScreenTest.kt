package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.ui.AddMovieUiState
import com.hub.media.ui.FilmFormState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Behavioural tests for adding a film by hand (ROADMAP Task 13 Phase B; the edit form's layout
 * since #214).
 *
 * Drives the stateless [AddMovieScreen] with a [FilmFormState] the test holds itself, so typing
 * round-trips through the same callbacks the view model receives. Parsing and the blank-vs-
 * unreadable rule are [FilmFormState]'s and are tested there (`FilmFormStateTest`); what is
 * asserted here is that this screen renders the shared form, wires it, and gates Save on it. Both
 * halves of the gate are asserted, because a button that never enables would pass a disabled-only
 * check.
 */
@RunWith(RobolectricTestRunner::class)
class AddMovieScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val saveButton get() = composeRule.onNodeWithTag(TestTags.AddMovie.SAVE_BUTTON)

    private var form by mutableStateOf(FilmFormState())

    private fun setContent(
        uiState: AddMovieUiState = AddMovieUiState.Idle,
        onSave: () -> Unit = {},
        onStatusChange: (WatchStatus) -> Unit = { form = form.choosingStatus(it) },
        onErrorShown: () -> Unit = {},
        onNavigateBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                AddMovieScreen(
                    uiState = uiState,
                    form = form,
                    onTitleChange = { form = form.copy(title = it) },
                    onReleaseYearChange = { form = form.copy(releaseYear = it) },
                    onRuntimeChange = { form = form.copy(runtimeMinutes = it) },
                    onPurchasePriceChange = { form = form.copy(purchasePrice = it) },
                    onStatusChange = onStatusChange,
                    onSave = onSave,
                    onErrorShown = onErrorShown,
                    onNavigateBack = onNavigateBack,
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

        saveButton.assertIsNotEnabled()
        typeIntoField(R.string.add_movie_field_title, "Dune")
        saveButton.assertIsEnabled()
    }

    /** #214: the edit form's cards and its pinned Cancel/Save, not a flat column and an inline button. */
    @Test
    fun form_isInCards_withSaveAndCancelPinned() {
        setContent()

        composeRule.onNodeWithText(context.getString(R.string.edit_section_details)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.edit_section_status)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.edit_section_purchase)).assertExists()
        saveButton.assertExists()
        composeRule.onNodeWithTag(TestTags.AddMovie.CANCEL_BUTTON).assertExists()
    }

    @Test
    fun cancel_navigatesBack() {
        var backs = 0
        setContent(onNavigateBack = { backs++ })

        composeRule.onNodeWithTag(TestTags.AddMovie.CANCEL_BUTTON).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun typedFields_reachTheForm_andSaveIsSent() {
        var saves = 0
        setContent(onSave = { saves++ })

        typeIntoField(R.string.add_movie_field_title, "Oppenheimer")
        typeIntoField(R.string.add_movie_field_year, "2023")
        typeIntoField(R.string.add_movie_field_runtime, "180")
        saveButton.performClick()

        assertEquals(1, saves)
        assertEquals("Oppenheimer", form.title)
        assertEquals("2023", form.releaseYear)
        assertEquals("180", form.runtimeMinutes)
    }

    @Test
    fun status_isChosenFromTheDropdown() {
        var chosen: WatchStatus? = null
        setContent(onStatusChange = { chosen = it })
        val watching = context.getString(R.string.watch_status_watching)
        // Positive control for the dropdown: the option is not on screen until the chip is opened.
        composeRule.onNodeWithText(watching).assertDoesNotExist()

        composeRule.onNodeWithText(context.getString(R.string.watch_status_watchlist)).performClick()
        composeRule.onNodeWithText(watching).performClick()

        assertEquals(WatchStatus.WATCHING, chosen)
    }

    @Test
    fun whenStateIsSaving_theWholeFormIsDisabled_notJustTheSaveButton() {
        form = FilmFormState(title = "Dune")
        setContent(uiState = AddMovieUiState.Saving)

        saveButton.assertIsNotEnabled()
        // The fields matter as much as the button: the values were read at click time, so a form
        // that stayed editable mid-save would accept edits the in-flight write cannot include. A
        // disabled field drops its set-text action, so "no title field accepts text" is the
        // behaviour; the typed title still showing is the positive control.
        val titleLabel = context.getString(R.string.add_movie_field_title)
        composeRule.onNode(hasText(titleLabel) and hasSetTextAction()).assertDoesNotExist()
        composeRule.onNode(hasText("Dune") and hasText(titleLabel)).assertExists()
    }

    @Test
    fun aNumberTooLargeToParse_blocksSavingRatherThanBeingSentAsUnknown() {
        var saves = 0
        setContent(onSave = { saves++ })

        typeIntoField(R.string.add_movie_field_title, "Dune")
        // Digits only, so the input filter lets it through, but it does not fit in an Int. Sent as
        // toIntOrNull()'s null it would be "unknown", and the typed year would vanish unsaid.
        typeIntoField(R.string.add_movie_field_year, "19999999999")

        saveButton.assertIsNotEnabled()
        assertEquals(0, saves)
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

    /** See AddTVShowScreenTest.anEmptyForm_doesNotFlagTheTitle: an empty add form is not a mistake. */
    @Test
    fun anEmptyForm_doesNotFlagTheTitle() {
        setContent()

        val titleLabel = context.getString(R.string.add_movie_field_title)
        composeRule
            .onNode(hasText(titleLabel) and hasSetTextAction())
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
    }
}
