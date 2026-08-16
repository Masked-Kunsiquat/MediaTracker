package com.hub.media.features.portability.goodreads

import com.hub.media.features.portability.csv.CsvUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests [GoodreadsCsvTableReader] -- the header-*tolerant* structural layer (ROADMAP Task 8 Phase
 * D). Priority: this phase's brief that a Goodreads export must import cleanly regardless of
 * column order, unknown extra columns, or missing optional columns, with only `Title` required.
 */
class GoodreadsCsvTableReaderTest {
    @Test
    fun read_completelyEmptyFile_fails() {
        val result = GoodreadsCsvTableReader.read("")
        assertIs<GoodreadsCsvTableResult.Failure>(result)
        assertTrue(result.message.contains("empty", ignoreCase = true))
    }

    @Test
    fun read_missingTitleColumn_fails() {
        val csv =
            CsvUtil.buildLine(listOf(GoodreadsColumns.ISBN, GoodreadsColumns.BINDING)) +
                CsvUtil.buildLine(listOf("9780000000001", "Hardcover"))
        val result = GoodreadsCsvTableReader.read(csv)
        assertIs<GoodreadsCsvTableResult.Failure>(result)
        assertTrue(result.message.contains("Title"))
    }

    @Test
    fun read_titleHeaderWithLeadingBom_stillRecognized() {
        // PR review finding: a UTF-8 BOM (U+FEFF) prepended to the file -- as Excel does when
        // saving a UTF-8 CSV -- must not make a genuine Goodreads export get rejected as
        // unrecognized. String.trim() does NOT strip U+FEFF (it isn't Unicode whitespace), so
        // without stripping it upstream (in CsvReader), the first header cell would parse as
        // "\uFEFFTitle" and never match GoodreadsColumns.TITLE.
        val csv = "\uFEFF" + CsvUtil.buildLine(listOf(GoodreadsColumns.TITLE)) + CsvUtil.buildLine(listOf("Some Title"))
        val result = GoodreadsCsvTableReader.read(csv)
        assertIs<GoodreadsCsvTableResult.Success>(result)
        assertEquals(mapOf(GoodreadsColumns.TITLE to 0), result.columnIndex)
    }

    @Test
    fun read_titleOnlyHeader_succeeds() {
        // Every other column this importer knows about is missing -- still a valid, if minimal,
        // Goodreads export as far as structural validation is concerned.
        val csv = CsvUtil.buildLine(listOf(GoodreadsColumns.TITLE)) + CsvUtil.buildLine(listOf("Some Title"))
        val result = GoodreadsCsvTableReader.read(csv)
        assertIs<GoodreadsCsvTableResult.Success>(result)
        assertEquals(mapOf(GoodreadsColumns.TITLE to 0), result.columnIndex)
    }

    @Test
    fun read_reorderedColumns_columnIndexReflectsActualPositions() {
        // Binding before Title, ISBN13 last -- a deliberately non-canonical order.
        val header = listOf(GoodreadsColumns.BINDING, GoodreadsColumns.TITLE, GoodreadsColumns.ISBN13)
        val csv = CsvUtil.buildLine(header) + CsvUtil.buildLine(listOf("Paperback", "Some Title", "9780593135204"))
        val result = GoodreadsCsvTableReader.read(csv)
        assertIs<GoodreadsCsvTableResult.Success>(result)
        assertEquals(0, result.columnIndex[GoodreadsColumns.BINDING])
        assertEquals(1, result.columnIndex[GoodreadsColumns.TITLE])
        assertEquals(2, result.columnIndex[GoodreadsColumns.ISBN13])
    }

    @Test
    fun read_unknownExtraColumns_tolerated() {
        // "Book Id"/"Author"/"Publisher" etc. aren't in GoodreadsColumns at all -- must not break
        // structural validation, just be ignored.
        val header = listOf("Book Id", GoodreadsColumns.TITLE, "Author", "Publisher", "Owned Copies")
        val csv =
            CsvUtil.buildLine(header) +
                CsvUtil.buildLine(listOf("123", "Some Title", "Some Author", "Some Publisher", "1"))
        val result = GoodreadsCsvTableReader.read(csv)
        assertIs<GoodreadsCsvTableResult.Success>(result)
        assertEquals(1, result.columnIndex[GoodreadsColumns.TITLE])
    }

    @Test
    fun read_rowWithWrongColumnCount_refusesWholeFile() {
        val header = listOf(GoodreadsColumns.TITLE, GoodreadsColumns.BINDING)
        val csv = CsvUtil.buildLine(header) + CsvUtil.buildLine(listOf("Only One Field"))
        val result = GoodreadsCsvTableReader.read(csv)
        assertIs<GoodreadsCsvTableResult.Failure>(result)
        assertTrue(result.message.contains("Row 2"))
    }

    @Test
    fun read_unterminatedQuote_propagatesReaderFailure() {
        val csv = CsvUtil.buildLine(listOf(GoodreadsColumns.TITLE)) + "\"unterminated\r\n"
        val result = GoodreadsCsvTableReader.read(csv)
        assertIs<GoodreadsCsvTableResult.Failure>(result)
        assertTrue(result.message.contains("Unterminated", ignoreCase = true))
    }

    @Test
    fun read_multiRowFile_succeedsWithHeaderStripped() {
        val header = listOf(GoodreadsColumns.TITLE, GoodreadsColumns.EXCLUSIVE_SHELF)
        val csv =
            CsvUtil.buildLine(header) +
                CsvUtil.buildLine(listOf("Book One", "read")) +
                CsvUtil.buildLine(listOf("Book Two", "to-read"))
        val result = GoodreadsCsvTableReader.read(csv)
        assertIs<GoodreadsCsvTableResult.Success>(result)
        assertEquals(2, result.rows.size)
    }
}
