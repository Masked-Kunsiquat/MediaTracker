package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [WatchedCircle]: TV Time's big check-circle, restyled. It must stay a real checkbox to every test
 * and accessibility service -- same role, same on/off state, same content description -- and keep a
 * 48dp touch target, which is the whole point of making it big.
 */
@RunWith(RobolectricTestRunner::class)
class WatchedCircleTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun isACheckbox_withA48dpTarget_thatToggles() {
        composeRule.setContent {
            var checked by remember { mutableStateOf(false) }
            MediaTrackerTheme(dynamicColor = false) {
                WatchedCircle(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    contentDescription = "Season 1, episode 4",
                )
            }
        }
        val node = composeRule.onNodeWithContentDescription("Season 1, episode 4")
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        node.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        node.assertIsOff()
        node.performClick()
        node.assertIsOn()
    }
}
