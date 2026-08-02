package com.hub.media.features.portability.csv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests [ReadingLogCsvImporter] (ROADMAP Task 8 Phase B). Priority: reusing
 * [com.hub.media.features.books.domain.ReadingSessionValidation]'s exact timestamp/duration/
 * position rules rather than a divergent copy.
 */
class ReadingLogCsvImporterTest {

    private fun validRow(
        sessionId: String = "session-1",
        mediaId: String = "media-1",
        timestampStart: String = "2024-01-01T00:00:00Z",
        timestampEnd: String = "2024-01-01T01:00:00Z",
        durationSeconds: String = "3600",
        startUnit: String = "0.0",
        endUnit: String = "50.0",
        deltaPages: String = "50",
        notes: String = "Good progress",
    ): List<String> = listOf(
        CSV_SCHEMA_VERSION.toString(), sessionId, mediaId, timestampStart, timestampEnd,
        durationSeconds, startUnit, endUnit, deltaPages, notes,
    )

    @Test
    fun parseRow_happyPath_parsesEveryField() {
        val result = ReadingLogCsvImporter.parseRow(validRow())
        assertIs<SessionRowParseResult.Parsed>(result)
        val row = result.row
        assertEquals("session-1", row.sessionId)
        assertEquals("media-1", row.mediaId)
        assertEquals(3600L, row.durationSeconds)
        assertEquals(0.0, row.startUnit)
        assertEquals(50.0, row.endUnit)
        assertEquals(50, row.deltaPages)
        assertEquals("Good progress", row.notes)
    }

    @Test
    fun parseRow_blankSessionId_isRejected() {
        val result = ReadingLogCsvImporter.parseRow(validRow(sessionId = ""))
        assertIs<SessionRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("session_id"))
    }

    @Test
    fun parseRow_blankMediaId_isRejected() {
        val result = ReadingLogCsvImporter.parseRow(validRow(mediaId = ""))
        assertIs<SessionRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("media_id"))
    }

    @Test
    fun parseRow_endBeforeStart_isRejected() {
        val result = ReadingLogCsvImporter.parseRow(
            validRow(timestampStart = "2024-01-02T00:00:00Z", timestampEnd = "2024-01-01T00:00:00Z"),
        )
        assertIs<SessionRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("timestampEnd"))
    }

    @Test
    fun parseRow_negativeDuration_isRejected() {
        val result = ReadingLogCsvImporter.parseRow(validRow(durationSeconds = "-1"))
        assertIs<SessionRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("durationSeconds"))
    }

    @Test
    fun parseRow_negativeStartUnit_isRejected() {
        val result = ReadingLogCsvImporter.parseRow(validRow(startUnit = "-1.0"))
        assertIs<SessionRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("startUnit"))
    }

    @Test
    fun parseRow_nanEndUnit_isRejected() {
        val result = ReadingLogCsvImporter.parseRow(validRow(endUnit = "NaN"))
        assertIs<SessionRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("endUnit"))
    }

    @Test
    fun parseRow_endLessThanStart_isAllowed_rereadIsNotAnError() {
        val result = ReadingLogCsvImporter.parseRow(validRow(startUnit = "100.0", endUnit = "80.0", deltaPages = "-20"))
        assertIs<SessionRowParseResult.Parsed>(result)
        assertEquals(-20, result.row.deltaPages)
    }

    @Test
    fun parseRow_blankOptionalFields_parseAsNull() {
        val result = ReadingLogCsvImporter.parseRow(
            validRow(durationSeconds = "", deltaPages = "", notes = ""),
        )
        assertIs<SessionRowParseResult.Parsed>(result)
        assertEquals(null, result.row.durationSeconds)
        assertEquals(null, result.row.deltaPages)
        assertEquals(null, result.row.notes)
    }

    @Test
    fun parseRow_zeroDurationZeroPositionSession_isAllowed() {
        val result = ReadingLogCsvImporter.parseRow(
            validRow(durationSeconds = "0", startUnit = "0.0", endUnit = "0.0", deltaPages = "0"),
        )
        assertIs<SessionRowParseResult.Parsed>(result)
        assertEquals(0L, result.row.durationSeconds)
    }
}
