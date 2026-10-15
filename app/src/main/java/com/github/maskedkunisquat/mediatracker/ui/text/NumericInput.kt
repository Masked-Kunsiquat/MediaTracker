package com.github.maskedkunisquat.mediatracker.ui.text

import com.hub.media.ui.filterDecimalInput
import com.hub.media.ui.toDisplayDecimal
import java.text.DecimalFormatSymbols

/**
 * The Android half of numeric form input: it supplies the one thing
 * [com.hub.media.ui.filterDecimalInput] cannot look up for itself, the locale's decimal separator.
 *
 * The filtering and parsing rules live in `shared`'s `NumericFormField.kt` so that ViewModels and
 * composables read a field the same way -- the add forms validate in the composable and the edit
 * forms validate in the ViewModel, so anything usable by only one of them would just become a
 * seventh copy. `commonMain` has no locale API, hence this thin shim rather than an expect/actual:
 * there is one platform, and it needs one character.
 */

/**
 * The decimal separator `KeyboardType.Decimal` offers on this device -- `.` in en-US, `,` in much
 * of Europe.
 *
 * Read per call rather than cached in a `val`: [DecimalFormatSymbols.getInstance] reads the default
 * locale, and a locale change while the app is backgrounded would leave a cached copy describing
 * the wrong keyboard.
 */
internal fun localeDecimalSeparator(): Char = DecimalFormatSymbols.getInstance().decimalSeparator

/**
 * Filters a decimal field's text against the device locale's separator, normalising it to the `.`
 * that [String.toDoubleOrNull] reads. See [com.hub.media.ui.filterDecimalInput] for why the
 * separator has to be decided rather than assumed.
 *
 * The field must DISPLAY its text through [toDisplayDecimal], not as stored. Compose feeds the
 * displayed text back to `onValueChange`, and in a comma locale this filter reads a displayed `.`
 * as a thousands mark (#201). This used to say showing the canonical text was "deliberate for
 * now"; it was the bug.
 */
internal fun String.filterDecimalInput(): String = filterDecimalInput(localeDecimalSeparator())

/**
 * What a decimal field displays: its canonical text with the device locale's separator. Pair it with
 * [filterDecimalInput] on every decimal field, `value = x.toDisplayDecimal()` and
 * `onValueChange = { x = it.filterDecimalInput() }`, so the filter only ever reads locale-style
 * text. See [com.hub.media.ui.toDisplayDecimal] for why displaying the canonical text corrupted
 * comma-locale input (#201).
 */
internal fun String.toDisplayDecimal(): String = toDisplayDecimal(localeDecimalSeparator())
