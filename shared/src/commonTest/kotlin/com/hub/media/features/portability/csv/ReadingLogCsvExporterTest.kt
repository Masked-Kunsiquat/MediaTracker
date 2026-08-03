package com.hub.media.features.portability.csv

import com.hub.media.core.database.entities.ReadingSessionEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests [ReadingLogCsvExporter] (ROADMAP Task 8 Phase A). The single highest-priority case here is
 * [export_nullDurationSeconds_exportsAsEmptyFieldNeverZero] -- see that test and
 * [ReadingSessionEntity]'s KDoc for why `null` and `0` must never collide.
 */
class ReadingLogCsvExporterTest {

    private val start = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val end = Instant.fromEpochMilliseconds(1_700_000_600_000)

    @Test
    fun export_emptyLog_producesOnlyHeaderRow() {
        val csv = ReadingLogCsvExporter.export(emptyList())
        assertEquals(CsvUtil.buildLine(ReadingLogCsvExporter.HEADER), csv)
    }

    @Test
    fun export_headerRow_includesSchemaVersionColumnFirst() {
        val csv = ReadingLogCsvExporter.export(emptyList())
        val header = csv.substringBefore(CsvUtil.LINE_ENDING)
        assertEquals(CSV_SCHEMA_VERSION_COLUMN, header.split(",").first())
    }

    @Test
    fun export_fullyPopulatedSession_includesEveryFieldInOrder() {
        val session = ReadingSessionEntity(
            id = "session-1",
            mediaId = "media-1",
            timestampStart = start,
            timestampEnd = end,
            durationSeconds = 600L,
            startUnit = 10.0,
            endUnit = 25.0,
            deltaPages = 15,
            notes = "Great chapter",
        )

        val csv = ReadingLogCsvExporter.export(listOf(session))
        val fields = csv.split(CsvUtil.LINE_ENDING)[1].split(",")

        assertEquals(CSV_SCHEMA_VERSION.toString(), fields[0])
        assertEquals("session-1", fields[1])
        assertEquals("media-1", fields[2])
        assertEquals(start.toString(), fields[3])
        assertEquals(end.toString(), fields[4])
        assertEquals("600", fields[5])
        assertEquals("10.0", fields[6])
        assertEquals("25.0", fields[7])
        assertEquals("15", fields[8])
        assertEquals("Great chapter", fields[9])
    }

    @Test
    fun export_nullDurationSeconds_exportsAsEmptyFieldNeverZero() {
        // The critical rule (schema v2, ROADMAP Task 5 pre-phase): a manual session with unknown
        // duration must export as an EMPTY field, never as the literal "0" -- "0" is reserved for a
        // real, valid zero-second session. Silently coalescing null -> 0 here would corrupt any
        // future re-import's time-read stats exactly the way the schema-v2 migration was written
        // to prevent one layer down.
        val session = ReadingSessionEntity(
            id = "session-2",
            mediaId = "media-1",
            timestampStart = start,
            timestampEnd = end,
            durationSeconds = null,
            startUnit = 0.0,
            endUnit = 10.0,
            deltaPages = null,
            notes = null,
        )

        val csv = ReadingLogCsvExporter.export(listOf(session))
        val fields = csv.split(CsvUtil.LINE_ENDING)[1].split(",")

        assertEquals("", fields[5], "null durationSeconds must export as an empty field, not '0'")
        assertTrue(!csv.contains(",0,")) // sanity: the literal zero never sneaks in anywhere on this row
    }

    @Test
    fun export_realZeroDurationSeconds_exportsAsLiteralZero() {
        // The other half of the same rule: a genuine 0-second session (a real, valid edge case per
        // AGENTS.md §7) must still export as "0", distinguishable from the empty field a null
        // produces above.
        val session = ReadingSessionEntity(
            id = "session-3",
            mediaId = "media-1",
            timestampStart = start,
            timestampEnd = start,
            durationSeconds = 0L,
            startUnit = 5.0,
            endUnit = 5.0,
            deltaPages = 0,
            notes = null,
        )

        val csv = ReadingLogCsvExporter.export(listOf(session))
        val fields = csv.split(CsvUtil.LINE_ENDING)[1].split(",")

        assertEquals("0", fields[5])
        assertEquals("0", fields[8]) // deltaPages 0 is likewise a real value, not "unknown"
    }

    @Test
    fun export_nullOptionalFields_exportAsEmpty() {
        val session = ReadingSessionEntity(
            id = "session-4",
            mediaId = "media-1",
            timestampStart = start,
            timestampEnd = end,
            durationSeconds = null,
            startUnit = 1.0,
            endUnit = 2.0,
            deltaPages = null,
            notes = null,
        )

        val csv = ReadingLogCsvExporter.export(listOf(session))
        val fields = csv.split(CsvUtil.LINE_ENDING)[1].split(",")

        assertEquals("", fields[5]) // duration_seconds
        assertEquals("", fields[8]) // delta_pages
        assertEquals("", fields[9]) // notes
    }

    @Test
    fun export_noteContainingEmbeddedNewline_isQuotedAndStaysOneRow() {
        val session = ReadingSessionEntity(
            id = "session-5",
            mediaId = "media-1",
            timestampStart = start,
            timestampEnd = end,
            durationSeconds = 120L,
            startUnit = 1.0,
            endUnit = 2.0,
            deltaPages = 1,
            notes = "First paragraph.\nSecond paragraph.",
        )

        val csv = ReadingLogCsvExporter.export(listOf(session))
        // Exactly 3 CRLF-terminated lines: header + one data row (the embedded \n inside the
        // quoted notes field is not a row separator) + trailing empty segment from split.
        val lines = csv.split(CsvUtil.LINE_ENDING)
        assertEquals(3, lines.size) // [header, dataRow, ""]
        assertTrue(lines[1].contains("\"First paragraph.\nSecond paragraph.\""))
    }

    @Test
    fun export_noteContainingCommaAndQuotes_isEscaped() {
        val session = ReadingSessionEntity(
            id = "session-6",
            mediaId = "media-1",
            timestampStart = start,
            timestampEnd = end,
            durationSeconds = 60L,
            startUnit = 1.0,
            endUnit = 2.0,
            deltaPages = null,
            notes = "Loved it, especially the \"twist\" ending",
        )

        val csv = ReadingLogCsvExporter.export(listOf(session))
        val dataLine = csv.split(CsvUtil.LINE_ENDING)[1]
        assertTrue(dataLine.contains("\"Loved it, especially the \"\"twist\"\" ending\""))
    }

    @Test
    fun export_multipleSessions_producesOneRowPerSession() {
        val sessions = listOf(
            ReadingSessionEntity("s1", "m1", start, end, 100L, 0.0, 10.0, 10, null),
            ReadingSessionEntity("s2", "m1", start, end, null, 10.0, 20.0, null, null),
            ReadingSessionEntity("s3", "m2", start, end, 0L, 0.0, 0.0, 0, null),
        )
        val csv = ReadingLogCsvExporter.export(sessions)
        val lines = csv.trimEnd().split(CsvUtil.LINE_ENDING)
        assertEquals(4, lines.size) // header + 3 sessions
    }
}
