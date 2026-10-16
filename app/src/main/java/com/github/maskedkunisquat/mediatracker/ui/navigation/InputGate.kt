package com.github.maskedkunisquat.mediatracker.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/**
 * Lets [content] take touches only while [owner] -- a destination's back-stack entry -- is the
 * settled, front-most screen, i.e. [Lifecycle.State.RESUMED] (#126).
 *
 * During a navigation transition the arriving and the leaving screen are both composed and both
 * drawn, and whichever is on top takes the tap. On the hub that meant a second tap on a card, or a
 * tap just after Back, landed on the library row that happened to be under the finger and opened an
 * item the user never chose. Navigation only resumes an entry once its transition has finished, so
 * until then this consumes every pointer event before [content] sees it; the user simply taps again
 * once the screen has arrived.
 *
 * A filter on the wrapping [Box], not an overlay added while unresumed: the entry also drops below
 * RESUMED whenever the activity pauses (a system file picker over the app), and inserting a node at
 * that moment recomposed the tree structurally from outside a frame -- which the device suite caught
 * as a wrong-thread layout request. Here nothing is added or removed; only the filter's decision
 * changes. It reads the events in the Initial pass, ahead of every child, so a consumed tap never
 * reaches a button or row.
 *
 * Touches are blocked, not navigation: a screen that navigates by itself (an edit screen leaving when
 * its item turns out to be deleted) still does so mid-transition, which a guard on the navigate
 * calls would have swallowed.
 */
@Composable
internal fun BlockInputUntilResumed(
    owner: LifecycleOwner,
    content: @Composable () -> Unit,
) {
    val state by owner.lifecycle.currentStateAsState()
    val resumed by rememberUpdatedState(state.isAtLeast(Lifecycle.State.RESUMED))
    Box(
        modifier =
            Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!resumed) event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        content()
    }
}
