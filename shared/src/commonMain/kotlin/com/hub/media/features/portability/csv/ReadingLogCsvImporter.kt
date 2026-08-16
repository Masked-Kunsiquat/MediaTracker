package com.hub.media.features.portability.csv

import com.hub.media.features.books.domain.ReadingSessionValidation
import kotlin.time.Instant

/** One successfully parsed `reading_logs_export.csv` data row -- mirrors [ReadingLogCsvExporter]'s column set. */
public data class ParsedSessionRow(
    public val sessionId: String,
    public val mediaId: String,
    public val timestampStart: Instant,
    public val timestampEnd: Instant,
    public val durationSeconds: Long?,
    public val startUnit: Double,
    public val endUnit: Double,
    public val deltaPages: Int?,
    public val notes: String?,
)

/** Outcome of parsing one `reading_logs_export.csv` data row. */
public sealed class SessionRowParseResult {
    public data class Parsed(
        public val row: ParsedSessionRow,
    ) : SessionRowParseResult()

    public data class Rejected(
        public val reason: String,
    ) : SessionRowParseResult()
}

/**
 * Parses `reading_logs_export.csv` data rows (ROADMAP Task 8 Phase B) -- see
 * [LibraryCsvImporter]'s KDoc for the same structural-vs-semantic division of responsibility.
 *
 * Timestamp/duration/position validation delegates to [ReadingSessionValidation] -- the exact
 * same rules [com.hub.media.features.books.data.ReadingSessionRepository.logSession] and
 * [com.hub.media.features.books.domain.LogReadingSessionUseCase] already enforce, so an imported
 * session can never be held to a different standard than a timer-logged or manually-entered one.
 *
 * Deliberately does NOT check `media_id` against the library here -- that requires knowledge of
 * every other book being imported (and every book already in the database), which is
 * `ImportDataUseCase`'s job (orphan-session detection), not a per-row parsing concern.
 */
public object ReadingLogCsvImporter {
    public fun parseRow(row: List<String>): SessionRowParseResult =
        try {
            SessionRowParseResult.Parsed(buildRow(row))
        } catch (e: RowRejectedException) {
            SessionRowParseResult.Rejected(e.message ?: "Invalid row")
        }

    private fun buildRow(row: List<String>): ParsedSessionRow {
        val sessionId = row[COL_SESSION_ID].ifBlank { reject("session_id is required") }
        val mediaId = row[COL_MEDIA_ID].ifBlank { reject("media_id is required") }

        val timestampStart = parseRequiredInstant(row[COL_TIMESTAMP_START], "timestamp_start")
        val timestampEnd = parseRequiredInstant(row[COL_TIMESTAMP_END], "timestamp_end")
        ReadingSessionValidation.validateTimestamps(timestampStart, timestampEnd)?.let { reject(it) }

        val durationSeconds = parseOptionalLong(row[COL_DURATION_SECONDS], "duration_seconds")
        ReadingSessionValidation.validateDuration(durationSeconds)?.let { reject(it) }

        val startUnit = parseRequiredDouble(row[COL_START_UNIT], "start_unit")
        val endUnit = parseRequiredDouble(row[COL_END_UNIT], "end_unit")
        ReadingSessionValidation.validatePositions(startUnit, endUnit)?.let { reject(it) }

        val deltaPages = parseOptionalInt(row[COL_DELTA_PAGES], "delta_pages")
        val notes = row[COL_NOTES].ifBlank { null }

        return ParsedSessionRow(
            sessionId = sessionId,
            mediaId = mediaId,
            timestampStart = timestampStart,
            timestampEnd = timestampEnd,
            durationSeconds = durationSeconds,
            startUnit = startUnit,
            endUnit = endUnit,
            deltaPages = deltaPages,
            notes = notes,
        )
    }

    // Column indices, matching ReadingLogCsvExporter.HEADER's order exactly.
    private const val COL_SESSION_ID = 1
    private const val COL_MEDIA_ID = 2
    private const val COL_TIMESTAMP_START = 3
    private const val COL_TIMESTAMP_END = 4
    private const val COL_DURATION_SECONDS = 5
    private const val COL_START_UNIT = 6
    private const val COL_END_UNIT = 7
    private const val COL_DELTA_PAGES = 8
    private const val COL_NOTES = 9
}
