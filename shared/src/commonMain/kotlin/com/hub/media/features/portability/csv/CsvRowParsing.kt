package com.hub.media.features.portability.csv

import com.hub.media.core.database.entities.IdentifierProvider
import kotlin.time.Instant

/**
 * Internal control-flow exception used by [LibraryCsvImporter]/[ReadingLogCsvImporter] to bail out
 * of building a row's typed representation at the first invalid field, carrying a human-readable
 * reason. Never escapes those objects' public `parseRow` functions -- both catch it and turn it
 * into a `Rejected(reason)` result. A plain exception (rather than threading a `Result`/`Either`
 * through every field parse) keeps each row-builder function reading top-to-bottom as a flat list
 * of "parse this field, validate it" steps.
 */
internal class RowRejectedException(message: String) : Exception(message)

/** Aborts the current row build with [message] -- see [RowRejectedException]. */
internal fun reject(message: String): Nothing = throw RowRejectedException(message)

/** Parses an optional integer field: blank -> `null`; non-blank and unparseable -> rejects. */
internal fun parseOptionalInt(raw: String, field: String): Int? =
    if (raw.isBlank()) null else raw.toIntOrNull() ?: reject("$field is not a valid integer: '$raw'")

/** Parses an optional long field: blank -> `null`; non-blank and unparseable -> rejects. */
internal fun parseOptionalLong(raw: String, field: String): Long? =
    if (raw.isBlank()) null else raw.toLongOrNull() ?: reject("$field is not a valid integer: '$raw'")

/** Parses an optional double field: blank -> `null`; non-blank and unparseable -> rejects. */
internal fun parseOptionalDouble(raw: String, field: String): Double? =
    if (raw.isBlank()) null else raw.toDoubleOrNull() ?: reject("$field is not a valid number: '$raw'")

/** Parses a required double field; blank or unparseable both reject. */
internal fun parseRequiredDouble(raw: String, field: String): Double =
    raw.toDoubleOrNull() ?: reject("$field is not a valid number: '$raw'")

/** Parses a required ISO-8601 instant field (matches [kotlin.time.Instant.toString]'s format). */
internal fun parseRequiredInstant(raw: String, field: String): Instant = try {
    Instant.parse(raw)
} catch (e: IllegalArgumentException) {
    reject("$field is not a valid timestamp: '$raw'")
}

/** Parses an optional ISO-8601 instant field: blank -> `null`; non-blank and unparseable -> rejects. */
internal fun parseOptionalInstant(raw: String, field: String): Instant? =
    if (raw.isBlank()) null else parseRequiredInstant(raw, field)

/**
 * Unpacks `library_export.csv`'s `external_identifiers` column -- `PROVIDER:id` pairs joined by
 * `|` (see [LibraryCsvExporter]'s KDoc for the encoding, and the packing side's `packIdentifiers`)
 * -- back into (provider, id) pairs (ROADMAP Task 8 Phase B, deliverable #4).
 *
 * ### The `|`/`:` hazard, and what this does about it
 * Phase A's encoding assumes a provider id never itself contains `:` or `|` -- true of every id
 * this app currently produces (ISBN digits, Open Library `OL...` keys, Google Books volume ids),
 * but not something the format can enforce. This function does NOT make that assumption blindly:
 * - **An id containing `:`** round-trips correctly: each `|`-separated segment is split on only
 *   its *first* `:` (`indexOf`, not a naive `split(":")`), so `TMDB:abc:def` unpacks to
 *   `(TMDB, "abc:def")` rather than being mangled.
 * - **An id containing a literal `|`** cannot be losslessly recovered from this encoding -- the
 *   packed field itself has no escaping for `|` (see [LibraryCsvExporter]'s KDoc: "only the `:`/`|`
 *   separators are assumed identifier-safe"). Rather than silently accepting the resulting
 *   mis-split segments as if they were correct providers/ids, this function detects the failure
 *   mode -- a segment with no `:` at all, or a provider name that isn't a known
 *   [IdentifierProvider] -- and rejects with a clear reason, one layer up, as a per-row validation
 *   failure (see [LibraryCsvImporter]). This is a real, documented limitation of Phase A's format
 *   (fixing it losslessly would require changing the export encoding, out of scope for an
 *   importer), not a gap Phase B silently papers over.
 *
 * @return The unpacked list, or a [RowRejectedException]-carrying failure via [reject] if any
 *   segment doesn't parse as `PROVIDER:id`. Blank [raw] yields an empty list (no identifiers).
 */
internal fun unpackIdentifiers(raw: String): List<Pair<IdentifierProvider, String>> {
    if (raw.isBlank()) return emptyList()

    return raw.split("|").map { segment ->
        val colonIndex = segment.indexOf(':')
        if (colonIndex < 0) {
            reject(
                "external_identifiers segment '$segment' is missing its ':' provider separator -- " +
                    "the field may be corrupted, or an identifier contains a literal '|' " +
                    "(not supported by this encoding; see LibraryCsvExporter's KDoc).",
            )
        }
        val providerName = segment.substring(0, colonIndex)
        val externalId = segment.substring(colonIndex + 1)
        val provider = try {
            IdentifierProvider.valueOf(providerName)
        } catch (e: IllegalArgumentException) {
            reject("Unknown identifier provider '$providerName' in external_identifiers")
        }
        if (externalId.isBlank()) {
            reject("external_identifiers segment '$segment' has an empty id")
        }
        provider to externalId
    }
}
