package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.ui.AddTVShowUiState
import com.hub.media.ui.SeasonRow
import org.junit.Rule
import org.junit.Test

/**
 * Behavioural tests for the Add TV Show screen (Issue #83, filling the coverage gap the numeric-
 * validation refactor left behind).
 *
 * Unlike [AddMovieScreen] -- whose fields are held locally via `rememberSaveable` and so only need
 * fake callbacks to observe what was typed -- [AddTVShowScreen] is stateless with respect to *every*
 * field, per [com.hub.media.ui.AddTVShowViewModel]'s KDoc. Driving it interactively therefore needs
 * a small stand-in reducer wired to the same shape [AddTVShowViewModel] itself uses, not just a
 * captured value; [setContent] below plays that role.
 *
 * The three validity trichotomies exercised here mirror [AddMovieScreenTest]'s "blank means unknown,
 * unreadable is refused" case, extended to this form's extra field (total seasons) and its one
 * structural difference: season rows are *required*, not optional, per
 * [com.hub.media.ui.AddTVShowViewModel]'s KDoc on [SeasonRow].
 */
class AddTVShowScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Matches the "Save show" button specifically, by its label text rather than by role+missing-
     * content-description (the [AddMovieScreenTest] `saveButtonMatcher` trick): this screen also has
     * an "Add season" [androidx.compose.material3.OutlinedButton], which is a
     * [androidx.compose.ui.semantics.Role.Button] with no content description too, so that trick
     * would be ambiguous here.
     */
    private fun saveButtonMatcher() = hasText(context.getString(R.string.add_tv_show_save)) and hasClickAction()

    /**
     * Wires [AddTVShowScreen] to a plain [mutableStateOf] reducer that mirrors
     * [com.hub.media.ui.AddTVShowViewModel]'s field-update shape, so typing into a field is visible
     * on screen exactly as it would be through the real ViewModel -- the same pattern
     * `AddBookScreenTest.searchClearButton_onlyShownWhenQueryIsNotEmpty` uses for a screen whose
     * state is not `rememberSaveable`-local either.
     */
    private fun setContent(initialState: AddTVShowUiState = AddTVShowUiState()) {
        val state = mutableStateOf(initialState)
        composeRule.setContent {
            MediaTrackerTheme {
                AddTVShowScreen(
                    uiState = state.value,
                    onTitleChange = { state.value = state.value.copy(title = it) },
                    onReleaseYearChange = { state.value = state.value.copy(releaseYear = it) },
                    onTotalSeasonsChange = { state.value = state.value.copy(totalSeasons = it) },
                    onPurchasePriceChange = { state.value = state.value.copy(purchasePrice = it) },
                    onAddSeasonRow = {
                        state.value = state.value.copy(seasons = state.value.seasons + SeasonRow())
                    },
                    onRemoveSeasonRow = { index ->
                        state.value =
                            state.value.copy(
                                seasons = state.value.seasons.filterIndexed { i, _ -> i != index },
                            )
                    },
                    onSeasonNumberChange = { index, value ->
                        state.value =
                            state.value.copy(
                                seasons =
                                    state.value.seasons.mapIndexed { i, row ->
                                        if (i == index) row.copy(seasonNumber = value) else row
                                    },
                            )
                    },
                    onEpisodeCountChange = { index, value ->
                        state.value =
                            state.value.copy(
                                seasons =
                                    state.value.seasons.mapIndexed { i, row ->
                                        if (i == index) row.copy(episodeCount = value) else row
                                    },
                            )
                    },
                    onSave = {},
                    onErrorShown = {},
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

        composeRule.onNode(saveButtonMatcher()).assertIsNotEnabled()

        typeIntoField(R.string.add_tv_show_field_title, "Breaking Bad")

        composeRule.onNode(saveButtonMatcher()).assertIsEnabled()
    }

    @Test
    fun saveButton_staysEnabled_whenReleaseYearTotalSeasonsAndPurchasePriceAreBlank() {
        setContent()

        // Title is the only required show-level field; year, total seasons and price are left
        // blank -- blank means "unknown" for these three, per AddTVShowViewModel's KDoc, and must
        // not block the save the way an unparseable value does.
        typeIntoField(R.string.add_tv_show_field_title, "The Wire")

        composeRule.onNode(saveButtonMatcher()).assertIsEnabled()
    }

    @Test
    fun saveButton_isDisabled_whenReleaseYearIsPresentButUnparseable() {
        setContent()

        typeIntoField(R.string.add_tv_show_field_title, "Fargo")
        // Digits only, so the input filter lets it through, but it overflows Int. This is exactly
        // the case parseOptionalNumber's KDoc calls out: a raw toIntOrNull() would read this as
        // "unknown" and silently drop what the user typed.
        typeIntoField(R.string.add_tv_show_field_year, "19999999999")

        composeRule.onNode(saveButtonMatcher()).assertIsNotEnabled()
    }

    @Test
    fun saveButton_isDisabled_whenPurchasePriceIsPresentButUnparseable() {
        setContent()

        typeIntoField(R.string.add_tv_show_field_title, "The Sopranos")
        // Unlike the release-year field (an Int, which genuinely overflows), a run of digits never
        // fails Double.toDoubleOrNull() -- it just rounds to Infinity instead of throwing, so
        // "19999999999" would not exercise the unparseable branch here. A lone decimal point does:
        // filterDecimalInput lets it through (it is the one decimal separator, seen before any
        // digit), but "." has no digits either side and so is not a valid Double.
        typeIntoField(R.string.add_tv_show_field_price, ".")

        composeRule.onNode(saveButtonMatcher()).assertIsNotEnabled()
    }

    @Test
    fun saveButton_isDisabled_whenASeasonRowHasABlankEpisodeCount() {
        // A season row's episode count is REQUIRED, not optional (unlike the show-level numeric
        // fields above): a blank one is a row the user has not finished filling in, not "unknown
        // episode count." Constructed directly rather than via the "Add season" button + typing,
        // since a freshly-added row already starts with a blank episode count.
        setContent(initialState = AddTVShowUiState(seasons = listOf(SeasonRow(seasonNumber = "1"))))

        typeIntoField(R.string.add_tv_show_field_title, "Chernobyl")

        composeRule.onNode(saveButtonMatcher()).assertIsNotEnabled()
    }
}
