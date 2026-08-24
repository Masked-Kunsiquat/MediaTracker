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
 *
 * **ROADMAP Task 13 Phase B update: `v3`.** [LibraryCsvExporter] gained `runtime_minutes`,
 * `watch_status` and `watched_at`, appended after `external_identifiers` so no existing column
 * moved. Both older shapes still import: `v2` via [LibraryCsvImporter.padLegacyV2Row] and `v1` via
 * [LibraryCsvImporter.padLegacyV1Row], which now inserts the `authors` column *and* pads to the
 * current width -- a `v1` file is two format changes behind, and one adapter has to carry both.
 *
 * Note this bump marks a column-set change the *exporter* made; [LibraryCsvImporter] still refuses
 * a `MOVIE` row, so the three new columns are written and not yet read. See
 * [LibraryCsvExporter]'s KDoc for why they are written anyway.
 *
 * **ROADMAP Task 13 Phase C update: `v4`.** [LibraryCsvExporter] gained `total_seasons`, appended
 * after `watched_at` so no existing column moved -- the same reasoning as the `v3` bump above,
 * applied to a show's season count instead of a movie's runtime/status. All three older shapes
 * still import: `v3` via [LibraryCsvImporter.padLegacyV3Row], `v2` via
 * [LibraryCsvImporter.padLegacyV2Row], and `v1` via [LibraryCsvImporter.padLegacyV1Row] -- each of
 * which now pads all the way to the current width, not just to its own successor's.
 *
 * This phase also adds a second new file, `episodes_export.csv`
 * ([com.hub.media.features.portability.csv.EpisodeCsvExporter]), for a show's episodes -- one row
 * per episode, since an episode is one-to-many under a show exactly like a
 * [com.hub.media.core.database.entities.ReadingSessionEntity] is one-to-many under a book, and so
 * does not fit `library_export.csv`'s one-row-per-item shape. That file is not governed by this
 * marker's "shared by both files" opening paragraph textually, but does carry the same
 * `csv_schema_version` column for the same self-describing-row reason.
 *
 * As with `v3`, [LibraryCsvImporter] still refuses a `TV_SHOW` row, so `total_seasons` is written
 * and not yet read, and `episodes_export.csv` is exported but has no importer at all yet -- see
 * [LibraryCsvExporter]'s KDoc and [EpisodeCsvExporter]'s KDoc respectively.
 */
public const val CSV_SCHEMA_VERSION: Int = 4

/** Column name the [CSV_SCHEMA_VERSION] marker is written under in both export files. */
public const val CSV_SCHEMA_VERSION_COLUMN: String = "csv_schema_version"
