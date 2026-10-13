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

    private fun buildRow(row: List<String>): ParsedWatchLogRow =
        ParsedWatchLogRow(
            logId = row[COL_LOG_ID].ifBlank { reject("log_id is required") },
            mediaId = row[COL_MEDIA_ID].ifBlank { reject("media_id is required") },
            episodeId = row[COL_EPISODE_ID].ifBlank { null },
            watchedAt = parseRequiredInstant(row[COL_WATCHED_AT], "watched_at"),
            durationSeconds = parseOptionalLong(row[COL_DURATION_SECONDS], "duration_seconds"),
        )

    // Column indices, matching WatchLogCsvExporter.HEADER's order exactly.
    private const val COL_LOG_ID = 1
    private const val COL_MEDIA_ID = 2
    private const val COL_EPISODE_ID = 3
    private const val COL_WATCHED_AT = 4
    private const val COL_DURATION_SECONDS = 5
}
