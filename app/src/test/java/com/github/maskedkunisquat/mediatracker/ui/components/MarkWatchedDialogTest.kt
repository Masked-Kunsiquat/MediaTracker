package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.features.tv.domain.WatchedWhen
import com.hub.media.ui.PendingTick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The stateless [MarkWatchedDialog] (#131) on the Robolectric lane, so every PR gates it. It holds
 * no text field, which is what lets it run here (AGENTS.md §7); the date picker itself is
 * device-only, in `MarkWatchedDialogPickerTest`.
 */
@RunWith(RobolectricTestRunner::class)
class MarkWatchedDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun tick(
        earlier: Int = 3,
        airDate: Boolean = true,
        airDateWithEarlier: Boolean = true,
        isSeason: Boolean = false,
    ) = PendingTick(
        title = "Please Remain Calm",
        seasonNumber = 1,
        episodeNumber = 4,
        isSeason = isSeason,
        targetIds = if (isSeason) listOf("a", "b") else listOf("t"),
        earlierIds = if (isSeason) emptyList() else List(earlier) { "e$it" },
        airDateAvailable = airDate,
        airDateAvailableWithEarlier = airDateWithEarlier,
        initialPickMillis = null,
    )

    private val confirmed = mutableListOf<Pair<WatchedWhen, Boolean>>()
    private var dismissed = 0

    private fun show(
        tick: PendingTick,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MediaTrackerTheme(dynamicColor = false) {
                    MarkWatchedDialog(tick, onConfirm = { w, e -> confirmed += w to e }, onDismiss = { dismissed++ })
                }
            }
        }
    }

    private fun button(text: String) = composeRule.onNode(hasText(text) and hasClickAction(), useUnmergedTree = false)

    @Test
    fun now_callsBackWithNow_andTheCheckboxState() {
        show(tick())
        composeRule.onNodeWithText("Also mark the 3 earlier episodes").performClick()
        button("Now").performClick()
        assertEquals(listOf(WatchedWhen.Now to true), confirmed)
    }

    @Test
    fun onAirDate_callsBackWithOnAirDate_withoutCatchUpByDefault() {
        show(tick())
        button("On air date").performClick()
        assertEquals(listOf(WatchedWhen.OnAirDate to false), confirmed)
    }

    @Test
    fun onAirDate_hidden_whenUnavailable() {
        show(tick(airDate = false, airDateWithEarlier = false))
        button("Now").assertIsDisplayed() // positive control: the dialog is up
        assertTrue(composeRule.onAllNodes(hasText("On air date")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun onAirDate_hiddenOnlyOnceCatchUpIsTicked_whenAnEarlierEpisodeLacksOne() {
        show(tick(airDate = true, airDateWithEarlier = false))
        button("On air date").assertIsDisplayed()
        composeRule.onNodeWithText("Also mark the 3 earlier episodes").performClick()
        assertTrue(composeRule.onAllNodes(hasText("On air date")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun seasonTick_hasItsOwnTitle_andNoCatchUpCheckbox() {
        show(tick(isSeason = true))
        composeRule.onNodeWithText("Mark the 2 unwatched episodes of Season 1 watched?").assertIsDisplayed()
        assertTrue(composeRule.onAllNodes(hasText("earlier", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun cancel_callsDismiss_andConfirmsNothing() {
        show(tick())
        button("Cancel").performClick()
        assertEquals(1, dismissed)
        assertTrue(confirmed.isEmpty())
    }

    @Test
    fun largeFont_everyChoiceIsStillOnScreen() {
        show(tick(), fontScale = 2f)
        for (label in listOf("Now", "On air date", "Pick a date…", "Cancel")) {
            button(label).assertIsDisplayed()
        }
    }

    @Test
    fun seasonTick_withOneEpisode_isSingular() {
        show(tick(isSeason = true).copy(targetIds = listOf("only")))
        composeRule.onNodeWithText("Mark the 1 unwatched episode of Season 1 watched?").assertIsDisplayed()
    }
}
