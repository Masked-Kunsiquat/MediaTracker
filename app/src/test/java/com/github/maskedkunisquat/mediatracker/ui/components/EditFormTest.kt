package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The pieces the edit screens share (#174): [EditHeader], [EditBottomBar], and a disabled [StatusDropdownChip]. */
@RunWith(RobolectricTestRunner::class)
class EditFormTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun header_showsTheTitleAsAHeading_andTheKindLineWithYear() {
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                EditHeader(kind = "Film", year = 1999, title = "The Matrix", artwork = null)
            }
        }

        composeRule.onNode(hasText("The Matrix") and isHeading()).assertExists()
        composeRule.onNodeWithText("Film · 1999").assertExists()
    }

    @Test
    fun header_withoutAYear_showsTheKindAlone() {
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                EditHeader(kind = "Film", year = null, title = "Untitled", artwork = null)
            }
        }

        composeRule.onNodeWithText("Film").assertExists()
        composeRule.onNodeWithText("Film ·", substring = true).assertDoesNotExist()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun header_longTitle_isCappedAtTwoLines() {
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                EditHeader(kind = "Film", year = 2000, title = "Word ".repeat(40).trim(), artwork = null)
            }
        }

        assertEquals(2, composeRule.onNode(isHeading()).textLayout().lineCount)
    }

    @Test
    fun statusChip_disabled_doesNotOpen() {
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                StatusDropdownChip(
                    value = "Watchlist",
                    options = listOf("Watchlist", "Watched"),
                    label = { it },
                    onSelect = {},
                    onClickLabel = "Change status",
                    enabled = false,
                )
            }
        }

        composeRule.onNodeWithText("Watchlist").assertIsNotEnabled().performClick()
        composeRule.onNodeWithText("Watched").assertDoesNotExist()
    }

    @Test
    fun bottomBar_whileSaving_disablesBothButtons() {
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                EditBottomBar(
                    isSaving = true,
                    canSave = true,
                    onCancel = {},
                    onSave = {},
                    cancelTag = CANCEL,
                    saveTag = SAVE,
                )
            }
        }

        composeRule.onNodeWithTag(SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(CANCEL).assertIsNotEnabled()
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private companion object {
        const val SAVE = "test:save"
        const val CANCEL = "test:cancel"
    }
}
