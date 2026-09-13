package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.features.portability.domain.StagedRestoreInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The confirmation dialog must not allow a second commit: it stays up until the process restarts, and
 * a second `commit` would move the freshly restored database over the pre-restore backup.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreConfirmationDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun string(id: Int): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    private val confirmButton get() =
        composeRule.onNode(
            hasText(string(R.string.restore_confirm_button)) and hasClickAction(),
        )

    private val cancelButton get() =
        composeRule.onNode(
            hasText(string(R.string.restore_cancel_button)) and hasClickAction(),
        )

    @Test
    fun confirm_disablesBothButtonsAfterTheFirstTap() {
        var confirmCount = 0
        var cancelCount = 0
        composeRule.setContent {
            // Mirrors RestoreOutcome, which flips commitInProgress synchronously in onConfirm.
            var commitInProgress by remember { mutableStateOf(false) }
            RestoreConfirmationDialog(
                info =
                    StagedRestoreInfo(
                        stagedFilePath = "/staged.tmp",
                        schemaVersionFound = 5,
                        isOlderSchemaVersion = false,
                    ),
                credentialsWillBeCleared = false,
                commitInProgress = commitInProgress,
                onConfirm = {
                    confirmCount++
                    commitInProgress = true
                },
                onCancel = { cancelCount++ },
            )
        }

        confirmButton.assertIsNotEnabled()
        composeRule.onNode(hasText(string(R.string.restore_confirm_checkbox_label)) and hasClickAction()).performClick()
        confirmButton.assertIsEnabled()
        cancelButton.assertIsEnabled()

        confirmButton.performClick()

        confirmButton.assertIsNotEnabled()
        cancelButton.assertIsNotEnabled()
        confirmButton.performClick()
        cancelButton.performClick()
        assertEquals(1, confirmCount)
        assertEquals(0, cancelCount)
    }
}
