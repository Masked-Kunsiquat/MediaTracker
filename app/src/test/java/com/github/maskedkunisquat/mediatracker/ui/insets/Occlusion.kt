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
import androidx.compose.ui.test.hasTestTag
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
object Occlusion {
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
     * Height of the fake navigation bar, in dp.
     *
     * 48dp is Android's three-button navigation bar. The gesture bar is smaller (around 24dp), so
     * asserting against the larger of the two covers both — and the larger is also the one that
     * strands a control further, which is the case worth reproducing. As with [KeyboardHeight] the
     * exact value should not matter to a correct screen.
     *
     * Two-thirds smaller than the keyboard, and that is the point of testing it separately: #99
     * moved padding on every screen at once, and the failure it can produce is a *pinned* control
     * left under the navigation bar with no keyboard anywhere in sight. The IME rule cannot see
     * that — it reports an IME inset, which a screen may handle correctly while handling the bars
     * wrongly, and the two insets now travel by different routes through every screen here.
     */
    val NavigationBarHeight: Dp = 48.dp

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
 * told the bottom [Occlusion.KeyboardHeight] is covered, which is exactly the situation a
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
fun ComposeContentTestRule.assertNoInteractiveNodeIsBehindTheKeyboard(
    expectedTags: List<String> = emptyList(),
    content: @Composable () -> Unit,
) = assertNothingIsBehindTheInset(
    insetType = WindowInsetsCompat.Type.ime(),
    insetHeight = Occlusion.KeyboardHeight,
    whatItIs = "The keyboard",
    expectedTags = expectedTags,
    content = content,
)

/**
 * Renders [content] with a navigation-bar-sized system-bar inset reported to it, then asserts that
 * nothing the user has to reach has been left underneath that bar.
 *
 * The same rule as [assertNoInteractiveNodeIsBehindTheKeyboard], asked about a different inset, and
 * it needs asking separately because after #99 the two insets travel by different routes through
 * every screen here. The keyboard stays outside the scroll as `Modifier.imePadding()`; the bars go
 * *inside* it as `contentPadding`. A screen can therefore get one right and the other wrong, and
 * the IME test would report nothing -- it reports no bar inset at all, so a screen that ignores the
 * bars entirely passes it comfortably.
 *
 * The failure this exists for is the one #99 names as its own most likely: padding moved on every
 * screen at once, and the shape of getting it wrong is a control that is present, enabled and
 * untappable. That was PR #95's stranded FAB and issue #83's season overflow button, both of which
 * shipped past a fully green suite and were found by hand on a phone. This is the second of those
 * two insets to get an assertion.
 *
 * Note what it deliberately does *not* assert: that content draws behind the bar. Nothing here
 * fails a screen for keeping real padding -- film detail has no scrolling container and add book's
 * results are a rounded card, and both are correct as they are. The rule is only that nothing ends
 * up underneath, which holds under either strategy and stays true if a screen changes its mind.
 *
 * ### Which screens have no navigation bar test, and why
 *
 * Five have one: the library, the changelog, edit book, settings and TV show detail. Every other
 * screen #99 touched was tried and then deleted, because the rule could not fail there and a green
 * no-op is worse than an absence. Listed so the gap is a decision on record rather than something
 * to be rediscovered and "fixed" by adding a test back:
 *
 * - **Add book, add film, add show, edit film.** Their forms do not reach the bottom of the display
 *   without a keyboard, so no control is ever near the bar. The keyboard is the inset that squeezes
 *   these screens and each is falsified against it.
 * - **Stats, the log viewer.** Neither has a single interactive node inside its scroller -- only
 *   top-bar buttons -- and this rule measures interactive nodes. What #99 risks on those two is a
 *   last card clipped by the bar, which is visual, and belongs to #96 Phase B.
 * - **Book detail.** Same reason on the Details tab, whose bottom rows are plain metadata. Its
 *   Reading history tab *would* qualify, since session rows carry edit and delete buttons at the
 *   foot of a scroll, but which tab is showing is `remember`ed inside `BookDetailContent` rather
 *   than hoisted, so reaching it needs a click and this harness measures rather than drives. That
 *   one is a real gap, not an exemption.
 */
fun ComposeContentTestRule.assertNoInteractiveNodeIsBehindTheNavigationBar(
    expectedTags: List<String> = emptyList(),
    content: @Composable () -> Unit,
) = assertNothingIsBehindTheInset(
    insetType = WindowInsetsCompat.Type.navigationBars(),
    insetHeight = Occlusion.NavigationBarHeight,
    whatItIs = "The navigation bar",
    expectedTags = expectedTags,
    content = content,
)

/**
 * The shared body of the two rules above: report one bottom inset, then measure.
 *
 * [insetType] is a `WindowInsetsCompat.Type` bit. It is reported rather than applied -- the window
 * keeps its full height and the composition is merely told the bottom [insetHeight] is covered,
 * which is exactly the situation a `Scaffold` with the wrong `contentWindowInsets` mishandles.
 * [whatItIs] names the inset in the failure message, because "The keyboard covers 3 things" and
 * "The navigation bar covers 3 things" send a reader to very different code.
 */
private fun ComposeContentTestRule.assertNothingIsBehindTheInset(
    insetType: Int,
    insetHeight: Dp,
    whatItIs: String,
    expectedTags: List<String>,
    content: @Composable () -> Unit,
) {
    val insetPx = with(density) { insetHeight.roundToPx() }
    setContent {
        val insets =
            WindowInsetsCompat
                .Builder()
                .setInsets(insetType, Insets.of(0, 0, 0, insetPx))
                .setVisible(insetType, true)
                .build()
        DeviceConfigurationOverride(DeviceConfigurationOverride.WindowInsets(insets)) {
            MediaTrackerTheme { content() }
        }
    }
    waitForIdle()

    // `onRoot()` would throw here: `DeviceConfigurationOverride` hosts the overridden content in a
    // composition of its own, so the tree has two coincident roots and "expected exactly 1" fails
    // before any occlusion is measured. Taking the tallest is robust to that whether the override
    // is present or not, rather than depending on how many roots this particular override happens
    // to introduce.
    val insetTopEdge = onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.size.height } - insetPx

    fun List<SemanticsNode>.belowTheLine() = filter { it.boundsInRoot.bottom > insetTopEdge + Occlusion.TOLERANCE_PX }

    assertTheScreenCouldHaveFailed(insetTopEdge, whatItIs)
    assertTheseTagsExist(expectedTags)

    // Controls at a fixed position: a FAB, a bottom bar, a search field above a list. If one of
    // these is below the keyboard line it is simply unreachable, which is the PR #95 bug.
    val strandedInPlace =
        onAllNodes(hasClickAction() or hasSetTextAction())
            .fetchSemanticsNodes()
            .filterNot { it.isInsideAScrollingContainer() }
            .belowTheLine()
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
            .belowTheLine()
            .map { "control ${it.describe()} at ${it.boundsInRoot}, with its list scrolled to the end" }

    val occluded = strandedInPlace + strandedAtTheEndOfAScroll

    // Reports every offender rather than the first, because a screen that mishandles insets
    // usually strands its whole bottom edge at once -- fixing them one failed run at a time would
    // mean one run per control.
    assertTrue(
        buildString {
            append("$whatItIs covers ${occluded.size} thing(s) the user has to reach.\n")
            append("Its top edge is y=$insetTopEdge px; anything below that is unreachable.\n")
            occluded.forEach { append("  - $it\n") }
        },
        occluded.isEmpty(),
    )
}

/**
 * Fails if a screen has lost a `testTag` it is supposed to carry.
 *
 * This exists because a tag that quietly disappears is invisible to everything else. The tags in
 * PR #103 were applied to seven screens and two of them were reverted before the commit landed by a
 * stray `git checkout` during an unrelated experiment — and every test stayed green, on the JVM and
 * on a device, because nothing anywhere asserted a tag was present. The registry's whole promise is
 * a handle that survives refactoring, and until this ran that promise rested on nobody making a
 * mistake.
 *
 * Cheap to keep true: each screen's test names the tags that screen owns, so a dropped tag fails
 * the screen it belongs to rather than some distant aggregate.
 */
private fun ComposeContentTestRule.assertTheseTagsExist(expectedTags: List<String>) {
    expectedTags.forEach { tag ->
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().ifEmpty {
            throw AssertionError(
                "This screen no longer carries the test tag \"$tag\". Either it was dropped in a " +
                    "refactor -- in which case anything driving that handle on a device is now " +
                    "silently matching nothing -- or the constant outlived the control and should " +
                    "be deleted from TestTags.",
            )
        }
    }
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
private fun ComposeContentTestRule.assertTheScreenCouldHaveFailed(
    insetTopEdge: Int,
    whatItIs: String,
) {
    val candidates =
        onAllNodes(hasClickAction() or hasSetTextAction()).fetchSemanticsNodes() +
            onAllNodes(isAScrollingContainer).fetchSemanticsNodes()
    assertTrue(
        "This fixture rendered nothing interactive, so it cannot demonstrate anything about " +
            "${whatItIs.replaceFirstChar { it.lowercase() }}. Populate the screen's state -- a " +
            "loading or empty variant passes trivially.",
        candidates.any { it.config.contains(SemanticsProperties.Focused) || it.boundsInRoot.height > 0f },
    )
    val deepest = candidates.maxOfOrNull { it.boundsInRoot.bottom } ?: 0f
    assertTrue(
        "This fixture's interactive content stops at y=$deepest px, well above the line at " +
            "y=$insetTopEdge px, so nothing on it could be covered whether or not the screen " +
            "handles insets -- the test would pass either way. Populate more state, or use the " +
            "state in which this screen actually fills the display.",
        deepest >= insetTopEdge * MIN_FILL_FRACTION,
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
 * Prefers a `testTag` (see `TestTags`). Note this is no longer why the container tags exist: since
 * the rule became scroll-to-end, only nodes with a click or set-text action are ever described, so
 * a viewport is never named here and cannot report as an unlabelled node. The container tags earn
 * their place in a `uiautomator` dump instead, which is a device concern rather than this one.
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
