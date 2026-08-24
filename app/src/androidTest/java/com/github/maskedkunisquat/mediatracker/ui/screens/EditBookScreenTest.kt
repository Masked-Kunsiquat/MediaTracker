package com.github.maskedkunisquat.mediatracker.ui.screens

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
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.features.books.data.BookRepository
import com.hub.media.ui.EditBookUiState
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Behavioural tests for the Edit Book screen (Issue #83, filling the coverage gap the numeric-
 * validation refactor left behind).
 *
 * [EditBookScreen] itself is stateless (uiState in, onSave out), but the fields it renders --
 * [EditBookForm] -- hold their *own* `remember`ed text state, seeded once from
 * [EditBookUiState.Ready] on first composition (see that composable's KDoc, "Field state seeding").
 * That means, unlike [AddTVShowScreenTest]'s fields, no external reducer is needed here: typing
 * updates the form's own state and is read back by the fields directly, exactly the way
 * [AddMovieScreenTest] drives [AddMovieScreen]. A field starts each test blank (its backing
 * [EditBookUiState.Ready] property `null`) whenever a test needs to type an exact, known string
 * into it -- [performTextInput] appends to whatever text is already there, so starting from blank
 * avoids the wrong value being asserted against a concatenation of seed and typed text.
 *
 * The out-of-range and unparseable release-year cases both use [BookRepository]'s own
 * `MIN_RELEASE_YEAR`/`MAX_RELEASE_YEAR` constants rather than hardcoded bounds, per
 * [EditBookForm]'s KDoc: client-side and repository-side validation read the same numbers, so this
 * test would fail to compile (not just fail) if the two drifted apart.
 */
class EditBookScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun readyState(
        title: String = "Dune",
        releaseYear: Int? = 1965,
        purchasePrice: Double? = 9.99,
        totalPages: Int? = 412,
        format: BookFormat = BookFormat.PHYSICAL,
        status: ReadingStatus = ReadingStatus.TO_READ,
        trackingMode: TrackingMode = TrackingMode.PAGES,
    ) = EditBookUiState.Ready(
        title = title,
        releaseYear = releaseYear,
        purchasePrice = purchasePrice,
        totalPages = totalPages,
        format = format,
        status = status,
        trackingMode = trackingMode,
    )

    private fun setContent(
        uiState: EditBookUiState = readyState(),
        onSave: (String, Int?, Double?, Int?, BookFormat, ReadingStatus, TrackingMode) -> Unit =
            { _, _, _, _, _, _, _ -> },
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                EditBookScreen(
                    uiState = uiState,
                    onNavigateBack = {},
                    onSave = onSave,
                )
            }
        }
    }

    private fun saveButtonMatcher() = hasText(context.getString(R.string.save_button)) and hasClickAction()

    private fun typeIntoField(
        labelRes: Int,
        text: String,
    ) {
        val label = context.getString(labelRes)
        composeRule.onNode(hasText(label) and hasSetTextAction()).performTextInput(text)
    }

    @Test
    fun saveButton_isDisabledWhenTitleIsBlank_andEnabledOnceATitleIsEntered() {
        setContent(uiState = readyState(title = ""))

        composeRule.onNode(saveButtonMatcher()).assertIsNotEnabled()

        typeIntoField(R.string.edit_title_label, "Dune")

        composeRule.onNode(saveButtonMatcher()).assertIsEnabled()
    }

    @Test
    fun saveButton_staysEnabled_whenReleaseYearPurchasePriceAndTotalPagesAreBlank() {
        setContent(
            uiState =
                readyState(
                    title = "Dune",
                    releaseYear = null,
                    purchasePrice = null,
                    totalPages = null,
                ),
        )

        composeRule.onNode(saveButtonMatcher()).assertIsEnabled()
    }

    @Test
    fun releaseYearOutOfRange_disablesSave_andShowsInlineError() {
        setContent(uiState = readyState(title = "Dune", releaseYear = null))

        // Below BookRepository.MIN_RELEASE_YEAR (1450) -- parses fine as an Int but fails the bound
        // check, which is a different failure than "1999999999" below being unreadable at all.
        typeIntoField(R.string.edit_release_year_label, "1000")

        composeRule.onNode(saveButtonMatcher()).assertIsNotEnabled()

        val errorText =
            context.getString(
                R.string.edit_release_year_invalid_error,
                BookRepository.MIN_RELEASE_YEAR,
                BookRepository.MAX_RELEASE_YEAR,
            )
        composeRule.onNodeWithText(errorText).assertIsDisplayed()
    }

    @Test
    fun releaseYearUnparseable_disablesSave() {
        setContent(uiState = readyState(title = "Dune", releaseYear = null))

        // Digits only, so the input filter lets it through, but it overflows Int -- the case
        // parseOptionalNumber exists to keep distinct from "blank."
        typeIntoField(R.string.edit_release_year_label, "19999999999")

        composeRule.onNode(saveButtonMatcher()).assertIsNotEnabled()
    }

    @Test
    fun totalPagesZero_disablesSave() {
        setContent(uiState = readyState(title = "Dune", totalPages = null))

        // The bound is > 0, not >= 0: a book cannot have zero pages, so "0" is a real, invalid,
        // non-blank claim -- distinct from leaving the field blank ("unknown").
        typeIntoField(R.string.edit_total_pages_label, "0")

        composeRule.onNode(saveButtonMatcher()).assertIsNotEnabled()
    }

    @Test
    fun savingWithBlankPurchasePrice_passesNullRatherThanZero() {
        var capturedPrice: Double? = -1.0
        setContent(
            uiState = readyState(title = "Dune", purchasePrice = null),
            onSave = { _, _, price, _, _, _, _ -> capturedPrice = price },
        )

        composeRule.onNode(saveButtonMatcher()).assertIsEnabled().performClick()

        assertNull("blank purchase price must arrive as null, not 0.0", capturedPrice)
    }
}
