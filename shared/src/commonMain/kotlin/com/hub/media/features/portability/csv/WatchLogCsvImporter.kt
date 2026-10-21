package com.hub.media.features.portability.csv

import kotlin.time.Instant

/** One parsed `watch_logs_export.csv` row -- see [WatchLogCsvExporter]. */
public data class ParsedWatchLogRow(
    public val logId: String,
    public val mediaId: String,
    /** `null` for a film viewing (#192): the file leaves `episode_id` blank. */
    public val episodeId: String?,
    public val watchedAt: Instant,
    public val durationSeconds: Long?,
    /** The episode viewing's watch-through (#231); `null` for a film, or for a file older than CSV v8 (run #1). */
    public val runNumber: Int? = null,
    /** A film viewing's rating in half-steps (#231); always `null` for an episode. */
    public val rating: Int? = null,
)

public sealed class WatchLogRowParseResult {
    public data class Parsed(
        public val row: ParsedWatchLogRow,
    ) : WatchLogRowParseResult()

    public data class Rejected(
        public val reason: String,
    ) : WatchLogRowParseResult()
}

/**
 * Parses `watch_logs_export.csv` rows (#90). Resolving a row onto a library episode -- by id, or
 * by `(show, season, episode)` slot when ids did not round-trip -- is the import use case's job,
 * because only it holds the episode file's resolution.
 */
public object WatchLogCsvImporter {
    public fun parseRow(row: List<String>): WatchLogRowParseResult =
        try {
            WatchLogRowParseResult.Parsed(buildRow(row))
        } catch (e: RowRejectedException) {
            WatchLogRowParseResult.Rejected(e.message ?: "Invalid row")
        }

    /** A pre-#231 (CSV v7 and earlier) row, padded to the current width: no run number (run #1) and no rating. */
    public fun padLegacyV7Row(row: List<String>): List<String> = row + "" + ""

    private fun buildRow(row: List<String>): ParsedWatchLogRow {
        val episodeId = row[COL_EPISODE_ID].ifBlank { null }
        val runNumber =
            row[COL_RUN_NUMBER].ifBlank { null }?.let {
                it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: reject("watch_through_number must be 1 or more, got '$it'")
            }
        val rating = parseRating(row[COL_RATING])
        if (episodeId != null && rating != null) reject("an episode is rated per watch-through, not per viewing")
        if (episodeId == null && runNumber != null) reject("a film viewing has no watch_through_number")
        return ParsedWatchLogRow(
            logId = row[COL_LOG_ID].ifBlank { reject("log_id is required") },
            mediaId = row[COL_MEDIA_ID].ifBlank { reject("media_id is required") },
            episodeId = episodeId,
            watchedAt = parseRequiredInstant(row[COL_WATCHED_AT], "watched_at"),
            durationSeconds = parseOptionalLong(row[COL_DURATION_SECONDS], "duration_seconds"),
            runNumber = runNumber,
            rating = rating,
        )
    }

    // Column indices, matching WatchLogCsvExporter.HEADER's order exactly.
    private const val COL_LOG_ID = 1
    private const val COL_MEDIA_ID = 2
    private const val COL_EPISODE_ID = 3
    private const val COL_WATCHED_AT = 4
    private const val COL_DURATION_SECONDS = 5
    private const val COL_RUN_NUMBER = 6
    private const val COL_RATING = 7
}
