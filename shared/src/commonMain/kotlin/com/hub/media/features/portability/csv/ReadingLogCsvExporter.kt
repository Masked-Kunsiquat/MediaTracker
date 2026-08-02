package com.hub.media.features.portability.csv

import com.hub.media.core.database.entities.ReadingSessionEntity

/**
 * Produces `reading_logs_export.csv` (ROADMAP Task 8 Phase A / vision doc §"Data Portability"):
 * one row per [ReadingSessionEntity], carrying every column that entity holds.
 *
 * Pure Kotlin/KMP-clean (no Android APIs) -- see [LibraryCsvExporter]'s KDoc for the same note,
 * which applies identically here.
 *
 * ### Column set (in order)
 * 1. [CSV_SCHEMA_VERSION_COLUMN] -- see [CSV_SCHEMA_VERSION]'s KDoc.
 * 2. `session_id` -- [ReadingSessionEntity.id].
 * 3. `media_id` -- [ReadingSessionEntity.mediaId], the FK linking a row here back to a row in
 *    `library_export.csv`.
 * 4. `timestamp_start` -- ISO-8601 UTC (`kotlin.time.Instant.toString()`).
 * 5. `timestamp_end` -- ISO-8601 UTC.
 * 6. `duration_seconds` -- **empty when [ReadingSessionEntity.durationSeconds] is `null`, never
 *    `0`.** This is the single most important rule this exporter enforces: schema v2 (ROADMAP Task
 *    5 pre-phase) made this column nullable specifically so "unknown duration" (a backlogged
 *    manual entry) and "a real zero-second session" (a genuine, valid edge case) would never
 *    collide -- see [ReadingSessionEntity]'s KDoc. Exporting `null` as `0` here would silently
 *    reintroduce exactly that collision one layer up, corrupting any future re-import's time-read
 *    stats. A real `0` duration exports as the literal text `0`, distinguishable from the empty
 *    field a `null` produces.
 * 7. `start_unit` -- [ReadingSessionEntity.startUnit] (page number or percent, AGENTS.md §3.5).
 * 8. `end_unit` -- [ReadingSessionEntity.endUnit].
 * 9. `delta_pages` -- empty when null.
 * 10. `notes` -- empty when null; free text, escaped like any other field (commas, quotes, and
 *     embedded newlines in a session note are expected and must round-trip -- see
 *     [CsvUtil.escapeField]).
 */
public object ReadingLogCsvExporter {

    /** Header row, in column order -- see class KDoc for what each column holds. */
    public val HEADER: List<String> = listOf(
        CSV_SCHEMA_VERSION_COLUMN,
        "session_id",
        "media_id",
        "timestamp_start",
        "timestamp_end",
        "duration_seconds",
        "start_unit",
        "end_unit",
        "delta_pages",
        "notes",
    )

    /**
     * Builds the complete CSV text for [sessions], including the header row.
     *
     * @param sessions Every reading session to export, in the order they should appear (callers
     *   typically pass a full-library, most-recent-first or chronological snapshot -- row order
     *   carries no semantic meaning here, since every row is fully self-identified by
     *   `session_id`/`media_id`).
     */
    public fun export(sessions: List<ReadingSessionEntity>): String = buildString {
        append(CsvUtil.buildLine(HEADER))
        for (session in sessions) {
            append(CsvUtil.buildLine(rowFor(session)))
        }
    }

    private fun rowFor(session: ReadingSessionEntity): List<String> = listOf(
        CSV_SCHEMA_VERSION.toString(),
        session.id,
        session.mediaId,
        session.timestampStart.toString(),
        session.timestampEnd.toString(),
        // Deliberately NOT `?: 0` -- see class KDoc point 6. `null` must stay the empty field.
        session.durationSeconds?.toString().orEmpty(),
        session.startUnit.toString(),
        session.endUnit.toString(),
        session.deltaPages?.toString().orEmpty(),
        session.notes.orEmpty(),
    )
}
