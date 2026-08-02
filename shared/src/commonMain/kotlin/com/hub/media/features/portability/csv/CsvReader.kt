package com.hub.media.features.portability.csv

/**
 * Result of tokenizing a raw CSV document into rows of fields (ROADMAP Task 8 Phase B) -- the
 * mirror of [CsvUtil]'s escaper, and the reason this whole file exists: import writes to the
 * user's real library (AGENTS.md §1), so a byte the reader gets wrong can corrupt or destroy data
 * that has no cloud copy. Every ambiguous case below is resolved in favor of "refuse and explain"
 * over "guess and proceed."
 */
public sealed class CsvParseResult {

    /** [rows] holds every row (including the header, if present), each a list of raw field text. */
    public data class Success(public val rows: List<List<String>>) : CsvParseResult()

    /** Structural CSV corruption was detected; [message] describes what and, where known, where. */
    public data class Failure(public val message: String) : CsvParseResult()
}

/**
 * Hand-rolled RFC 4180 CSV reader (ROADMAP Task 8 Phase B), the genuine parser [CsvUtil]'s KDoc
 * promises a future importer would need -- NOT `split(",")`. Mirrors [CsvUtilTest]'s test-only
 * `parseRow` helper (a single-row reader), generalized to a whole document: it walks the text one
 * character at a time rather than splitting on line breaks first, because a quoted field can
 * legitimately contain an embedded newline (a multi-line session note is one field, not a row
 * break) -- splitting by line before parsing quotes would corrupt exactly that case.
 *
 * ### Malformed-input decisions (documented here, per this phase's brief)
 * - **Unterminated quote** (a quoted field that never finds its closing `"` before end-of-file):
 *   [parse] returns [CsvParseResult.Failure]. Once inside an unterminated quote, every remaining
 *   character in the file -- including real row breaks -- would be silently swallowed into one
 *   giant final field; there is no way to recover the intended row boundaries, so this fails
 *   closed rather than guessing.
 * - **Completely empty file** (`text` is the empty string): returns
 *   [CsvParseResult.Success] with zero rows -- this reader has no opinion about headers, so an
 *   empty document is not itself malformed at this layer. [CsvTableReader] (the layer above, which
 *   *does* know what a header should look like) is what turns "zero rows" into a rejection.
 * - **A row with the wrong column count**: NOT this reader's concern -- it has no header to compare
 *   against, so every row is accepted as however many fields it tokenized to. [CsvTableReader]
 *   enforces uniform column count against the header.
 *
 * ### Row endings
 * A bare `\n`, a bare `\r`, or `\r\n` are all accepted as a row terminator when encountered
 * *outside* a quoted field (lenient reading of files that may not have been produced by
 * [CsvUtil]'s own CRLF-only writer); any of these characters *inside* a quoted field are preserved
 * verbatim as data, never treated as a row break. A trailing row terminator at end-of-file does
 * not produce a fictitious empty trailing row (mirrors [CsvUtil.buildLine] always terminating its
 * last line the same way its interior lines are terminated).
 */
public object CsvReader {

    public fun parse(text: String): CsvParseResult {
        if (text.isEmpty()) return CsvParseResult.Success(emptyList())

        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == CsvUtil.QUOTE -> {
                    if (text.getOrNull(i + 1) == CsvUtil.QUOTE) {
                        field.append(CsvUtil.QUOTE)
                        i++ // consume the doubled quote's second character too
                    } else {
                        inQuotes = false
                    }
                }
                inQuotes -> field.append(c)
                c == CsvUtil.QUOTE -> inQuotes = true
                c == CsvUtil.DELIMITER -> {
                    currentRow.add(field.toString())
                    field.clear()
                }
                c == '\r' || c == '\n' -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++ // consume the pair as one break
                    currentRow.add(field.toString())
                    field.clear()
                    rows.add(currentRow)
                    currentRow = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }

        if (inQuotes) {
            return CsvParseResult.Failure(
                "Unterminated quoted field beginning in row ${rows.size + 1} -- the file is truncated or corrupted.",
            )
        }

        // Trailing content with no terminating row break becomes the final row; a file that ended
        // exactly on a row break has nothing left to flush here (see class KDoc's "no fictitious
        // trailing row" rule).
        if (field.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(field.toString())
            rows.add(currentRow)
        }

        return CsvParseResult.Success(rows)
    }
}
