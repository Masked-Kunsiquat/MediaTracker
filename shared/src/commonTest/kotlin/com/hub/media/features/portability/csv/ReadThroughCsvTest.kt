package com.hub.media.features.portability.csv

import com.hub.media.core.database.entities.ReadThroughEntity
import com.hub.media.core.database.entities.ReadingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/** `read_throughs_export.csv` (#230): each read's number, status, dates and rating, written and read back. */
class ReadThroughCsvTest {
    private val header = ReadThroughCsvExporter.HEADER.joinToString(",")

    private fun parseOne(line: String) = ReadThroughCsvImporter.parseRow(line.split(","))

    @Test
    fun aRead_roundTripsThroughExportAndParse() {
        val read =
            ReadThroughEntity(
                id = "r2",
                mediaId = "b",
                number = 2,
                status = ReadingStatus.FINISHED,
                startedAt = Instant.fromEpochMilliseconds(1_000),
                finishedAt = Instant.fromEpochMilliseconds(2_000),
                rating = 9,
            )
        val lines = ReadThroughCsvExporter.export(listOf(read)).trim().lines()

        assertEquals(header, lines[0])
        val parsed =
            ReadThroughCsvImporter.parseRow(
                (CsvReader.parse(lines[1]) as CsvParseResult.Success).rows.single(),
            )
        assertIs<ReadThroughRowParseResult.Parsed>(parsed)
        assertEquals(
            ParsedReadThrough(
                "b",
                2,
                ReadingStatus.FINISHED,
                Instant.fromEpochMilliseconds(1_000),
                Instant.fromEpochMilliseconds(2_000),
                9,
            ),
            parsed.read,
        )
        assertEquals("4.5", lines[1].split(",").last(), "a rating is written in stars")
    }

    /** Review Focus 5: a rating off the half-star grid rejects its row, with a reason. */
    @Test
    fun readThroughsCsv_offGridRating_isRejected() {
        for (bad in listOf("4.3", "0", "6", "abc", "-1")) {
            val result = parseOne("$CSV_SCHEMA_VERSION,b,1,FINISHED,,,$bad")
            assertIs<ReadThroughRowParseResult.Rejected>(result, "rating '$bad'")
            assertEquals(true, result.reason.contains("rating"), result.reason)
        }
    }

    @Test
    fun aRereadAtToRead_isRejected() {
        assertIs<ReadThroughRowParseResult.Rejected>(parseOne("$CSV_SCHEMA_VERSION,b,2,TO_READ,,,"))
    }

    @Test
    fun aMissingOrZeroNumber_andAnUnknownStatus_areRejected() {
        assertIs<ReadThroughRowParseResult.Rejected>(parseOne("$CSV_SCHEMA_VERSION,b,0,READING,,,"))
        assertIs<ReadThroughRowParseResult.Rejected>(parseOne("$CSV_SCHEMA_VERSION,b,,READING,,,"))
        assertIs<ReadThroughRowParseResult.Rejected>(parseOne("$CSV_SCHEMA_VERSION,b,1,SKIMMED,,,"))
        assertIs<ReadThroughRowParseResult.Rejected>(parseOne("$CSV_SCHEMA_VERSION,,1,READING,,,"))
    }

    @Test
    fun anEmptyRating_isUnrated_andWholeStarsParse() {
        val unrated = parseOne("$CSV_SCHEMA_VERSION,b,1,READING,,,")
        assertIs<ReadThroughRowParseResult.Parsed>(unrated)
        assertNull(unrated.read.rating)

        val five = parseOne("$CSV_SCHEMA_VERSION,b,1,FINISHED,,,5")
        assertIs<ReadThroughRowParseResult.Parsed>(five)
        assertEquals(10, five.read.rating)
    }
}
