package com.hub.media.features.portability.csv

import com.hub.media.features.books.data.starsToHalves

/** One parsed row of `watch_throughs_export.csv` (#231). [rating] is in half-steps, `null` when unrated. */
public data class ParsedWatchThrough(
    public val mediaId: String,
    public val number: Int,
    public val abandoned: Boolean,
    public val rating: Int?,
)

public sealed class WatchThroughRowParseResult {
    public data class Parsed(
        public val run: ParsedWatchThrough,
    ) : WatchThroughRowParseResult()

    public data class Rejected(
        public val reason: String,
    ) : WatchThroughRowParseResult()
}

/**
 * Parses `watch_throughs_export.csv` rows (#231) with the rules the app holds a run to: a number of 1 or
 * more, an `abandoned` of true or false, and a rating on the half-star grid -- refused, never rounded.
 */
public object WatchThroughCsvImporter {
    public fun parseRow(row: List<String>): WatchThroughRowParseResult =
        try {
            WatchThroughRowParseResult.Parsed(buildRow(row))
        } catch (e: RowRejectedException) {
            WatchThroughRowParseResult.Rejected(e.message ?: "Invalid row")
        }

    private fun buildRow(row: List<String>): ParsedWatchThrough {
        val mediaId = row[COL_MEDIA_ID].ifBlank { reject("media_id is required") }
        val number =
            row[COL_NUMBER].toIntOrNull()?.takeIf { it >= 1 }
                ?: reject("number must be 1 or more, got '${row[COL_NUMBER]}'")
        val abandoned =
            row[COL_ABANDONED].lowercase().toBooleanStrictOrNull()
                ?: reject("abandoned must be true or false, got '${row[COL_ABANDONED]}'")
        return ParsedWatchThrough(mediaId, number, abandoned, parseRating(row[COL_RATING]))
    }

    // Column indices, matching WatchThroughCsvExporter.HEADER's order exactly.
    private const val COL_MEDIA_ID = 1
    private const val COL_NUMBER = 2
    private const val COL_ABANDONED = 3
    private const val COL_RATING = 4
}

/** A rating column in stars (#230, #231): blank is unrated; anything off the half-star grid rejects the row. */
internal fun parseRating(raw: String): Int? =
    raw.ifBlank { null }?.let {
        it.toDoubleOrNull()?.let(::starsToHalves)
            ?: reject("rating '$it' must be half a star to five stars, in half stars")
    }
