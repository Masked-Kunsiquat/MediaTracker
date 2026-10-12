package com.github.maskedkunisquat.mediatracker.ui.goldens

import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.components.MarkWatchedDialog
import com.hub.media.ui.PendingTick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The "Mark … watched?" prompt (#131) in its fullest state: catch-up plus all four choices. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkWatchedDialogGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun markWatchedDialog() {
        composeRule.captureGolden(
            name = "mark-watched-dialog",
            alsoAssert = {
                assertTextIsShown(
                    "Mark \"Please Remain Calm\" watched?",
                    "Also mark the 3 earlier episodes",
                    "Now",
                    "On air date",
                    "Pick a date…",
                    "Cancel",
                )
            },
        ) {
            MarkWatchedDialog(
                tick =
                    PendingTick(
                        title = "Please Remain Calm",
                        seasonNumber = 1,
                        episodeNumber = 4,
                        isSeason = false,
                        targetIds = listOf("t"),
                        earlierIds = listOf("a", "b", "c"),
                        airDateAvailable = true,
                        airDateAvailableWithEarlier = true,
                        initialPickMillis = null,
                    ),
                onConfirm = { _, _ -> },
                onDismiss = {},
            )
        }
    }
}
