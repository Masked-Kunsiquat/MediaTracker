package com.hub.media.features.portability.csv

/**
 * Hand-rolled RFC 4180 CSV field escaping/row-building (ROADMAP Task 8 Phase A). AGENTS.md §5
 * forbids adding a third-party dependency for something this small and well-specified, so this is
 * the one place both [LibraryCsvExporter] and [ReadingLogCsvExporter] route quoting through,
 * rather than each hand-rolling its own copy.
 *
 * ### The rule (RFC 4180 §2.5-2.7)
 * A field is wrapped in double quotes if — and only if — it contains a comma, a double quote, or
 * a line break (`\n` or `\r`); every literal double quote inside a quoted field is doubled
 * (`"` -> `""`). A field needing none of that is emitted bare. This is exactly what makes the
 * escaping *unambiguous*: a conformant reader can always tell where a quoted field ends (an
 * unescaped `"` immediately followed by the delimiter or end-of-line) and always recover the
 * original text (undouble any `""` inside).
 *
 * ### Row/line endings
 * Rows are joined with CRLF (`\r\n`) per RFC 4180 §2.1, the most broadly compatible choice across
 * spreadsheet applications; a `\n` embedded *inside* a quoted field (e.g. a multi-line session
 * note) is left completely untouched -- it is data, not a row separator, once inside quotes.
 */
public object CsvUtil {
    /** RFC 4180 field delimiter. */
    public const val DELIMITER: Char = ','

    /** RFC 4180 quote character. */
    public const val QUOTE: Char = '"'

    /** RFC 4180 row terminator (§2.1) -- used between rows, never relied upon inside a field. */
    public const val LINE_ENDING: String = "\r\n"

    /**
     * Escapes a single field for safe inclusion in a CSV row: wraps it in [QUOTE] and doubles any
     * embedded quote whenever it contains [DELIMITER], [QUOTE], `\n`, or `\r`; returns [value]
     * unchanged otherwise (including when [value] is empty -- an empty field never needs
     * quoting).
     */
    public fun escapeField(value: String): String {
        val needsQuoting = value.any { it == DELIMITER || it == QUOTE || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        val doubled = value.replace(QUOTE.toString(), "$QUOTE$QUOTE")
        return "$QUOTE$doubled$QUOTE"
    }

    /** Builds one CSV row (no trailing line ending) by escaping and delimiter-joining [fields]. */
    public fun buildRow(fields: List<String>): String =
        fields.joinToString(separator = DELIMITER.toString()) { escapeField(it) }

    /** Builds one complete CSV row, including the trailing [LINE_ENDING]. */
    public fun buildLine(fields: List<String>): String = buildRow(fields) + LINE_ENDING
}
