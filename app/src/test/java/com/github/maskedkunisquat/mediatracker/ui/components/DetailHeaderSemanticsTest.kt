package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hub.media.core.database.entities.MediaType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Accessibility and state guards for the shared detail-header components (#141), from the
 * adversarial review of #165. Each pins something a screen-level test cannot see: what TalkBack is
 * handed, and whether synopsis state follows the text it belongs to.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailHeaderSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun statusChip_exposesItsChangeStatusLabelToAccessibility() {
        composeRule.setContent {
            StatusDropdownChip(
                value = "Watched",
                options = listOf("Watchlist", "Watched"),
                label = { it },
                onSelect = {},
                onClickLabel = "Change status",
            )
        }

        composeRule.onNodeWithText("Watched").assert(
            SemanticsMatcher("OnClick is labelled \"Change status\"") {
                it.config.getOrNull(SemanticsActions.OnClick)?.label == "Change status"
            },
        )
    }

    /** One announcement, not the description followed by "7.9" and "/ 10" read out again. */
    @Test
    fun ratingRow_isOneNodeCarryingOnlyItsDescription() {
        composeRule.setContent {
            DetailHeader(
                kind = "Film",
                year = null,
                title = "Arrival",
                subline = null,
                rating = 7.9,
                artwork = null,
                statusNote = null,
                statusControl = {},
            )
        }

        composeRule
            .onNodeWithContentDescription("Rated 7.9 out of 10")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
    }

    @Test
    fun synopsis_dropsItsExpandedState_whenTheTextChanges() {
        var text by mutableStateOf(LONG)
        composeRule.setContent {
            Box(modifier = Modifier.width(300.dp)) { DetailSynopsis(text) }
        }
        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Less").assertExists()

        text = "Short."
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Less").assertCountEquals(0)
        composeRule.onAllNodesWithText("More").assertCountEquals(0)
    }

    /**
     * #141 step 2: the read-only status chip ([DetailStatus.ReadOnly]) looks like
     * [StatusDropdownChip] but carries no click action -- TalkBack must not offer to "change status"
     * on a chip whose value is derived, not picked. `onNodeWithText` finding the node at all is the
     * "label still reaches accessibility" half: a `clearAndSetSemantics` bug that wiped the text along
     * with the click action would fail this lookup outright, not merely leave `OnClick` behind.
     */
    @Test
    fun readOnlyStatusChip_exposesNoClickAction_butKeepsItsLabel() {
        composeRule.setContent {
            DetailStatusChip(DetailStatus.ReadOnly(label = "Watching"))
        }

        composeRule
            .onNodeWithText("Watching")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    /**
     * #141 step 3: when a caller (only Book) passes [DetailHeader]'s `onArtworkClick`, the label
     * reaches accessibility on the artwork's own click action -- verified the same way the status
     * chip's click label is above, via [SemanticsActions.OnClick]'s `label`.
     */
    @Test
    fun artworkClick_exposesItsLabel_whenSupplied() {
        composeRule.setContent {
            DetailHeader(
                kind = "Book",
                year = null,
                title = "Dune",
                subline = null,
                rating = null,
                artwork = DetailArtwork("no-covers", "hash.jpg", MediaType.BOOK),
                statusNote = null,
                onArtworkClick = {},
                artworkClickLabel = "View enlarged cover",
                statusControl = {},
            )
        }

        composeRule
            .onNode(
                SemanticsMatcher("OnClick is labelled \"View enlarged cover\"") {
                    it.config.getOrNull(SemanticsActions.OnClick)?.label == "View enlarged cover"
                },
            ).assertExists()
    }

    /**
     * The other half of the same guard: no `onArtworkClick` (Film/TV's case) means no click action
     * on the artwork at all -- not merely an unlabelled one -- so their artwork stays inert.
     */
    @Test
    fun artworkClick_isAbsent_whenNotSupplied() {
        composeRule.setContent {
            DetailHeader(
                kind = "Book",
                year = null,
                title = "Dune",
                subline = null,
                rating = null,
                artwork = DetailArtwork("no-covers", "hash.jpg", MediaType.BOOK),
                statusNote = null,
                statusControl = {},
            )
        }

        // Only the status-control slot (empty here) would otherwise carry an OnClick action; the
        // artwork itself must contribute none.
        composeRule
            .onAllNodes(
                SemanticsMatcher("has an OnClick action") {
                    it.config.getOrNull(SemanticsActions.OnClick) != null
                },
            ).assertCountEquals(0)
    }

    private companion object {
        val LONG = List(12) { "A sentence long enough to wrap across the synopsis width." }.joinToString(" ")
    }
}
