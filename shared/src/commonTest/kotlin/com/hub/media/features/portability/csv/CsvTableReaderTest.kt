package com.hub.media.features.portability.csv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests [CsvTableReader] -- the structural validation layer (ROADMAP Task 8 Phase B) that decides
 * the three whole-file rejections this phase's brief calls out: a completely empty file, a row
 * with the wrong column count, and (deliverable #2) a `csv_schema_version` newer than this app
 * understands.
 */
class CsvTableReaderTest {

    private val header = listOf("csv_schema_version", "a", "b")

    @Test
    fun read_completelyEmptyFile_fails() {
        val result = CsvTableReader.read("", header)
        assertIs<CsvTableResult.Failure>(result)
        assertTrue(result.message.contains("empty", ignoreCase = true))
    }

    @Test
    fun read_leadingBom_doesNotBreakHeaderRecognition() {
        // PR review finding: a UTF-8 BOM prepended to a genuine MediaTracker export must not make
        // it get rejected as "unrecognized header" -- see CsvReader.parse's KDoc for where this is
        // stripped.
        val csv = "\uFEFF" + CsvUtil.buildLine(header) + CsvUtil.buildLine(listOf("1", "x", "y"))
        val result = CsvTableReader.read(csv, header)
        assertIs<CsvTableResult.Success>(result)
        assertEquals(header, result.header)
    }

    @Test
    fun read_unrecognizedHeader_fails() {
        val csv = CsvUtil.buildLine(listOf("wrong", "header", "row"))
        val result = CsvTableReader.read(csv, header)
        assertIs<CsvTableResult.Failure>(result)
        assertTrue(result.message.contains("header", ignoreCase = true))
    }

    @Test
    fun read_rowWithTooFewColumns_failsWithRowNumber() {
        val csv = CsvUtil.buildLine(header) + CsvUtil.buildLine(listOf("1", "onlyOneMore"))
        val result = CsvTableReader.read(csv, header)
        assertIs<CsvTableResult.Failure>(result)
        assertTrue(result.message.contains("Row 2"))
    }

    @Test
    fun read_rowWithTooManyColumns_fails() {
        val csv = CsvUtil.buildLine(header) + CsvUtil.buildLine(listOf("1", "x", "y", "extra"))
        val result = CsvTableReader.read(csv, header)
        assertIs<CsvTableResult.Failure>(result)
    }

    @Test
    fun read_secondRowWithWrongColumnCount_reportsRowThree() {
        val csv = CsvUtil.buildLine(header) +
            CsvUtil.buildLine(listOf("1", "x", "y")) +
            CsvUtil.buildLine(listOf("1", "onlyOne"))
        val result = CsvTableReader.read(csv, header)
        assertIs<CsvTableResult.Failure>(result)
        assertTrue(result.message.contains("Row 3"))
    }

    @Test
    fun read_schemaVersionNewerThanSupported_fails() {
        val newerVersion = CSV_SCHEMA_VERSION + 1
        val csv = CsvUtil.buildLine(header) + CsvUtil.buildLine(listOf(newerVersion.toString(), "x", "y"))
        val result = CsvTableReader.read(csv, header)
        assertIs<CsvTableResult.Failure>(result)
        assertTrue(result.message.contains("newer", ignoreCase = true))
    }

    @Test
    fun read_schemaVersionEqualToSupported_succeeds() {
        val csv = CsvUtil.buildLine(header) + CsvUtil.buildLine(listOf(CSV_SCHEMA_VERSION.toString(), "x", "y"))
        val result = CsvTableReader.read(csv, header)
        assertIs<CsvTableResult.Success>(result)
    }

    @Test
    fun read_nonIntegerSchemaVersion_fails() {
        val csv = CsvUtil.buildLine(header) + CsvUtil.buildLine(listOf("not-a-number", "x", "y"))
        val result = CsvTableReader.read(csv, header)
        assertIs<CsvTableResult.Failure>(result)
    }

    @Test
    fun read_headerOnlyFile_succeedsWithNoDataRows() {
        // A header-only export (empty library) is well-formed -- there's simply nothing to check
        // the version against, and nothing is malformed about it.
        val csv = CsvUtil.buildLine(header)
        val result = CsvTableReader.read(csv, header)
        assertIs<CsvTableResult.Success>(result)
        assertEquals(emptyList(), result.rows)
    }

    @Test
    fun read_unterminatedQuote_propagatesReaderFailure() {
        val csv = CsvUtil.buildLine(header) + "1,\"unterminated\r\n"
        val result = CsvTableReader.read(csv, header)
        assertIs<CsvTableResult.Failure>(result)
        assertTrue(result.message.contains("Unterminated", ignoreCase = true))
    }

    @Test
    fun read_validMultiRowFile_succeedsWithHeaderStripped() {
        val csv = CsvUtil.buildLine(header) +
            CsvUtil.buildLine(listOf("1", "x1", "y1")) +
            CsvUtil.buildLine(listOf("1", "x2", "y2"))
        val result = CsvTableReader.read(csv, header)
        assertIs<CsvTableResult.Success>(result)
        assertEquals(header, result.header)
        assertEquals(2, result.rows.size)
    }
}
