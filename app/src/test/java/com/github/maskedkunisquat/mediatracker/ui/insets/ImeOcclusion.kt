package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowInsets
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import org.junit.Assert.assertTrue

/**
 * Fails if any control the user is expected to reach ends up underneath the on-screen keyboard.
 *
 * This exists because the failure it catches is invisible to every other kind of test in this
 * repository. When the Library FAB sat behind the keyboard (PR #95) it was present, enabled and
 * `assertIsDisplayed()` — 95 instrumented and 966 unit tests were green throughout, because what
 * was wrong was the button's geometry and nothing here read geometry. Semantics answer *whether* a
 * control exists; only bounds answer *where*, and "where" is the whole bug.
 *
 * Deliberately an invariant rather than a golden image. A screenshot would have caught this only if
 * a human looked at the diff and recognised the FAB as misplaced, which is the step that gets
 * skipped — see the anti-rot rules on issue #96. An assertion needs no reviewer.
 *
 * It is also independent of *how* a screen handles insets. The `Compose IME insets` CI job greps
 * for a `contentWindowInsets` assignment, which as its own commit message admits cannot tell which
 * `Scaffold` in a file received it. This does not care: pass `contentWindowInsets`, use
 * `imePadding()`, or invent something else — the only question asked is whether the controls ended
 * up above the keyboard.
 */
object ImeOcclusion {
    /**
     * Height of the fake keyboard, in dp.
     *
     * Chosen to sit in the middle of the range real IMEs occupy (Gboard is roughly 270–320dp
     * depending on the device and whether the suggestion strip is showing) and to be large enough
     * that a screen which ignores the inset entirely cannot pass by luck. The exact value should
     * not matter to a correct screen, which is itself part of what is being asserted.
     */
    val KeyboardHeight: Dp = 300.dp

    /**
     * Allowed overlap, in px, before a node counts as occluded.
     *
     * Not a fudge factor for near-misses: it absorbs the sub-pixel rounding that comes of
     * converting a dp inset to px and back, which can leave a correctly-placed node reporting a
     * bottom edge a fraction below the keyboard line. A genuinely stranded control is wrong by tens
     * or hundreds of pixels — the FAB in PR #95 was 940px inside the keyboard — so nothing real is
     * hidden by a tolerance this small.
     */
    const val TOLERANCE_PX = 1f
}

/**
 * Renders [content] with a keyboard-sized IME inset reported to it, then asserts that no interactive
 * control has been left underneath that keyboard.
 *
 * The inset is *reported*, not applied: the window keeps its full height and the composition is
 * told the bottom [ImeOcclusion.KeyboardHeight] is covered, which is exactly the situation a
 * `Scaffold` with the default `contentWindowInsets` mishandles. That fidelity is the reason this
 * lane runs on Robolectric rather than LayoutLib — Paparazzi and the Compose Preview screenshot
 * tool have no real `Window`, so `WindowInsets.safeDrawing` resolves to zero there and the bug
 * cannot be reproduced at all.
 *
 * Nodes inside a scrolling container are exempt, and the distinction is the point rather than a
 * convenience: content that has merely scrolled under the keyboard can be scrolled back out, so it
 * is not stranded. A FAB, a bottom bar or a search field cannot be, which is why those are the
 * things that strand.
 */
fun ComposeContentTestRule.assertNoInteractiveNodeIsBehindTheKeyboard(content: @Composable () -> Unit) {
    val keyboardPx = with(density) { ImeOcclusion.KeyboardHeight.roundToPx() }
    setContent {
        val imeInsets =
            WindowInsetsCompat
                .Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboardPx))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .build()
        DeviceConfigurationOverride(DeviceConfigurationOverride.WindowInsets(imeInsets)) {
            MediaTrackerTheme { content() }
        }
    }
    waitForIdle()

    // `onRoot()` would throw here: `DeviceConfigurationOverride` hosts the overridden content in a
    // composition of its own, so the tree has two coincident roots and "expected exactly 1" fails
    // before any occlusion is measured. Taking the tallest is robust to that whether the override
    // is present or not, rather than depending on how many roots this particular override happens
    // to introduce.
    val keyboardTopEdge = onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.size.height } - keyboardPx
    val occluded =
        onAllNodes(hasClickAction() or hasSetTextAction())
            .fetchSemanticsNodes()
            .filterNot { it.isInsideAScrollingContainer() }
            .filter { it.boundsInRoot.bottom > keyboardTopEdge + ImeOcclusion.TOLERANCE_PX }

    // Reports every offender rather than the first, because a screen that mishandles insets
    // usually strands its whole bottom edge at once -- fixing them one failed run at a time would
    // mean one run per control.
    assertTrue(
        buildString {
            append("The keyboard covers ${occluded.size} control(s) that the user has to reach.\n")
            append("Keyboard top edge is y=$keyboardTopEdge px; anything below that is unreachable.\n")
            occluded.forEach { node ->
                append("  - ${node.describe()} at ${node.boundsInRoot}\n")
            }
        },
        occluded.isEmpty(),
    )
}

/**
 * True when some ancestor scrolls, which makes this node's position a scroll offset rather than a
 * layout mistake.
 *
 * Walks ancestors rather than checking the node itself: the scrollable is the container (the
 * `LazyColumn`), while the thing carrying the click action is a row inside it.
 */
private fun SemanticsNode.isInsideAScrollingContainer(): Boolean =
    generateSequence(parent) { it.parent }.any { ancestor ->
        ancestor.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null ||
            ancestor.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
    }

/**
 * A human-readable handle for a failure message.
 *
 * Falls through several properties because the screens carry no `testTag`s yet (see the ROADMAP
 * backlog item on `testTagsAsResourceId`); until they do, visible text and content descriptions are
 * what identifies a control. The node id is a last resort — it is stable within a run, which is
 * enough to tell two unlabelled offenders apart.
 */
private fun SemanticsNode.describe(): String =
    config.getOrNull(SemanticsProperties.TestTag)
        ?: config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        ?: config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        ?: config.getOrNull(SemanticsProperties.EditableText)?.text?.ifBlank { null }
        ?: "unlabelled node #$id"
