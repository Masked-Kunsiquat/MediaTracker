package com.hub.media.ui

/**
 * Reading numeric text out of a form field, shared by every add/edit form in this package
 * (ROADMAP Task 13 Phase C; extracted from [EditMovieViewModel], which introduced it in Phase B).
 *
 * ### The distinction these exist to preserve
 * A numeric field has **three** outcomes, not two, and `toIntOrNull()`/`toDoubleOrNull()` can only
 * express two of them:
 * - blank — the user is telling us the value is *unknown*, which is a legitimate thing to store;
 * - readable — the number they typed;
 * - unreadable — text that is not a number at all.
 *
 * Collapsing the third into the first is how an edit form silently erases the very value the user
 * opened it to correct, so [parseOptionalNumber] keeps them apart. This is not hypothetical even
 * behind a digits-only input filter: a run of digits longer than [Int] holds ("19999999999" for a
 * year) parses to nothing and would otherwise be written as "unknown".
 *
 * These live in their own file because two same-named `private` top-level declarations in one
 * package collide at the JVM class-file level regardless of visibility — the second form to want
 * this idiom could not simply copy it, and the copy it did make had to be renamed around the clash.
 */
public class ParsedNumber<T : Any>(
    /** The number, or `null` when the field was blank — "unknown". */
    public val value: T?,
)

/**
 * Reads an optional numeric field: [ParsedNumber] with a `null` [ParsedNumber.value] for blank
 * text, [ParsedNumber] with the number for text that parses, and `null` for text that does not
 * parse at all — see this file's KDoc for why that third case is kept distinct.
 */
public fun <T : Any> parseOptionalNumber(
    text: String,
    parse: (String) -> T?,
): ParsedNumber<T>? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ParsedNumber(null)
    return parse(trimmed)?.let { ParsedNumber(it) }
}

/**
 * Reads a **required** whole-number field: `null` for both blank text and text that fails to parse.
 *
 * Unlike [parseOptionalNumber], the caller has no legitimate "unknown" to fall back on — a blank
 * episode count on a season row is an unfinished row, not a known-absent value — so both failure
 * shapes collapse to one "not usable" outcome.
 */
public fun parseRequiredInt(text: String): Int? = text.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()

/**
 * Keeps only the digits of a keystroke-by-keystroke integer field.
 *
 * Lives here rather than beside the screens that call it: it was previously a private extension at
 * the bottom of `BookDetailScreen`, which every other add/edit form reached into purely because
 * they share a package. Filtering and parsing are two halves of reading one field, and the parse
 * half is called from ViewModels while the filter half is called from composables, so the pair has
 * to sit somewhere both can see.
 */
public fun String.filterIntegerInput(): String = filter { it.isDigit() }

/**
 * Keeps the digits of a decimal field and at most one decimal point, normalising the separator the
 * user's keyboard offers into the `.` that [String.toDoubleOrNull] can actually read.
 *
 * ### Why [decimalSeparator] is a parameter and not a constant
 * `KeyboardType.Decimal` shows whichever separator the locale uses, so a comma-decimal locale
 * offers `,`. The previous version of this filter kept digits and `.` only, which meant `,` was
 * *dropped* rather than translated: "14,99" became "1499", and `toDoubleOrNull` — locale-invariant,
 * it only ever accepts `.` — parsed that cleanly into a price a hundred times too large, with no
 * error and nothing on screen to notice.
 *
 * Accepting both `.` and `,` as decimal points would fix that locale and break the other one:
 * an en-US user typing "1,499" means one thousand four hundred ninety-nine, and turning it into
 * "1.499" is the same class of silent corruption pointing the other way. Taking the locale's
 * separator as an argument gets both right — whichever character is *not* the decimal separator is
 * a thousands separator, and dropping it is the correct reading:
 *
 * | separator | input     | result |
 * |-----------|-----------|--------|
 * | `.`       | `1,499`   | `1499` |
 * | `,`       | `1.499`   | `1499` |
 * | `,`       | `14,99`   | `14.99`|
 *
 * `commonMain` cannot see the platform locale, which is the other half of why this is a parameter:
 * the app module looks the separator up and passes it in, and this stays testable without one.
 *
 * @param decimalSeparator The character the user's keyboard offers as a decimal point. Anything
 *   else non-digit is discarded, including a second occurrence of this one.
 */
public fun String.filterDecimalInput(decimalSeparator: Char): String {
    val builder = StringBuilder()
    var seenSeparator = false
    for (char in this) {
        when {
            char.isDigit() -> builder.append(char)
            char == decimalSeparator && !seenSeparator -> {
                builder.append('.')
                seenSeparator = true
            }
        }
    }
    return builder.toString()
}
