package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.WindowInsets
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performSemanticsAction
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
 * Two rules, because scrolling changes what "unreachable" means but does not abolish it:
 * - A control at a **fixed** position — a FAB, a bottom bar, a search field above a list — must sit
 *   above the keyboard line. Below it, it is simply gone.
 * - A control **inside a scroller** is measured with its container scrolled to the end, because
 *   that is the highest position it can attain. Reachable means *some* scroll offset clears the
 *   keyboard, and if the furthest one does not, none does.
 *
 * Both rules are preceded by a positive control ([assertTheScreenCouldHaveFailed]), because the way
 * this harness goes wrong is not by asserting the wrong thing — it is by asserting nothing.
 *
 * That is not hypothetical. The first draft exempted *anything* inside a scroller, which left four
 * of seven screens asserting nothing whatsoever: Add movie, Add show, Edit film and Settings each
 * wrap their whole form, Save button included, in one scrolling container. All four passed with
 * `contentWindowInsets` deleted. A fifth, Add book, passed because a single fabricated search result
 * left the list too short to reach the bottom of the screen at all.
 *
 * **A green run cannot distinguish a guard from a no-op. Only breaking the code can.** Every test
 * using this harness has been checked that way — strip the screen's inset handling, watch its test
 * fail, restore — and must stay that way.
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

    fun List<SemanticsNode>.belowTheKeyboard() =
        filter { it.boundsInRoot.bottom > keyboardTopEdge + ImeOcclusion.TOLERANCE_PX }

    assertTheScreenCouldHaveFailed(keyboardTopEdge)

    // Controls at a fixed position: a FAB, a bottom bar, a search field above a list. If one of
    // these is below the keyboard line it is simply unreachable, which is the PR #95 bug.
    val strandedInPlace =
        onAllNodes(hasClickAction() or hasSetTextAction())
            .fetchSemanticsNodes()
            .filterNot { it.isInsideAScrollingContainer() }
            .belowTheKeyboard()
            .map { "control ${it.describe()} at ${it.boundsInRoot}" }

    // Everything inside a scroller, measured at the one position that decides the question.
    //
    // A control in a list is reachable if *some* scroll offset puts it above the keyboard, so the
    // test has to name a position rather than measure wherever the screen happened to open. The
    // deciding position is the end: scrolling moves content up, so a control's highest attainable
    // position is the one it holds when the container can scroll no further. If it is still below
    // the keyboard line there, no scroll offset saves it.
    //
    // This replaces a check on the viewport's own bottom edge, which was correct only while the
    // current padding strategy holds. A list that draws behind the system bars with bottom
    // `contentPadding` -- what #99 is about to do to every screen here -- has a viewport that
    // extends past the inset *by design* while its last row still clears the keyboard. The old rule
    // would have failed that honest code, mid-feature, looking like a bug in the feature.
    // Scroll-to-end asks the question the user actually cares about and is indifferent to how the
    // padding is arranged.
    scrollEveryContainerToItsEnd()
    val strandedAtTheEndOfAScroll =
        onAllNodes(hasClickAction() or hasSetTextAction())
            .fetchSemanticsNodes()
            .filter { it.isInsideAScrollingContainer() }
            .belowTheKeyboard()
            .map { "control ${it.describe()} at ${it.boundsInRoot}, with its list scrolled to the end" }

    val occluded = strandedInPlace + strandedAtTheEndOfAScroll

    // Reports every offender rather than the first, because a screen that mishandles insets
    // usually strands its whole bottom edge at once -- fixing them one failed run at a time would
    // mean one run per control.
    assertTrue(
        buildString {
            append("The keyboard covers ${occluded.size} thing(s) the user has to reach.\n")
            append("Keyboard top edge is y=$keyboardTopEdge px; anything below that is unreachable.\n")
            occluded.forEach { append("  - $it\n") }
        },
        occluded.isEmpty(),
    )
}

/**
 * The positive control: fails if the fixture could not have produced an occlusion in the first
 * place.
 *
 * AGENTS.md section 7 states the rule this implements — assert that the thing you expect to be
 * present *is* present, not merely that the forbidden thing is absent — and this harness has now
 * been caught by both halves of it. A screen in a loading or empty state renders no controls at
 * all and passes trivially. So does a screen whose content is too short to reach the bottom: Add
 * book's results `LazyColumn` only sets `fillMaxWidth`, so with one fabricated result it wrapped to
 * a short box near the top, nothing on the screen came near the keyboard, and deleting
 * `contentWindowInsets` changed no measurable bound.
 *
 * Two conditions, matching those two failures:
 * - Something interactive exists.
 * - The interactive content reaches into the lower part of the space above the keyboard, so there
 *   is something the keyboard could plausibly have covered. [MIN_FILL_FRACTION] is deliberately
 *   loose — this is meant to catch a fixture that renders a stub, not to police layout.
 *
 * Necessary, not sufficient. A fixture can satisfy both and still assert nothing, which is why the
 * falsification sweep in the KDoc above is not optional.
 */
private fun ComposeContentTestRule.assertTheScreenCouldHaveFailed(keyboardTopEdge: Int) {
    val candidates =
        onAllNodes(hasClickAction() or hasSetTextAction()).fetchSemanticsNodes() +
            onAllNodes(isAScrollingContainer).fetchSemanticsNodes()
    assertTrue(
        "This fixture rendered nothing interactive, so it cannot demonstrate anything about the " +
            "keyboard. Populate the screen's state -- a loading or empty variant passes trivially.",
        candidates.any { it.config.contains(SemanticsProperties.Focused) || it.boundsInRoot.height > 0f },
    )
    val deepest = candidates.maxOfOrNull { it.boundsInRoot.bottom } ?: 0f
    assertTrue(
        "This fixture's interactive content stops at y=$deepest px, well above the keyboard line at " +
            "y=$keyboardTopEdge px, so nothing on it could be covered whether or not the screen " +
            "handles insets -- the test would pass either way. Populate more state, or use the " +
            "state in which this screen actually fills the display.",
        deepest >= keyboardTopEdge * MIN_FILL_FRACTION,
    )
}

/**
 * How far down the usable area a fixture's content must reach to count as a real test.
 *
 * Two-thirds is a floor, not a target. Every screen in this lane clears it comfortably when given a
 * realistic state and the one that did not — Add book with a single search result — is exactly the
 * case this is here to reject.
 */
private const val MIN_FILL_FRACTION = 2f / 3f

/**
 * Drives every scrolling container on screen as far down as it will go.
 *
 * Loops until the scroll position stops moving rather than computing a single jump to `maxValue`,
 * because a `LazyColumn` reports `maxValue` as an *estimate* derived from item count — it can be
 * infinite before enough items have been measured, and it shifts as new ones compose. Watching the
 * position settle needs no such assumption and stops as soon as it has.
 *
 * [SCROLL_ATTEMPTS] bounds the loop so a container that somehow never settles fails the test on its
 * assertion rather than hanging the suite.
 */
private fun ComposeContentTestRule.scrollEveryContainerToItsEnd() {
    val containers = onAllNodes(isAScrollingContainer)
    repeat(containers.fetchSemanticsNodes().size) { index ->
        var previous = Float.NaN
        repeat(SCROLL_ATTEMPTS) {
            // Re-fetched every iteration: scrolling one container composes and disposes rows, which
            // can add or remove scrollers elsewhere in the tree and invalidate a held node.
            val node = onAllNodes(isAScrollingContainer).fetchSemanticsNodes().getOrNull(index) ?: return@repeat
            val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) ?: return@repeat
            val position = range.value()
            if (position == previous) return@repeat
            previous = position
            // A viewport-sized step: large enough to reach the end of an ordinary form in one or
            // two goes, small enough that an over-long list does not overshoot into an animation
            // Robolectric then has to settle.
            onAllNodes(isAScrollingContainer)[index]
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, node.size.height.toFloat()) }
            waitForIdle()
        }
    }
}

private const val SCROLL_ATTEMPTS = 25

/**
 * Matches a node that scrolls — the viewport, not the content inside it.
 */
private val isAScrollingContainer =
    SemanticsMatcher("is a scrolling container") { it.scrolls() }

/**
 * The first piece of visible text anywhere inside this node.
 *
 * A scrolling viewport carries no text or content description of its own, so on its own it reports
 * as an unlabelled node id — which tells whoever reads the failure nothing about *which* list on
 * the screen is the problem. Naming something it contains is enough to locate it.
 */
private fun SemanticsNode.firstTextInside(): String =
    generateSequence(listOf(this)) { level -> level.flatMap { it.children }.ifEmpty { null } }
        .flatten()
        .firstNotNullOfOrNull {
            it.config
                .getOrNull(SemanticsProperties.Text)
                ?.firstOrNull()
                ?.text
        }
        ?: "no text"

private fun SemanticsNode.scrolls(): Boolean =
    config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null ||
        config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null

/**
 * True when some ancestor scrolls, which makes this node's position a scroll offset rather than a
 * layout mistake.
 *
 * Walks ancestors rather than checking the node itself: the scrollable is the container (the
 * `LazyColumn`), while the thing carrying the click action is a row inside it.
 */
private fun SemanticsNode.isInsideAScrollingContainer(): Boolean =
    generateSequence(parent) { it.parent }.any { it.scrolls() }

/**
 * A human-readable handle for a failure message.
 *
 * Prefers a `testTag` (see `TestTags`), which is why the tags exist: a scrolling container has no
 * text and no content description of its own, so before they were applied a stranded list reported
 * as `unlabelled node #91` and left the reader to work out which list on the screen that was.
 *
 * Falls through to content description and visible text for everything else, deliberately. Only the
 * controls a test or a device check actually drives are tagged — a status chip that reports as
 * "Abandoned" is telling you more than `addMovie:statusChip` would, and tagging every node to
 * flatter this function would invert AGENTS.md section 7's preference for semantic matchers.
 *
 * The node id is a last resort. It is stable within a run, which is enough to tell two anonymous
 * offenders apart, and seeing one is a hint that whatever it names may be worth a tag.
 */
private fun SemanticsNode.describe(): String =
    config.getOrNull(SemanticsProperties.TestTag)
        ?: config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        ?: config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        ?: config.getOrNull(SemanticsProperties.EditableText)?.text?.ifBlank { null }
        ?: "unlabelled node #$id"
