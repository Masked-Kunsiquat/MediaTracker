package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Arithmetic on [PaddingValues], for screens that draw behind the system bars.
 *
 * Edge-to-edge splits one set of padding into two jobs that used to be one. A `Scaffold` hands its
 * content a single `innerPadding` covering the bars, the top app bar and the IME; a screen that
 * draws behind the bars can no longer apply that as `Modifier.padding` on the container, because
 * doing so is precisely what stops the list from scrolling under them. It has to reach the list's
 * `contentPadding` instead — where it now has to coexist with whatever spacing the list already
 * wanted — or be split by side, when part of the screen is pinned and part of it scrolls.
 *
 * Both operations are three lines of `calculate*Padding` each and neither is interesting, which is
 * exactly the argument for writing them once: the layout-direction-aware start/end accessors are
 * easy to get subtly wrong (`start` is not `left`), and a mistake produces a screen whose padding is
 * merely wrong rather than one that fails to compile.
 */

/**
 * The bar insets a scrolling container re-adds *inside* itself: horizontal and bottom, keyboard
 * deliberately absent.
 *
 * The keyboard is the reason this is not simply `innerPadding`. A `Scaffold` given
 * `contentWindowInsets = WindowInsets.safeDrawing` (PR #95) folds the IME into `innerPadding`
 * alongside the bars, and once the padding moves inside the scroller those two want opposite
 * treatment:
 *
 * - **Bars belong inside.** The navigation bar is translucent and content passing under it is the
 *   whole point; the inset comes back as trailing space so the last row still clears it.
 * - **The keyboard belongs outside**, as real padding that shrinks the viewport. A scroller whose
 *   viewport extends behind the keyboard reports a field as on-screen while the keyboard covers it,
 *   so focusing one scrolls it nowhere — which is PR #95's bug wearing a different hat, and one the
 *   occlusion lane's scroll-to-end rule would not catch, because the field *is* reachable by
 *   scrolling. It just isn't reached.
 *
 * So a form pairs this with `Modifier.imePadding()` on the container outside the scroll, and takes
 * the top bar's height from `innerPadding.calculateTopPadding()` — the one part of `innerPadding`
 * only the `Scaffold` knows.
 *
 * `systemBars ∪ displayCutout` is `safeDrawing` with the IME left out, by construction:
 * `safeDrawing` is the union of exactly those three.
 */
@Composable
fun barPaddingForScrollingContent(): PaddingValues =
    WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        .asPaddingValues()

/**
 * Everything a full-bleed scrolling container re-adds inside itself, in one value.
 *
 * The three parts, and why each is separate:
 *
 * - **The top app bar's height**, which only the `Scaffold` knows, so it arrives as
 *   [scaffoldPadding] and is read for its top edge alone. The rest of [scaffoldPadding] is not used
 *   — its bottom carries the IME, which belongs outside the scroll (see
 *   [barPaddingForScrollingContent]).
 * - **The bars**, so the last row clears the navigation bar while the middle of the list passes
 *   under it.
 * - **[own]** — whatever margin the list already wanted. It cannot simply be dropped in favour of
 *   the insets, and `contentPadding` takes one value, so the two are summed rather than chosen
 *   between.
 *
 * The caller still owns the keyboard: pair this with `Modifier.imePadding()` on the container
 * outside the scroll wherever the screen has a text field.
 */
@Composable
fun scrollingContentPadding(
    scaffoldPadding: PaddingValues,
    own: PaddingValues = PaddingValues(0.dp),
): PaddingValues =
    PaddingValues(top = scaffoldPadding.calculateTopPadding())
        .plus(barPaddingForScrollingContent())
        .plus(own)

/**
 * Sums two sets of padding side by side.
 *
 * The case this exists for: a list that wants 16dp of its own breathing room *and* has to clear the
 * navigation bar. `PaddingValues(16.dp)` alone puts the last row under the bar; `innerPadding`
 * alone loses the margin the design had. Neither can be dropped, and `contentPadding` takes one
 * value.
 */
@Composable
fun PaddingValues.plus(other: PaddingValues): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(layoutDirection) + other.calculateStartPadding(layoutDirection),
        top = calculateTopPadding() + other.calculateTopPadding(),
        end = calculateEndPadding(layoutDirection) + other.calculateEndPadding(layoutDirection),
        bottom = calculateBottomPadding() + other.calculateBottomPadding(),
    )
}

/**
 * The same padding with its bottom edge dropped.
 *
 * For the pinned upper half of a screen that is part fixed, part scrolling — a search field above a
 * list, say. The field needs the top and horizontal insets as real padding; the bottom inset is not
 * its problem, it belongs to the list underneath, and applying it here would push the list up off
 * the navigation bar again.
 */
@Composable
fun PaddingValues.exceptBottom(): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(layoutDirection),
        top = calculateTopPadding(),
        end = calculateEndPadding(layoutDirection),
        bottom = 0.dp,
    )
}

/**
 * The bottom bar inset alone — the other half of [exceptBottom].
 *
 * A screen that is part pinned, part scrolling applies the horizontal inset once, on the pinned
 * part, and must not apply it again on the list below: two applications of a display cutout indent
 * the list twice as far as the field above it, which is visible and wrong.
 * [barPaddingForScrollingContent] carries the horizontal inset because the screens using it have
 * nothing above the scroller to have applied it already. This one is for when something did.
 */
@Composable
fun barPaddingBelowContent(): PaddingValues =
    WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Bottom)
        .asPaddingValues()
