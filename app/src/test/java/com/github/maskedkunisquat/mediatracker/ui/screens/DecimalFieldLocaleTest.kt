package com.github.maskedkunisquat.mediatracker.ui.screens

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.ui.EditMovieUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * #201 through a real field: in a comma-decimal locale, a decimal field must display the locale's
 * separator, or Compose feeds the canonical "." back into the filter and it is dropped as a
 * thousands mark. One screen proves the wiring; the other eight fields are the same one-line change,
 * and the keystroke loop itself is tested in shared's NumericFormFieldTest.
 */
@RunWith(RobolectricTestRunner::class)
class DecimalFieldLocaleTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var previousLocale: Locale

    @Before
    fun useCommaLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    private var storedPrice = ""

    private fun setUpEditMovie(prefill: String) {
        composeRule.setContent {
            var state by remember {
                mutableStateOf(
                    EditMovieUiState.Editing(
                        title = "Interstellar",
                        releaseYear = "2014",
                        runtimeMinutes = "169",
                        purchasePrice = prefill,
                        status = WatchStatus.WATCHLIST,
                    ),
                )
            }
            storedPrice = state.purchasePrice
            EditMovieScreen(
                uiState = state,
                onTitleChange = {},
                onReleaseYearChange = {},
                onRuntimeChange = {},
                onPurchasePriceChange = { state = state.copy(purchasePrice = it) },
                onStatusChange = {},
                onSave = {},
                onErrorShown = {},
                onRetry = {},
                onNavigateBack = {},
            )
        }
    }

    private val priceLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>().getString(R.string.add_movie_field_price)

    private fun priceField() = composeRule.onNode(hasSetTextAction() and hasText(priceLabel))

    @Test
    fun prefilledPrice_displaysTheLocaleDecimalComma() {
        setUpEditMovie(prefill = "19.99")
        priceField().assert(hasText("19,99"))
    }

    @Test
    fun typingAPriceKeystrokeByKeystroke_storesTheDecimal() {
        setUpEditMovie(prefill = "19.99")
        priceField().performTextClearance()
        for (char in "14,99") priceField().performTextInput(char.toString())
        composeRule.waitForIdle()
        assertEquals("14.99", storedPrice)
        priceField().assert(hasText("14,99"))
    }

    @Test
    fun typingAPriceWithThePeriodKey_storesTheDecimal() {
        // Samsung's decimal keypad in de-DE disables ',' and offers only '.' (found on a device).
        setUpEditMovie(prefill = "")
        for (char in "14.99") priceField().performTextInput(char.toString())
        composeRule.waitForIdle()
        assertEquals("14.99", storedPrice)
        priceField().assert(hasText("14,99"))
    }
}
