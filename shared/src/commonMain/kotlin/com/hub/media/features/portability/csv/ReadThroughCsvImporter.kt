package com.hub.media.features.portability.csv

import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.features.books.data.starsToHalves
import kotlin.time.Instant

/** One parsed row of `read_throughs_export.csv` (#230). [rating] is in half-steps, `null` when unrated. */
public data class ParsedReadThrough(
    public val mediaId: String,
    public val number: Int,
    public val status: ReadingStatus,
    public val startedAt: Instant?,
    public val finishedAt: Instant?,
    public val rating: Int?,
)

public sealed class ReadThroughRowParseResult {
    public data class Parsed(
        public val read: ParsedReadThrough,
    ) : ReadThroughRowParseResult()

    public data class Rejected(
        public val reason: String,
    ) : ReadThroughRowParseResult()
}

/**
 * Parses `read_throughs_export.csv` rows (#230) with the rules the app holds a read to: a number of 1 or
 * more, a known status, To read only on read #1, and a rating on the half-star grid from half a star to
 * five -- `4.3` is refused rather than rounded, since a file that says it is not something the app wrote.
 */
public object ReadThroughCsvImporter {
    public fun parseRow(row: List<String>): ReadThroughRowParseResult =
        try {
            ReadThroughRowParseResult.Parsed(buildRow(row))
        } catch (e: RowRejectedException) {
            ReadThroughRowParseResult.Rejected(e.message ?: "Invalid row")
        }

    private fun buildRow(row: List<String>): ParsedReadThrough {
        val mediaId = row[COL_MEDIA_ID].ifBlank { reject("media_id is required") }
        val number =
            row[COL_NUMBER].toIntOrNull()?.takeIf { it >= 1 }
                ?: reject("number must be 1 or more, got '${row[COL_NUMBER]}'")
        val status =
            ReadingStatus.entries.firstOrNull { it.name == row[COL_STATUS] }
                ?: reject("status '${row[COL_STATUS]}' is not a reading status")
        if (status == ReadingStatus.TO_READ && number > 1) reject("only read 1 can be To read")
        val startedAt = parseOptionalInstant(row[COL_STARTED_AT], "started_at")
        val finishedAt = parseOptionalInstant(row[COL_FINISHED_AT], "finished_at")
        val rating =
            row[COL_RATING].ifBlank { null }?.let { raw ->
                raw.toDoubleOrNull()?.let(::starsToHalves)
                    ?: reject("rating '$raw' must be half a star to five stars, in half stars")
            }
        return ParsedReadThrough(mediaId, number, status, startedAt, finishedAt, rating)
    }

    // Column indices, matching ReadThroughCsvExporter.HEADER's order exactly.
    private const val COL_MEDIA_ID = 1
    private const val COL_NUMBER = 2
    private const val COL_STATUS = 3
    private const val COL_STARTED_AT = 4
    private const val COL_FINISHED_AT = 5
    private const val COL_RATING = 6
}
