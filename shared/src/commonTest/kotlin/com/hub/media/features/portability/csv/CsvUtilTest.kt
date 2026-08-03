package com.hub.media.features.portability.csv

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests [CsvUtil]'s RFC 4180 escaping (ROADMAP Task 8 Phase A) -- the priority per AGENTS.md §7's
 * task brief for this phase: a silently wrong quote/comma/newline rule is the single most likely
 * source of a corrupt export, so every edge case the brief calls out gets its own test, plus a
 * genuine round-trip check via [parseSingleRow] (a small hand-rolled RFC 4180 reader, test-only --
 * production code never needs to parse CSV until Phase B's importer).
 */
class CsvUtilTest {

    @Test
    fun escapeField_plainValue_isUnchanged() {
        assertEquals("hello world", CsvUtil.escapeField("hello world"))
    }

    @Test
    fun escapeField_emptyValue_isUnchangedAndUnquoted() {
        assertEquals("", CsvUtil.escapeField(""))
    }

    @Test
    fun escapeField_blankValue_isUnchangedAndUnquoted() {
        // A blank (whitespace-only) field contains none of the RFC 4180 special characters, so it
        // needs no quoting -- distinct from an empty string only in that it has content, but that
        // content is still "safe" as-is.
        assertEquals("   ", CsvUtil.escapeField("   "))
    }

    @Test
    fun escapeField_valueContainingComma_isQuoted() {
        assertEquals("\"a, b\"", CsvUtil.escapeField("a, b"))
    }

    @Test
    fun escapeField_valueContainingDoubleQuote_isQuotedAndQuoteIsDoubled() {
        assertEquals("\"a \"\"quoted\"\" title\"", CsvUtil.escapeField("a \"quoted\" title"))
    }

    @Test
    fun escapeField_valueContainingEmbeddedNewline_isQuotedAndNewlinePreservedVerbatim() {
        val note = "line one\nline two"
        val escaped = CsvUtil.escapeField(note)
        assertEquals("\"line one\nline two\"", escaped)
    }

    @Test
    fun escapeField_valueContainingCarriageReturn_isQuoted() {
        val note = "line one\rline two"
        assertEquals("\"line one\rline two\"", CsvUtil.escapeField(note))
    }

    @Test
    fun escapeField_valueContainingCommaAndQuoteAndNewline_handlesAllThreeAtOnce() {
        val value = "title, \"with quotes\"\nand a newline"
        val escaped = CsvUtil.escapeField(value)
        assertEquals("\"title, \"\"with quotes\"\"\nand a newline\"", escaped)
        assertEquals(value, parseSingleField(escaped))
    }

    @Test
    fun buildRow_joinsEscapedFieldsWithComma() {
        val row = CsvUtil.buildRow(listOf("a", "b, c", "d\"e"))
        assertEquals("a,\"b, c\",\"d\"\"e\"", row)
    }

    @Test
    fun buildLine_appendsCrlf() {
        val line = CsvUtil.buildLine(listOf("a", "b"))
        assertEquals("a,b\r\n", line)
    }

    // --- Round-trip checks: escape then parse back, via a minimal test-only RFC 4180 reader ---

    @Test
    fun roundTrip_titleContainingCommaAndAbbreviation() {
        assertRoundTrips("a, b")
    }

    @Test
    fun roundTrip_titleContainingDoubleQuotes() {
        assertRoundTrips("a \"quoted\" title")
    }

    @Test
    fun roundTrip_noteContainingEmbeddedNewline() {
        assertRoundTrips("first line\nsecond line")
    }

    @Test
    fun roundTrip_emptyField() {
        assertRoundTrips("")
    }

    @Test
    fun roundTrip_blankField() {
        assertRoundTrips("   ")
    }

    @Test
    fun roundTrip_multipleFieldsInOneRowEachRecoveredExactly() {
        val fields = listOf("Dune, Book One", "He said \"hello\"", "line1\nline2", "", "plain")
        val row = CsvUtil.buildRow(fields)
        assertEquals(fields, parseRow(row))
    }

    private fun assertRoundTrips(original: String) {
        val escaped = CsvUtil.escapeField(original)
        assertEquals(original, parseSingleField(escaped))
    }

    private fun parseSingleField(escapedField: String): String = parseRow(escapedField).single()
}

/**
 * Minimal RFC 4180 single-row reader, test-only: splits [row] into fields, un-quoting/un-doubling
 * exactly the inverse of [CsvUtil.escapeField]. Deliberately not shipped in `commonMain` -- CSV
 * *parsing* is Phase B's (import) concern, not this phase's -- but writing one here is the most
 * direct way to prove the escaper's output is genuinely unambiguous, rather than only asserting
 * against hand-computed expected strings.
 */
private fun parseRow(row: String): List<String> {
    val fields = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < row.length) {
        val c = row[i]
        when {
            inQuotes && c == CsvUtil.QUOTE -> {
                val next = row.getOrNull(i + 1)
                if (next == CsvUtil.QUOTE) {
                    current.append(CsvUtil.QUOTE)
                    i++ // consume the doubled quote's second character too
                } else {
                    inQuotes = false
                }
            }
            !inQuotes && c == CsvUtil.QUOTE -> inQuotes = true
            !inQuotes && c == CsvUtil.DELIMITER -> {
                fields.add(current.toString())
                current.clear()
            }
            else -> current.append(c)
        }
        i++
    }
    fields.add(current.toString())
    return fields
}
