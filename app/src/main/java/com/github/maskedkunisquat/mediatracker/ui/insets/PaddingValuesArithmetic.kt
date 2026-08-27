package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
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
 * The same padding with its top edge dropped.
 *
 * The other half of [exceptBottom]: the scrolling part of such a screen takes the bottom inset as
 * `contentPadding` so its last row clears the navigation bar, and must not re-apply the top inset
 * that the pinned element above it has already accounted for.
 */
@Composable
fun PaddingValues.exceptTop(): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(layoutDirection),
        top = 0.dp,
        end = calculateEndPadding(layoutDirection),
        bottom = calculateBottomPadding(),
    )
}
