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
internal class ParsedNumber<T : Any>(
    /** The number, or `null` when the field was blank — "unknown". */
    val value: T?,
)

/**
 * Reads an optional numeric field: [ParsedNumber] with a `null` [ParsedNumber.value] for blank
 * text, [ParsedNumber] with the number for text that parses, and `null` for text that does not
 * parse at all — see this file's KDoc for why that third case is kept distinct.
 */
internal fun <T : Any> parseOptionalNumber(
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
internal fun parseRequiredInt(text: String): Int? = text.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
