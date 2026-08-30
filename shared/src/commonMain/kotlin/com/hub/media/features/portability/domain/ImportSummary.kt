package com.hub.media.features.portability.domain

/**
 * Which imported file a rejected row came from.
 *
 * [MEDIA] was named `BOOK` until Issue #106, when `library_export.csv` stopped being a books-only
 * file on the import side; it identifies the file, not the media type of the row that failed, and
 * that file always held every type on the export side.
 */
public enum class ImportRowSource {
    MEDIA,
    SESSION,
    EPISODE,
}

/**
 * One row [ImportDataUseCase] declined to import, with a human-readable reason (ROADMAP Task 8
 * Phase B) -- surfaced to the user so a partially-rejected import is never mistaken for a fully
 * successful one ("done" alone is not an acceptable summary per this phase's brief).
 *
 * @property rowNumber 1-based, counting *logical CSV rows/records* including the header as row 1
 *   (the first data row is row 2) -- NOT a physical text-editor line number. A quoted field
 *   spanning multiple physical lines (e.g. a multi-line session note) is still one row, so this
 *   number can be lower than where the offending text visually appears in an editor for a file
 *   containing embedded newlines.
 */
public data class ImportRejection(
    public val source: ImportRowSource,
    public val rowNumber: Int,
    public val reason: String,
)

/**
 * Outcome of one [ImportDataUseCase.execute] call (ROADMAP Task 8 Phase B): counts for every
 * duplicate-policy outcome, for all three files, plus every rejected row's reason. The UI is
 * expected to render this in full -- a silent "import succeeded" that doesn't say what was skipped
 * is exactly the failure mode this phase's brief calls out.
 *
 * ### Why the media counts are not broken down by type
 * The `items*` counts were `books*` until Issue #106 made movies and shows importable, at which
 * point the name stopped being true -- a film landing in a field called `booksImported` is the kind
 * of quiet mislabelling this class exists to prevent. Renaming was preferred over splitting into
 * per-type counts (twelve fields where there are now four) because the number the user is checking
 * is "did my library come back", and `library_export.csv` is one file with one row shape; the
 * per-type detail is visible in the library itself the moment the dialog is dismissed. Episodes are
 * counted separately because they are a *different file*, on the same principle that already gives
 * sessions their own counts.
 */
public data class ImportSummary(
    public val itemsImported: Int,
    public val itemsSkipped: Int,
    public val itemsMerged: Int,
    public val itemsReplaced: Int,
    /**
     * How many of [itemsImported] were books. **Not displayed** -- the summary dialog shows the
     * combined counts above, for the reason this class's KDoc gives.
     *
     * This exists because one *behaviour* genuinely does depend on the media type: the dialog offers
     * to start the bulk cover/author backfill after an import that added something, and that
     * backfill seeds itself from `BookRepository.getAllBooksWithDetails()` -- it is a books-only
     * operation. Gating it on [itemsImported] would offer it after a films-and-shows-only import,
     * where it can only report having nothing to do. That is the shape of bug this field prevents,
     * and it is why the argument against per-type counts (a display argument) does not apply here.
     */
    public val booksImported: Int,
    public val sessionsImported: Int,
    public val sessionsSkipped: Int,
    public val sessionsMerged: Int,
    public val sessionsReplaced: Int,
    /**
     * Episodes read back from `episodes_export.csv` (Issue #106).
     *
     * These exist so a zero is *visible*. Before them the file was exported and never read, and the
     * summary had no field in which that could show up -- an import that dropped every episode a
     * user had ticked off reported the same clean success as one that kept them. A count that reads
     * `0 episodes imported` is the difference between a silent loss and a reported one.
     */
    public val episodesImported: Int,
    public val episodesSkipped: Int,
    public val episodesMerged: Int,
    public val episodesReplaced: Int,
    public val rejections: List<ImportRejection>,
    /**
     * Free-text advisory notes about this import that aren't per-row problems and don't fit the
     * counts above (ROADMAP Task 8 Phase D) -- e.g. a Goodreads import's notice that some columns
     * had nowhere to be stored and were dropped, with instructions for recovering them later (see
     * [com.hub.media.features.portability.goodreads.GoodreadsCsvImporter.NOT_IMPORTED_COLUMNS_NOTICE]),
     * or a book-row that only matched an existing book via the low-confidence title-only tier (see
     * [ImportDataUseCase]'s KDoc, "Duplicate matching precedence" tier 4) -- surfaced so a book
     * added by ISBN (which stores the scanned *edition*'s year) and later re-imported from
     * Goodreads (which stores the *work*'s original year) can still be recognized as the same book
     * without silently trusting a title-only match. Not exclusive to Goodreads imports: [execute]
     * populates this too whenever its own tier-4 match fires. The UI is expected to render every
     * note here, not just the numeric counts and [rejections] -- the same "no silent partial
     * result" rule this phase's brief applies to rejected rows applies here too.
     */
    public val notes: List<String> = emptyList(),
)
