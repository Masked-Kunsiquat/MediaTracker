package com.github.maskedkunisquat.mediatracker.ui.goldens

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.WindowInsets
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue

/**
 * Screenshot goldens for the canonical screens (#102).
 *
 * ### What this lane is for, and what it is not for
 *
 * The occlusion lane (`ui/insets/Occlusion.kt`) catches anything expressible as an invariant over
 * bounds, and it stays the first choice: an assertion needs no reviewer, while a golden only ever
 * catches a regression if a human reads the diff. What an invariant cannot catch is *appearance* —
 * a colour that goes wrong in dark mode, a control that keeps its bounds while its contents
 * overflow, spacing that collapses at a large font scale. #99 changed how every screen relates to
 * the system bars, which is exactly that kind of change, and nothing in the repository could see it.
 *
 * ### Why Roborazzi rather than Paparazzi, restated where it is actionable
 *
 * Paparazzi renders through LayoutLib, which has no real `Window` and no `WindowInsetsCompat`
 * dispatch, so `WindowInsets.safeDrawing` resolves to zero there. It is structurally incapable of
 * rendering the bug class this project keeps hitting — the same objection that rules out the
 * Compose Preview screenshot tool. Roborazzi runs on Robolectric, so these goldens inherit
 * `app/src/test/resources/robolectric.properties` wholesale: the pinned SDK, the Pixel-class
 * 411x891dp screen, and the bare `Application` that keeps Room from booting. One notion of "the
 * test device", shared with the lane #96 built, rather than a second one free to drift from it.
 *
 * That inheritance is also why [captureGolden] reports system bar insets rather than rendering
 * against a bare window: with no insets reported, a golden of a post-#99 screen shows none of the
 * edge-to-edge arrangement it exists to protect, and the whole reason for choosing this tool goes
 * unused.
 *
 * ### The rot rules, and which of them this file can actually enforce
 *
 * The predictable failure is goldens regenerated reflexively until the test asserts nothing. That
 * is not hypothetical: #96's harness shipped a first draft in which four of seven tests asserted
 * nothing at all, and no green run showed it. #102 sets three rules; two are enforced here and the
 * third cannot be:
 *
 * 1. **Every screenshot test also asserts something non-visual in the same test body.** Enforced by
 *    [alsoAssert] being a required parameter with no default — a screenshot test that asserts only
 *    the PNG will not compile. Re-recording a golden then cannot make the test vacuous, because the
 *    assertion does not live in the image.
 * 2. **The paired assertion is falsified when introduced.** Cannot be enforced by a signature: an
 *    assertion that cannot fail is just a second thing quietly asserting nothing, which is exactly
 *    what happened in #96 and again in #99, where a fixture reached the right screen and still
 *    could not fail. Break the code, watch the assertion fail, restore. [assertTheFixtureIsWorthAPicture]
 *    is a floor under this, not a substitute for it.
 * 3. **Goldens are recorded by a human, never in CI**, and a golden-only commit stays separate from
 *    an assertion change. This is a process rule and lives in AGENTS.md; nothing here can check it.
 *    CI runs `verifyRoborazziDebug`, which compares against committed PNGs and never writes one.
 */
private object Golden {
    /**
     * Height of the reported navigation bar, in dp, matching `Occlusion.NavigationBarHeight`.
     *
     * Deliberately the same number as the occlusion lane rather than a second opinion about what a
     * navigation bar is. 48dp is Android's three-button bar; the gesture bar is smaller, and the
     * larger of the two is the one that shows more in a golden.
     */
    const val NAVIGATION_BAR_DP = 48

    /** Height of the reported status bar, in dp. 24dp is the long-standing platform value. */
    const val STATUS_BAR_DP = 24

    /**
     * How much of the display a fixture's content must occupy to be worth a picture.
     *
     * The same idea as the occlusion lane's `MIN_FILL_FRACTION` and a looser number, because this
     * rejects a different thing: not a fixture too short to be occluded, but a fixture that
     * rendered a spinner or an empty state and would produce a golden showing nothing. Two-fifths
     * is a floor for "something is actually on screen", not a layout opinion.
     */
    const val MIN_FILL_FRACTION = 0.4f
}

/**
 * Which colour scheme a golden is recorded in.
 *
 * `dynamicColor` is forced off for both (see [captureGolden]), so these are the app's own palettes
 * rather than the platform's.
 */
enum class Theme { LIGHT, DARK }

/**
 * Renders [content] as a canonical screen, asserts [alsoAssert] against it, and writes or verifies
 * the golden named [name].
 *
 * Ordering is deliberate: the assertions run **before** the capture. A screen that has already
 * failed its non-visual assertion should not also leave a regenerated PNG behind for someone to
 * commit — the failure is the output.
 *
 * [alsoAssert] has no default. That is rule 1 of #102 expressed in the type system rather than in a
 * comment: there is no way to write a test through this function that asserts only an image. It can
 * still be passed an empty lambda, which is why rule 2 (falsify it) is not optional.
 *
 * @param name File name of the golden, without extension. Becomes `app/src/test/screenshots/<name>.png`.
 * @param theme Which palette to record. Defaults to [Theme.LIGHT]; [Theme.DARK] is recorded for the
 *   screens where a colour mistake would be worst rather than for all of them, since every extra
 *   golden is another image a reviewer has to actually look at.
 * @param fontScale System font scale. `1f` unless the screen is being recorded specifically to show
 *   that its spacing survives a large one.
 * @param alsoAssert The non-visual assertion this screenshot is paired with. Required.
 * @param content The screen under test, called with fabricated state and no-op callbacks.
 */
fun ComposeContentTestRule.captureGolden(
    name: String,
    theme: Theme = Theme.LIGHT,
    fontScale: Float = 1f,
    alsoAssert: ComposeContentTestRule.() -> Unit,
    content: @Composable () -> Unit,
) {
    val statusBarPx = with(density) { Golden.STATUS_BAR_DP.dp.roundToPx() }
    val navigationBarPx = with(density) { Golden.NAVIGATION_BAR_DP.dp.roundToPx() }

    setContent {
        val bars =
            WindowInsetsCompat
                .Builder()
                .setInsets(
                    WindowInsetsCompat.Type.systemBars(),
                    Insets.of(0, statusBarPx, 0, navigationBarPx),
                ).setVisible(WindowInsetsCompat.Type.systemBars(), true)
                .build()
        DeviceConfigurationOverride(
            DeviceConfigurationOverride.WindowInsets(bars) then
                DeviceConfigurationOverride.FontScale(fontScale),
        ) {
            MediaTrackerTheme(
                darkTheme = theme == Theme.DARK,
                // Off, and this is load-bearing rather than tidy. On API 31+ the default pulls the
                // palette from the device wallpaper, which would make every golden a picture of
                // whatever colours Robolectric's framework happens to supply -- a hidden input that
                // can shift under a Robolectric upgrade and would be read as an app regression.
                // These goldens are of the app's own colour schemes.
                dynamicColor = false,
            ) {
                content()
            }
        }
    }
    waitForIdle()

    assertTheFixtureIsWorthAPicture(name)
    alsoAssert()

    // `onRoot()` would throw: DeviceConfigurationOverride hosts the overridden content in a
    // composition of its own, so the tree has two coincident roots and "expected exactly 1" fails.
    // The occlusion lane resolves the same problem the same way -- take the tallest.
    //
    // The explicit Screenshot capture type is not a default worth relying on. Roborazzi's other
    // mode, Dump, renders the *semantics tree* -- labelled boxes over node bounds -- rather than
    // pixels, and the first recording here silently produced three of those: byte-identical files
    // for the light, dark and large-font variants, because a diagram of the node tree does not vary
    // with the palette or the font scale. A golden lane whose images cannot show a colour mistake
    // is precisely the no-op #102 exists to prevent, and it looked like a successful recording.
    tallestRoot().captureRoboImage(
        filePath = "src/test/screenshots/$name.png",
        roborazziOptions = RoborazziOptions(captureType = RoborazziOptions.CaptureType.Screenshot()),
    )
}

/**
 * Asserts that a named test tag is present, for use as a [captureGolden] pairing.
 *
 * The cheapest assertion that is not vacuous. A tag proves a control survived whatever refactor is
 * being screenshotted, which a PNG cannot: an image diff shows a missing button as pixels that
 * changed, and pixels that changed are exactly what a reviewer waves through when re-recording.
 *
 * Not sufficient on its own for a screen whose risk is behaviour rather than presence -- pair those
 * with a bounds assertion from the occlusion lane instead.
 */
fun ComposeContentTestRule.assertTagsExist(vararg tags: String) {
    tags.forEach { tag ->
        assertTrue(
            "This screen no longer carries the test tag \"$tag\", so its golden is a picture of a " +
                "screen that has lost a control. Either the tag was dropped in a refactor, or the " +
                "constant outlived the control and should be deleted from TestTags.",
            onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty(),
        )
    }
}

/**
 * The positive control: fails if the fixture would produce a golden of nothing much.
 *
 * A loading spinner, an empty state or a screen whose fabricated data ran out after two rows all
 * render, all capture, and all produce a green PNG that would not move if the screen broke. This is
 * the golden-lane equivalent of the occlusion harness's `assertTheScreenCouldHaveFailed`, and it
 * exists for the same reason: in this repository, twice now, a harness's real failure mode has been
 * asserting nothing rather than asserting wrongly.
 *
 * Necessary, not sufficient -- see rule 2 in this file's KDoc.
 */
private fun ComposeContentTestRule.assertTheFixtureIsWorthAPicture(name: String) {
    val interactive =
        onAllNodes(hasClickAction() or hasSetTextAction()).fetchSemanticsNodes() +
            onAllNodes(isAScrollingContainer).fetchSemanticsNodes()
    assertTrue(
        "Golden \"$name\" rendered nothing interactive, so the image would show a screen nobody " +
            "can use -- a loading or empty variant, most likely. Populate the fixture's state.",
        interactive.isNotEmpty(),
    )

    val rootHeight = tallestRootNode().size.height
    val deepest = interactive.maxOf { it.boundsInRoot.bottom }
    assertTrue(
        "Golden \"$name\"'s content stops at y=$deepest px on a ${rootHeight}px display, so most " +
            "of the image is empty and a regression in the part that matters would barely move " +
            "it. Populate more state, or record the state in which this screen actually fills " +
            "the display.",
        deepest >= rootHeight * Golden.MIN_FILL_FRACTION,
    )
}

private val isAScrollingContainer =
    SemanticsMatcher("is a scrolling container") {
        it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null ||
            it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
    }

private fun ComposeContentTestRule.tallestRootNode(): SemanticsNode =
    onAllNodes(isRoot()).fetchSemanticsNodes().maxBy { it.size.height }

private fun ComposeContentTestRule.tallestRoot() =
    onAllNodes(isRoot()).filter(
        SemanticsMatcher("is the tallest root") { it.id == tallestRootNode().id },
    )[0]
