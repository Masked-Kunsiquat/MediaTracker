package com.github.maskedkunisquat.mediatracker.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 * until then a transparent layer above [content] consumes every pointer event; the user simply taps
 * again once the screen has arrived.
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
    Box {
        content()
        if (!state.isAtLeast(Lifecycle.State.RESUMED)) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    // Initial pass: consumed before anything in [content] sees it.
                                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            }
                        },
            )
        }
    }
}
