package com.hub.media.features.portability.domain

/** Which imported file a rejected row came from. */
public enum class ImportRowSource {
    BOOK,
    SESSION,
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
 * duplicate-policy outcome, for both files, plus every rejected row's reason. The UI is expected
 * to render this in full -- a silent "import succeeded" that doesn't say what was skipped is
 * exactly the failure mode this phase's brief calls out.
 */
public data class ImportSummary(
    public val booksImported: Int,
    public val booksSkipped: Int,
    public val booksMerged: Int,
    public val booksReplaced: Int,
    public val sessionsImported: Int,
    public val sessionsSkipped: Int,
    public val sessionsMerged: Int,
    public val sessionsReplaced: Int,
    public val rejections: List<ImportRejection>,
    /**
     * Free-text advisory notes about this import that aren't per-row problems and don't fit the
     * counts above (ROADMAP Task 8 Phase D) -- e.g. a Goodreads import's notice that some columns
     * had nowhere to be stored and were dropped, with instructions for recovering them later (see
     * [com.hub.media.features.portability.goodreads.GoodreadsCsvImporter.NOT_IMPORTED_COLUMNS_NOTICE]).
     * Always empty for [ImportDataUseCase.execute] (this app's own CSV format round-trips every
     * column it exports, so there is never anything to caveat there). The UI is expected to render
     * every note here, not just the numeric counts and [rejections] -- the same "no silent partial
     * result" rule this phase's brief applies to rejected rows applies here too.
     */
    public val notes: List<String> = emptyList(),
)
