package com.github.maskedkunisquat.mediatracker.ui.navigation

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [BlockInputUntilResumed] (#126): a destination that is still fading in, or already fading out,
 * must not take taps. On the hub a second tap on a card -- or a tap just after Back -- otherwise
 * landed on the library row under the finger and opened an item the user never chose.
 */
@RunWith(RobolectricTestRunner::class)
class InputGateTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A lifecycle the test moves by hand, standing in for a NavBackStackEntry's. */
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private var clicks = 0

    private fun setContent(owner: Owner) {
        composeRule.setContent {
            BlockInputUntilResumed(owner) {
                Button(onClick = { clicks++ }) { Text("Row") }
            }
        }
    }

    @Test
    fun aSettledScreen_takesTaps() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        setContent(owner)

        composeRule.onNodeWithText("Row").performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun anArrivingScreen_ignoresTaps_untilItSettles() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
        setContent(owner)

        composeRule.onNodeWithText("Row").performClick()
        assertEquals(0, clicks)

        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.onNodeWithText("Row").performClick()
        // Positive control: the same tap works once the screen has settled.
        assertEquals(1, clicks)
    }

    @Test
    fun aLeavingScreen_ignoresTaps() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        setContent(owner)

        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        composeRule.onNodeWithText("Row").performClick()

        assertEquals(0, clicks)
    }
}
