package com.hub.media.features.portability.csv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests [CsvReader], the genuine RFC 4180 parser this phase's brief requires (ROADMAP Task 8
 * Phase B) -- NOT `split(",")`. Priority per that brief: quoted commas/quotes/embedded newlines
 * (a multi-line record must parse as ONE row), plus the documented malformed-input behaviors
 * (unterminated quote, wrong column count -- covered by [CsvTableReaderTest] since column-count
 * validation lives one layer up -- and a completely empty file).
 */
class CsvReaderTest {

    @Test
    fun parse_emptyFile_returnsEmptyRows() {
        val result = CsvReader.parse("")
        assertIs<CsvParseResult.Success>(result)
        assertEquals(emptyList(), result.rows)
    }

    @Test
    fun parse_simplePlainRows_splitsOnCommaAndCrlf() {
        val result = CsvReader.parse("a,b,c\r\nd,e,f\r\n")
        assertIs<CsvParseResult.Success>(result)
        assertEquals(listOf(listOf("a", "b", "c"), listOf("d", "e", "f")), result.rows)
    }

    @Test
    fun parse_lastRowWithoutTrailingNewline_isStillIncluded() {
        val result = CsvReader.parse("a,b\r\nc,d")
        assertIs<CsvParseResult.Success>(result)
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), result.rows)
    }

    @Test
    fun parse_trailingRowTerminator_doesNotProduceFictitiousEmptyRow() {
        // Mirrors CsvUtil.buildLine always terminating its last line the same way interior lines
        // are terminated -- reading that output back must not manufacture an extra empty row.
        val result = CsvReader.parse("a,b\r\nc,d\r\n")
        assertIs<CsvParseResult.Success>(result)
        assertEquals(2, result.rows.size)
    }

    @Test
    fun parse_quotedFieldContainingComma_isOneField() {
        val result = CsvReader.parse("\"a, b\",c\r\n")
        assertIs<CsvParseResult.Success>(result)
        assertEquals(listOf(listOf("a, b", "c")), result.rows)
    }

    @Test
    fun parse_quotedFieldWithDoubledQuote_unescapesToSingleQuote() {
        val result = CsvReader.parse("\"a \"\"quoted\"\" word\"\r\n")
        assertIs<CsvParseResult.Success>(result)
        assertEquals(listOf(listOf("a \"quoted\" word")), result.rows)
    }

    @Test
    fun parse_quotedFieldWithEmbeddedNewline_isOneRowNotTwo() {
        // The key production-grade case a single-row test-only helper can't exercise: a session
        // note spanning lines must parse as one field within one row, not split into extra rows.
        val csv = "title,notes\r\n\"Dune\",\"line one\nline two\"\r\n\"Foundation\",\"plain\"\r\n"
        val result = CsvReader.parse(csv)
        assertIs<CsvParseResult.Success>(result)
        assertEquals(3, result.rows.size)
        assertEquals(listOf("Dune", "line one\nline two"), result.rows[1])
        assertEquals(listOf("Foundation", "plain"), result.rows[2])
    }

    @Test
    fun parse_quotedFieldWithEmbeddedCrlf_preservesVerbatim() {
        val csv = "\"line one\r\nline two\"\r\n"
        val result = CsvReader.parse(csv)
        assertIs<CsvParseResult.Success>(result)
        assertEquals(listOf(listOf("line one\r\nline two")), result.rows)
    }

    @Test
    fun parse_unterminatedQuote_returnsFailure() {
        val result = CsvReader.parse("a,\"unterminated\r\nb,c\r\n")
        assertIs<CsvParseResult.Failure>(result)
        assertTrue(result.message.contains("Unterminated", ignoreCase = true))
    }

    @Test
    fun parse_unterminatedQuoteAtVeryEndOfFile_returnsFailure() {
        val result = CsvReader.parse("a,\"never closed")
        assertIs<CsvParseResult.Failure>(result)
    }

    @Test
    fun parse_bareLfRowEnding_isAcceptedLeniently() {
        val result = CsvReader.parse("a,b\nc,d\n")
        assertIs<CsvParseResult.Success>(result)
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), result.rows)
    }

    @Test
    fun parse_emptyFieldsBetweenCommas_areEmptyStrings() {
        val result = CsvReader.parse("a,,c\r\n")
        assertIs<CsvParseResult.Success>(result)
        assertEquals(listOf(listOf("a", "", "c")), result.rows)
    }

    // --- leading UTF-8 BOM (Finding 3: a BOM must not become part of the first header cell) ---

    @Test
    fun parse_leadingBom_isStrippedNotKeptAsFieldData() {
        val result = CsvReader.parse("\uFEFFTitle,Author\r\nDune,Herbert\r\n")
        assertIs<CsvParseResult.Success>(result)
        assertEquals(listOf(listOf("Title", "Author"), listOf("Dune", "Herbert")), result.rows)
    }

    @Test
    fun parse_bomOnlyThenEmpty_returnsEmptyRows() {
        val result = CsvReader.parse("\uFEFF")
        assertIs<CsvParseResult.Success>(result)
        assertEquals(emptyList(), result.rows)
    }

    @Test
    fun parse_bomNotAtStart_isPreservedAsOrdinaryData() {
        // Only a *leading* BOM is stripped -- one appearing mid-document (e.g. inside a field,
        // however unusual) is left alone as ordinary data, not treated as invisible everywhere.
        val result = CsvReader.parse("a,b\r\nc\uFEFF,d\r\n")
        assertIs<CsvParseResult.Success>(result)
        assertEquals(listOf(listOf("a", "b"), listOf("c\uFEFF", "d")), result.rows)
    }

    @Test
    fun roundTrip_exportedLibraryCsvWithTrickyFields_parsesBackExactly() {
        // The strongest reader-level test: run real exporter output (with a comma, embedded
        // quotes, and an embedded newline in the title/notes fields) through CsvReader and confirm
        // every field comes back exactly as written -- see ImportDataUseCaseTest for the full
        // export-then-import round trip at the use-case level.
        val tricky = listOf("Title, with a comma", "He said \"hi\"", "multi\nline\nnote", "")
        val csv = CsvUtil.buildLine(listOf("h1", "h2", "h3", "h4")) + CsvUtil.buildLine(tricky)

        val result = CsvReader.parse(csv)
        assertIs<CsvParseResult.Success>(result)
        assertEquals(2, result.rows.size)
        assertEquals(tricky, result.rows[1])
    }
}
