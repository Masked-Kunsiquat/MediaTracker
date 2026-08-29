package com.github.maskedkunisquat.mediatracker.ui.text

/**
 * Display formatting for book reading-progress values -- the inverse of `NumericInput.kt`, this
 * package's sibling: that file constrains what a user can *type* into a numeric field, this one
 * formats a stored [Double] back out for display.
 *
 * [formatUnit] moved here from the bottom of `BookDetailScreen.kt` (#81 item 1) because it had a
 * caller outside that file -- `EditBookScreen` -- reaching it by package accident with no explicit
 * import. That is the coupling #78 was filed about, and the move is what turns it into a dependency
 * visible at the call site.
 */

/**
 * Formats a [Double] position value, dropping a trailing `.0` for whole-number pages. Also reused
 * by `EditBookScreen` (ROADMAP Task 6 Phase A) to prefill the purchase-price field -- which is now
 * a different package and therefore an explicit import; see this file's KDoc.
 */
internal fun formatUnit(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
