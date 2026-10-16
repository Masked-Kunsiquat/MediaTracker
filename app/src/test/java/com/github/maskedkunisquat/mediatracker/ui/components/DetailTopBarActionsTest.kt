package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The shared detail-screen top bar (#172): Edit as an icon, then "More options" holding the
 * screen's secondary actions and, always last, Delete. The screens' own tests cover each screen's
 * wiring; these pin the component's contract once.
 */
@RunWith(RobolectricTestRunner::class)
class DetailTopBarActionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var edits = 0
    private var deletes = 0

    private fun setContent(secondary: List<DetailMenuAction>) {
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                Row {
                    DetailTopBarActions(
                        editContentDescription = "Edit item",
                        onEdit = { edits++ },
                        secondary = secondary,
                        deleteLabel = "Delete item",
                        onDelete = { deletes++ },
                    )
                }
            }
        }
    }

    private fun openMoreOptions() = composeRule.onNodeWithContentDescription("More options").performClick()

    private fun item(text: String) = composeRule.onNode(hasText(text) and hasClickAction())

    @Test
    fun edit_invokesOnEdit() {
        setContent(secondary = emptyList())
        composeRule.onNodeWithContentDescription("Edit item").performClick()
        assertEquals(1, edits)
    }

    @Test
    fun moreOptions_opensMenu_withSecondaryActionsInOrder_andDeleteLast() {
        setContent(
            secondary = listOf(DetailMenuAction("First", onClick = {}), DetailMenuAction("Second", onClick = {})),
        )
        openMoreOptions()

        val tops = listOf("First", "Second", "Delete item").map { item(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals("menu order is secondary actions as given, then Delete", tops.sorted(), tops)
        assertTrue("items are distinct rows", tops.toSet().size == 3)
    }

    @Test
    fun choosingDelete_invokesOnDeleteOnce_andClosesTheMenu() {
        setContent(secondary = emptyList())
        openMoreOptions()
        item("Delete item").performClick()
        composeRule.waitForIdle()

        assertEquals(1, deletes)
        composeRule.onNodeWithText("Delete item").assertDoesNotExist()
    }

    @Test
    fun choosingASecondaryAction_invokesIt_andClosesTheMenu() {
        var refreshes = 0
        setContent(secondary = listOf(DetailMenuAction("Refresh", onClick = { refreshes++ })))
        openMoreOptions()
        item("Refresh").performClick()
        composeRule.waitForIdle()

        assertEquals(1, refreshes)
        assertEquals("choosing an action is not a delete", 0, deletes)
        composeRule.onNodeWithText("Refresh").assertDoesNotExist()
    }

    @Test
    fun disabledAction_isNotEnabled_andShowsItsSupportingText() {
        setContent(
            secondary =
                listOf(
                    DetailMenuAction(
                        "Re-fetch cover",
                        onClick = {},
                        enabled = false,
                        supportingText = "No ISBN on record",
                    ),
                ),
        )
        openMoreOptions()

        item("Re-fetch cover").assertIsNotEnabled()
        composeRule.onNodeWithText("No ISBN on record", substring = true).assertExists()
        item("Delete item").assertIsEnabled()
    }

    @Test
    fun inProgressAction_isNotEnabled_andShowsProgress() {
        setContent(secondary = listOf(DetailMenuAction("Re-fetching cover…", onClick = {}, inProgress = true)))
        openMoreOptions()

        item("Re-fetching cover…").assertIsNotEnabled()
        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true)
            .assertExists()
        item("Delete item").assertIsEnabled()
    }

    @Test
    fun noSecondaryActions_menuHoldsOnlyDelete() {
        setContent(secondary = emptyList())
        openMoreOptions()

        item("Delete item").assertExists()
        composeRule.onNodeWithText("Edit item").assertDoesNotExist()
    }
}
