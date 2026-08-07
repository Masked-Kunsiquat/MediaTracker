package com.hub.media.features.portability.csv

/** Structurally-validated CSV table, or a description of why the file was rejected outright. */
public sealed class CsvTableResult {

    /** [header] is exactly the expected header; every entry in [rows] has [header].size fields. */
    public data class Success(public val header: List<String>, public val rows: List<List<String>>) : CsvTableResult()

    /** The file was rejected wholesale -- see [CsvTableReader]'s KDoc for what triggers this. */
    public data class Failure(public val message: String) : CsvTableResult()
}

/**
 * Structural validation layer on top of [CsvReader] (ROADMAP Task 8 Phase B): given [text] and the
 * [expectedHeader] a well-formed export carries, either hands back the data rows (header stripped
 * off) or refuses the whole file with a clear reason. Every check here is deliberately whole-file,
 * not per-row -- these are signs the *file itself* is not a genuine MediaTracker export (or is
 * truncated/corrupted), which [ImportDataUseCase] must refuse before writing anything, as opposed
 * to a semantically-invalid *row* inside an otherwise well-formed file (which is a per-row
 * skip-with-report concern handled one layer up -- see that class's KDoc).
 *
 * ### The three whole-file rejections this phase's brief calls out
 * - **Completely empty file**: zero rows means no header was found at all -- rejected here (
 *   [CsvReader] itself has no opinion on headers, see its KDoc).
 * - **A row with the wrong column count**: every data row must have exactly [expectedHeader].size
 *   fields; a short/long row almost always means the file was truncated mid-write or hand-edited
 *   incorrectly, not that "the extra/missing field should be treated as empty" -- guessing which
 *   column shifted would risk silently misattributing a value to the wrong field (e.g. a
 *   `purchase_price` landing in `total_pages`), which AGENTS.md §1 rules out.
 * - **A version newer than this app understands**: see the version check below.
 *
 * ### Version compatibility (ROADMAP Task 8 Phase B requirement #2)
 * Every data row repeats [CSV_SCHEMA_VERSION_COLUMN] (see [CSV_SCHEMA_VERSION]'s KDoc for why it's
 * a column, not a header line). This reader reads it once, from the first data row -- Phase A's
 * exporter never varies it within one export, so a single read is authoritative -- and refuses the
 * file outright if that value is **newer** than [CSV_SCHEMA_VERSION], with a message telling the
 * user to update the app rather than silently mis-parsing a column layout this build was never
 * written for.
 *
 * An older value used to be unreachable (`v1` was both the current and the only version this app
 * ever produced), but ROADMAP Task 9 Phase A made it real: `library_export.csv` bumped to `v2`
 * (see [CSV_SCHEMA_VERSION]'s KDoc), and a `v1` file must still import cleanly. That forward plan is
 * implemented via [legacyHeaders]: a per-historical-header row adapter, run *before* [Success.rows]
 * is handed to [LibraryCsvImporter]/[ReadingLogCsvImporter] -- translating an older row shape into
 * the current one -- rather than teaching every downstream consumer about every historical version.
 *
 * @param expectedHeader The current header shape a well-formed, up-to-date export carries. A file
 *   whose header equals this is read as-is.
 * @param legacyHeaders Maps a recognized *older* header shape to a function that pads/reshapes one
 *   of its data rows into [expectedHeader]'s current shape (e.g. inserting a blank value at the
 *   position a since-added column now occupies). A file whose header matches one of these keys is
 *   accepted -- every data row is passed through the matching adapter before the row-length and
 *   `csv_schema_version` checks below run, so downstream code always sees rows shaped like
 *   [expectedHeader] regardless of which historical version the file was actually exported by.
 *   Empty by default (no legacy shape recognized) -- [ReadingLogCsvExporter]'s callers pass nothing
 *   here today since that file's column set hasn't changed since `v1`.
 */
public object CsvTableReader {

    public fun read(
        text: String,
        expectedHeader: List<String>,
        legacyHeaders: Map<List<String>, (List<String>) -> List<String>> = emptyMap(),
    ): CsvTableResult {
        val parsed = CsvReader.parse(text)
        val rows = when (parsed) {
            is CsvParseResult.Failure -> return CsvTableResult.Failure(parsed.message)
            is CsvParseResult.Success -> parsed.rows
        }

        if (rows.isEmpty()) {
            return CsvTableResult.Failure("The file is empty -- no header row was found.")
        }

        val fileHeader = rows.first()
        val legacyAdapter = legacyHeaders[fileHeader]
        if (fileHeader != expectedHeader && legacyAdapter == null) {
            return CsvTableResult.Failure(
                "Unrecognized header row -- this doesn't look like a MediaTracker export. " +
                    "Expected columns: ${expectedHeader.joinToString()}.",
            )
        }
        // Downstream code always sees the current header/shape: a recognized legacy file is
        // reported (and its rows adapted) as if it were expectedHeader-shaped, since every row was
        // just normalized to match it.
        val header = expectedHeader

        val dataRows = rows.drop(1).let { raw -> if (legacyAdapter != null) raw.map(legacyAdapter) else raw }
        dataRows.forEachIndexed { index, row ->
            if (row.size != header.size) {
                return CsvTableResult.Failure(
                    "Row ${index + 2} has ${row.size} column(s), expected ${header.size} -- " +
                        "the file may be truncated or corrupted.",
                )
            }
        }

        if (dataRows.isNotEmpty()) {
            val versionColumnIndex = header.indexOf(CSV_SCHEMA_VERSION_COLUMN)
            val rawVersion = dataRows.first()[versionColumnIndex]
            val fileVersion = rawVersion.toIntOrNull()
                ?: return CsvTableResult.Failure(
                    "Row 2's $CSV_SCHEMA_VERSION_COLUMN ('$rawVersion') is not a valid integer.",
                )
            if (fileVersion > CSV_SCHEMA_VERSION) {
                return CsvTableResult.Failure(
                    "This file was exported by a newer version of MediaTracker " +
                        "($CSV_SCHEMA_VERSION_COLUMN=$fileVersion) than this app understands " +
                        "(v$CSV_SCHEMA_VERSION). Update the app before importing this file.",
                )
            }
            // fileVersion < CSV_SCHEMA_VERSION: expected for a recognized legacy header (its
            // adapted rows still carry their original, older version value in this column -- that's
            // fine, this reader only ever refuses a *newer* file, never an older one it knows how to
            // adapt).
        }

        return CsvTableResult.Success(header, dataRows)
    }
}
