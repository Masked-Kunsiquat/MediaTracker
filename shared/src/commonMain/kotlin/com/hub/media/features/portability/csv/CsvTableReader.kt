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
 * An **older** value cannot occur today: `v1` is both the current and the only version this app
 * has ever produced, so this branch is unreachable in practice. The forward plan for when a `v2`
 * format exists: this is where a per-version migration/adapter step would run *before* handing
 * [Success.rows] to [LibraryCsvImporter]/[ReadingLogCsvImporter] -- translating an older row shape
 * into the current one -- rather than teaching every downstream consumer about every historical
 * version. Building that machinery now, for a version that has never existed, would be exactly the
 * kind of speculative complexity this phase's brief asks to defer.
 */
public object CsvTableReader {

    public fun read(text: String, expectedHeader: List<String>): CsvTableResult {
        val parsed = CsvReader.parse(text)
        val rows = when (parsed) {
            is CsvParseResult.Failure -> return CsvTableResult.Failure(parsed.message)
            is CsvParseResult.Success -> parsed.rows
        }

        if (rows.isEmpty()) {
            return CsvTableResult.Failure("The file is empty -- no header row was found.")
        }

        val header = rows.first()
        if (header != expectedHeader) {
            return CsvTableResult.Failure(
                "Unrecognized header row -- this doesn't look like a MediaTracker export. " +
                    "Expected columns: ${expectedHeader.joinToString()}.",
            )
        }

        val dataRows = rows.drop(1)
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
            // fileVersion < CSV_SCHEMA_VERSION: unreachable today (see class KDoc's forward plan).
        }

        return CsvTableResult.Success(header, dataRows)
    }
}
