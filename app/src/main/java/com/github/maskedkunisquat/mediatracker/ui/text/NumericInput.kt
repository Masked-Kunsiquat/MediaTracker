package com.github.maskedkunisquat.mediatracker.ui.text

import com.hub.media.ui.filterDecimalInput
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
 * Note the field then *displays* the normalised text: a comma-decimal user typing "14,99" sees
 * "14.99". That is deliberate for now -- it is the honest representation of what will be stored,
 * and showing a localised string while parsing a canonical one needs separate display and edit
 * representations, which is a larger change than the defect being fixed here warrants.
 */
internal fun String.filterDecimalInput(): String = filterDecimalInput(localeDecimalSeparator())
