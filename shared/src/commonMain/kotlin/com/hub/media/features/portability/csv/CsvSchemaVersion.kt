package com.hub.media.features.portability.csv

/**
 * Format/schema version marker for `library_export.csv` and `reading_logs_export.csv` (ROADMAP
 * Task 8 Phase A), bumped independently of the Room `@Database` version (AGENTS.md §8) whenever
 * a *later* phase changes either file's column set in a way an importer must know about.
 *
 * ### Mechanism: a leading column, not a dedicated first line
 * Two mechanisms were considered:
 * 1. A dedicated first line before the header (e.g. a `# mediatracker-csv v1` comment row).
 * 2. A `csv_schema_version` column, repeated on every data row.
 *
 * (2) is what's implemented, for two reasons. First, a comment-style first line makes the file
 * *ragged* -- row 1 has a different column count than the header/data rows below it -- which is
 * exactly the kind of thing a naive spreadsheet import or a hand-rolled parser can mishandle (does
 * it treat row 1 as the header? skip it? choke on the column-count mismatch?), whereas a leading
 * column keeps every row uniform and the file trivially still a well-formed table. Second, a
 * repeated column means each row is self-describing on its own -- useful if a file is ever
 * concatenated, truncated, or split -- rather than the version living only in a first line that
 * could be lost or misaligned separately from the data.
 *
 * The cost is a column whose value never varies within one export, which is a non-issue at the
 * personal-library scale this app targets, and the requirement from the ROADMAP that "a bare
 * `.csv` should still open sanely in a spreadsheet" is satisfied either way -- opening either file
 * in Excel/Sheets shows an ordinary table, just with one extra (very boring) column here.
 *
 * A future importer (ROADMAP Task 8's later phases) reads this column from the first data row and
 * refuses/warns on a file whose value it does not recognize, rather than guessing at a column
 * layout it was never written for.
 *
 * **Phase B update**: that importer is [com.hub.media.features.portability.csv.CsvTableReader],
 * which reads this column from the first data row and refuses the whole file if its value is
 * *newer* than [CSV_SCHEMA_VERSION] -- see that object's KDoc for the exact check and for the
 * forward plan on what an *older* value would mean once a `v2` format exists (nothing today: `v1`
 * is still the only version ever produced).
 *
 * **ROADMAP Task 9 Phase A update: `v2` now exists.** [LibraryCsvExporter] gained an `authors`
 * column (see [com.hub.media.core.database.entities.BookDetailsEntity.authors]'s KDoc), which is a
 * column-set change an importer must know about -- exactly what this constant exists to signal.
 * `reading_logs_export.csv`'s own column set is unchanged by this bump (this marker is shared by
 * both files, per this KDoc's opening paragraph, even though only one of them actually changed
 * shape). The forward plan mentioned above is now exercised for real:
 * [com.hub.media.features.portability.csv.CsvTableReader] accepts a `v1` file (no `authors` column)
 * via a registered legacy-header adapter that pads each row with an empty `authors` field before
 * handing it to [LibraryCsvImporter] -- see that object's KDoc and `LibraryCsvExporter.HEADER_V1`.
 * A `v1` file must still import cleanly; only the *exporter* ever writes `v2` now.
 */
public const val CSV_SCHEMA_VERSION: Int = 2

/** Column name the [CSV_SCHEMA_VERSION] marker is written under in both export files. */
public const val CSV_SCHEMA_VERSION_COLUMN: String = "csv_schema_version"
