package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.maskedkunisquat.mediatracker.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric guards for [DetailFacts] (#141 step 2), so the null-dropping rule runs in CI without a
 * device. Screen-level tests (`TVShowDetailScreenTest`) cover the real show data feeding this;
 * these pin the component's own contract in isolation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailFactsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pairsWithANullValue_areDropped() {
        composeRule.setContent {
            DetailFacts(
                facts =
                    listOf(
                        DetailFact("First aired", "May 26, 2019"),
                        DetailFact("Airing", null),
                        DetailFact("Seasons", "1"),
                    ),
            )
        }

        composeRule.onNodeWithText("First aired").assertIsDisplayed()
        composeRule.onNodeWithText("Seasons").assertIsDisplayed()
        composeRule.onAllNodesWithText("Airing").assertCountEquals(0)
    }

    @Test
    fun everyPairNull_rendersNothing() {
        composeRule.setContent {
            DetailFacts(facts = listOf(DetailFact("First aired", null), DetailFact("Airing", null)))
        }

        composeRule.onAllNodesWithText("First aired").assertCountEquals(0)
        composeRule.onAllNodesWithText("Airing").assertCountEquals(0)
    }

    /**
     * #141 step 3: a fact carrying [DetailFactAction] (Book's ISBN copy button) renders both its
     * value and the trailing icon, and tapping the icon invokes the action rather than the row
     * itself doing anything.
     */
    @Test
    fun factWithAnAction_rendersValueAndIcon_andInvokesOnClick() {
        var copies = 0
        composeRule.setContent {
            DetailFacts(
                facts =
                    listOf(
                        DetailFact(
                            label = "ISBN",
                            value = "9780743273565",
                            action =
                                DetailFactAction(
                                    iconRes = R.drawable.ic_content_copy,
                                    contentDescription = "Copy ISBN",
                                    onClick = { copies++ },
                                ),
                        ),
                    ),
            )
        }

        composeRule.onNodeWithText("9780743273565").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Copy ISBN").performClick()

        assertEquals(1, copies)
    }
}
