package com.hub.media.features.portability.goodreads

import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.core.database.entities.joinAuthors
import com.hub.media.core.util.newId
import com.hub.media.features.books.domain.BookMetadataValidation
import com.hub.media.features.portability.csv.LibraryRowParseResult
import com.hub.media.features.portability.csv.ParsedLibraryRow
import com.hub.media.features.portability.csv.ParsedRowDetails
import com.hub.media.features.portability.csv.RowRejectedException
import com.hub.media.features.portability.csv.parseOptionalInt
import com.hub.media.features.portability.csv.reject
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Maps one `goodreads_library_export.csv` data row into this app's own [ParsedLibraryRow] shape
 * (ROADMAP Task 8 Phase D) -- a *mapping* layer, not a parser: [GoodreadsCsvTableReader] has
 * already tokenized and structurally validated the file (via [com.hub.media.features.portability.csv.CsvReader]).
 * Reuses [LibraryRowParseResult] itself (Goodreads rows and this app's own `library_export.csv`
 * rows resolve through the exact same [com.hub.media.features.portability.domain.ImportDataUseCase]
 * duplicate-matching/write path once parsed into a [ParsedLibraryRow] -- see that class's KDoc for
 * why this phase needed no parallel import path, just this mapping).
 *
 * ### Field-by-field mapping
 * - [GoodreadsColumns.TITLE] -> [ParsedLibraryRow.title] (required; blank/missing rejects the row,
 *   validated through the same [BookMetadataValidation.validateTitle] a manual edit uses).
 * - [GoodreadsColumns.AUTHOR] + [GoodreadsColumns.ADDITIONAL_AUTHORS] ->
 *   [ParsedRowDetails.Book.authors] (schema v5, ROADMAP Task 9 Phase A). Goodreads splits one book's
 *   authorship across three columns; this importer combines exactly two of them: [GoodreadsColumns.AUTHOR]
 *   (the primary author, already in `"First Last"` order) first, followed by every name in
 *   [GoodreadsColumns.ADDITIONAL_AUTHORS] (co-authors, which Goodreads itself comma-separates --
 *   split on `,`, each name trimmed, blank entries dropped), then re-joined with this app's own
 *   [com.hub.media.core.database.entities.BookDetailsEntity.AUTHOR_SEPARATOR] via [joinAuthors] so
 *   the stored form is uniform regardless of source. **Goodreads' `Author l-f` column is
 *   deliberately never used** -- it is the *same* primary author Goodreads already gives in
 *   [GoodreadsColumns.AUTHOR], just re-formatted `"Last, First"`; including it too would duplicate
 *   that one person as a second, differently-punctuated entry rather than add a name this importer
 *   doesn't already have. Both source columns are optional -- a row with neither present, or both
 *   blank, gets `null` [ParsedRowDetails.Book.authors] like any other book with no author on record.
 * - [GoodreadsColumns.NUMBER_OF_PAGES] -> [ParsedRowDetails.Book.totalPages].
 * - [GoodreadsColumns.BINDING] -> [ParsedRowDetails.Book.format]; see [mapBinding]'s KDoc for the exact
 *   mapping table and its fallback.
 * - [GoodreadsColumns.EXCLUSIVE_SHELF] -> [ParsedRowDetails.Book.status]; see [mapExclusiveShelf]'s KDoc
 *   for the mapping and why nothing maps to [ReadingStatus.DNF].
 * - [GoodreadsColumns.DATE_READ] -> [ParsedRowDetails.Book.finishedAt], only when [ReadingStatus.FINISHED]
 *   was derived above (see [mapExclusiveShelf]'s KDoc for why).
 * - [GoodreadsColumns.DATE_ADDED] -> [ParsedLibraryRow.createdAt], falling back to [clock] if blank
 *   or unparseable (every book needs *some* `createdAt`; Goodreads' own "when I added this" is the
 *   best available substitute for "when this device first learned about the book").
 * - [GoodreadsColumns.ISBN13] preferred over [GoodreadsColumns.ISBN] (the 13-digit form is the
 *   modern standard and what Goodreads itself prefers to display) -> [ParsedRowDetails.Book.isbn], after
 *   stripping Goodreads' Excel-armor wrapper (see [stripGoodreadsIsbnArmor]). A resolved ISBN is
 *   also recorded as an [IdentifierProvider.ISBN] external identifier, matching
 *   [com.hub.media.features.books.domain.AddBookByIsbnUseCase]'s convention of always pairing
 *   [ParsedRowDetails.Book.isbn]/`BookDetailsEntity.isbn` with the equivalent
 *   [com.hub.media.core.database.entities.ExternalIdentifierEntity] row.
 * - [ParsedLibraryRow.purchasePrice] and [ParsedLibraryRow.coverImageHash] -- Goodreads carries
 *   neither concept (no price paid, and no downloaded cover image bytes -- see
 *   [com.hub.media.features.portability.domain.ImportDataUseCase]'s KDoc "Cover images are never
 *   restored by CSV import" for why a cover *URL*, even if Goodreads exported one, still wouldn't
 *   be written here) -- both are always `null`.
 * - [ParsedRowDetails.Book.trackingMode] -- [TrackingMode.PAGES] when [totalPages] is known,
 *   [TrackingMode.PERCENT] otherwise, the same derivation
 *   [com.hub.media.features.books.data.BookRepository.addBook] and
 *   [com.hub.media.features.portability.csv.LibraryCsvImporter] both already apply.
 * - [ParsedLibraryRow.mediaId] -- freshly generated ([newId]), since a Goodreads export carries no
 *   MediaTracker primary key. This is deliberate, not an oversight: `media_id` matching in
 *   [com.hub.media.features.portability.domain.ImportDataUseCase] is only ever the *first* of three
 *   duplicate-matching tiers, and a fresh id can never coincide with an existing one, so every
 *   Goodreads row naturally falls through to the ISBN tier (or the title+year tier for an
 *   ISBN-less row) -- exactly the "importing into a different library" case that tier exists for
 *   (see that class's KDoc).
 *
 * ### Release year: `Year Published` vs `Original Publication Year`
 * Goodreads exports both. [ParsedLibraryRow.releaseYear] prefers
 * [GoodreadsColumns.ORIGINAL_PUBLICATION_YEAR], falling back to [GoodreadsColumns.YEAR_PUBLISHED]
 * only when the former is blank/missing. This is the deliberate choice ROADMAP Task 8's Goodreads
 * bullet calls out as needing a decision: `Year Published` is the year of the specific
 * *edition/printing* Goodreads happened to catalog against this book (which can be a much later
 * reprint -- Goodreads' own worked example is a 2026 anniversary printing's `Year Published`
 * masking an original 1926 publication), while `Original Publication Year` is the year the *work*
 * itself first appeared -- the year a reader means when they say "this book is from 1926." A
 * personal library tracker is about the *work* someone read (the same title shelved once,
 * regardless of which specific printing they happened to own), not a bibliographic record of one
 * exact printing, so the work-identity year is preferred whenever Goodreads recorded one. The
 * fallback to `Year Published` exists because `Original Publication Year` is frequently blank on
 * less-cataloged books (self-published works, small-press titles, some non-fiction) -- falling
 * back to the edition year in that case is still strictly better than leaving `releaseYear` `null`
 * for a book Goodreads *did* record a year for.
 *
 * Business-rule bounds (title non-blank, pages `> 0`, release year in
 * [BookMetadataValidation.MIN_RELEASE_YEAR]..
 * [BookMetadataValidation.MAX_RELEASE_YEAR]) are delegated to
 * [BookMetadataValidation] -- the exact same rules every other row source in this app is held to
 * (AGENTS.md §7 "reuse, don't fork"). A Goodreads row for a work older than that lower bound (an
 * ancient text, say) is rejected with a per-row reason like any other out-of-range year, not a
 * problem specific to this importer.
 */
public object GoodreadsCsvImporter {
    /**
     * User-facing notice for the three Goodreads columns this importer cannot store anywhere yet
     * (ROADMAP Task 8's Goodreads bullet, and the Phase D user decision built on top of it):
     * [GoodreadsColumns.MY_RATING] (no rating field exists on any entity), [GoodreadsColumns.BOOKSHELVES]
     * (genres/shelves don't exist until Task 12), and [GoodreadsColumns.READ_COUNT] (read-through
     * history doesn't exist until Task 10). Rather than a staging table or delaying this phase
     * until those tasks land, the decision made was: import everything else *now*, drop these
     * three, and tell the user plainly -- silently discarding `My Rating` is exactly the failure
     * this phase exists to avoid.
     *
     * The recovery path is [com.hub.media.features.portability.domain.DuplicatePolicy.MERGE]: once
     * Tasks 10/12 add homes for these columns, re-running the *same* `goodreads_library_export.csv`
     * through this importer again will match every book it already imported (by ISBN, or by
     * title+year) and backfill the new fields -- MERGE only fills a blank, never overwrites a value
     * already set, so a second import is always safe to run and never loses anything recorded
     * between the two runs. That recovery only works if the user still *has* the export file, which
     * is why this notice says so explicitly rather than just naming the dropped columns.
     */
    public val NOT_IMPORTED_COLUMNS_NOTICE: String =
        "Goodreads columns not imported: '${GoodreadsColumns.MY_RATING}', '${GoodreadsColumns.BOOKSHELVES}', " +
            "and '${GoodreadsColumns.READ_COUNT}' -- MediaTracker doesn't have a place to store ratings, " +
            "shelves/genres, or read-through counts yet. Nothing is lost forever: keep your " +
            "goodreads_library_export.csv file, and importing it again later (once those features " +
            "exist) will automatically fill this information in for the books you just imported -- " +
            "Merge only fills in blanks and never overwrites anything you've already recorded."

    /**
     * @param columnIndex From [GoodreadsCsvTableReader.read]'s [GoodreadsCsvTableResult.Success] --
     *   every recognized header's column position, by name.
     * @param row One data row, already confirmed by [GoodreadsCsvTableReader] to have exactly
     *   [columnIndex]'s number of fields.
     * @param clock Source of "now" for [ParsedLibraryRow.createdAt] when [GoodreadsColumns.DATE_ADDED]
     *   is blank/unparseable. Defaults to [Clock.System]; tests inject a fixed [Clock] for
     *   deterministic assertions.
     */
    public fun parseRow(
        columnIndex: Map<String, Int>,
        row: List<String>,
        clock: Clock = Clock.System,
    ): LibraryRowParseResult =
        try {
            LibraryRowParseResult.Parsed(buildRow(columnIndex, row, clock))
        } catch (e: RowRejectedException) {
            LibraryRowParseResult.Rejected(e.message ?: "Invalid row")
        }

    private fun buildRow(
        columnIndex: Map<String, Int>,
        row: List<String>,
        clock: Clock,
    ): ParsedLibraryRow {
        val title = row.column(columnIndex, GoodreadsColumns.TITLE)
        BookMetadataValidation.validateTitle(title)?.let { reject(it) }

        val primaryAuthor = row.column(columnIndex, GoodreadsColumns.AUTHOR)
        val additionalAuthors =
            row
                .column(columnIndex, GoodreadsColumns.ADDITIONAL_AUTHORS)
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val authors = joinAuthors(listOf(primaryAuthor) + additionalAuthors)

        val originalYear =
            parseOptionalInt(
                row.column(columnIndex, GoodreadsColumns.ORIGINAL_PUBLICATION_YEAR),
                GoodreadsColumns.ORIGINAL_PUBLICATION_YEAR,
            )
        val editionYear =
            parseOptionalInt(
                row.column(columnIndex, GoodreadsColumns.YEAR_PUBLISHED),
                GoodreadsColumns.YEAR_PUBLISHED,
            )
        val releaseYear = originalYear ?: editionYear
        BookMetadataValidation.validateReleaseYear(releaseYear)?.let { reject(it) }

        val isbn13 = stripGoodreadsIsbnArmor(row.column(columnIndex, GoodreadsColumns.ISBN13)).ifBlank { null }
        val isbn10 = stripGoodreadsIsbnArmor(row.column(columnIndex, GoodreadsColumns.ISBN)).ifBlank { null }
        val isbn = isbn13 ?: isbn10

        val format = mapBinding(row.column(columnIndex, GoodreadsColumns.BINDING))

        val totalPages =
            parseOptionalInt(
                row.column(columnIndex, GoodreadsColumns.NUMBER_OF_PAGES),
                GoodreadsColumns.NUMBER_OF_PAGES,
            )
        BookMetadataValidation.validateTotalPages(totalPages)?.let { reject(it) }

        val status = mapExclusiveShelf(row.column(columnIndex, GoodreadsColumns.EXCLUSIVE_SHELF))

        // Date Read only means anything once the shelf itself says FINISHED -- see mapExclusiveShelf's
        // KDoc and BookDetailsEntity.finishedAt's own "when status most recently became FINISHED"
        // invariant; a stray Date Read on a currently-reading/to-read row (a Goodreads re-shelve
        // artifact) must not fabricate a finish date that contradicts the status right next to it.
        val finishedAt =
            if (status == ReadingStatus.FINISHED) {
                parseGoodreadsDate(row.column(columnIndex, GoodreadsColumns.DATE_READ))
            } else {
                null
            }

        val createdAt = parseGoodreadsDate(row.column(columnIndex, GoodreadsColumns.DATE_ADDED)) ?: clock.now()

        val trackingMode = if (totalPages != null) TrackingMode.PAGES else TrackingMode.PERCENT

        // Always a book -- a Goodreads export has no other media type in it, so this is the one
        // ParsedRowDetails variant this importer can ever produce. ParsedLibraryRow.type derives
        // MediaType.BOOK from it (see that property's KDoc), which is why no `type` is passed here.
        return ParsedLibraryRow(
            mediaId = newId(),
            title = title,
            releaseYear = releaseYear,
            purchasePrice = null,
            createdAt = createdAt,
            coverImageHash = null,
            externalIdentifiers = isbn?.let { listOf(IdentifierProvider.ISBN to it) }.orEmpty(),
            details =
                ParsedRowDetails.Book(
                    authors = authors,
                    isbn = isbn,
                    format = format,
                    totalPages = totalPages,
                    status = status,
                    finishedAt = finishedAt,
                    trackingMode = trackingMode,
                ),
        )
    }

    private fun List<String>.column(
        columnIndex: Map<String, Int>,
        name: String,
    ): String = columnIndex[name]?.let { getOrNull(it) }.orEmpty()
}

/**
 * Strips Goodreads' Excel-armor wrapper from an ISBN cell (ROADMAP Task 8's Goodreads bullet's
 * documented gotcha): Goodreads writes an ISBN as the Excel formula `="9780593135204"`, not the
 * bare digits, specifically so Excel displays the cell as literal text instead of reformatting it
 * as a number (which would silently strip a leading zero or switch to scientific notation for a
 * 13-digit value). A parser unaware of this convention reads the literal string `="9780593135204"`
 * -- quotes, equals sign and all -- and every ISBN comparison/validation against it then fails.
 *
 * Also handles the empty-armor case Goodreads writes for a row with no ISBN on file (`=""`),
 * unwrapping it to an empty string -- identical to a genuinely blank cell, not a malformed one.
 *
 * A cell that isn't wrapped this way at all (already-bare digits, or genuinely blank) passes
 * through unchanged (after trimming incidental whitespace).
 */
internal fun stripGoodreadsIsbnArmor(raw: String): String {
    val trimmed = raw.trim()
    return if (trimmed.startsWith("=\"") && trimmed.endsWith("\"") && trimmed.length >= 3) {
        trimmed.substring(2, trimmed.length - 1)
    } else {
        trimmed
    }
}

/**
 * Maps Goodreads' `Binding` column to [BookFormat] (ROADMAP Task 8's Goodreads bullet). Matched
 * case-insensitively against every binding string Goodreads is known to emit; anything not listed
 * here -- including a blank cell, and including a genuinely novel binding string Goodreads adds in
 * the future -- falls back to [BookFormat.PHYSICAL], the exact same "we don't know the precise
 * binding" fallback [com.hub.media.features.books.domain.AddBookByIsbnUseCase]-driven ISBN
 * ingestion already uses for the identical reason (see [BookFormat.PHYSICAL]'s own KDoc): a book
 * whose binding this importer can't confidently classify is still overwhelmingly more likely to be
 * some kind of physical copy than a digital one, so a generic-physical fallback is far less wrong
 * than guessing [BookFormat.EBOOK]/[BookFormat.AUDIOBOOK], and the user can always upgrade a
 * specific book to a more precise value later via the edit-metadata flow, exactly as that KDoc
 * already documents for the ISBN-ingestion case.
 */
internal fun mapBinding(raw: String): BookFormat =
    when (raw.trim().lowercase()) {
        "hardcover", "library binding", "board book", "leather bound" -> BookFormat.HARDCOVER
        "paperback", "mass market paperback", "trade paperback", "spiral-bound", "unbound" -> BookFormat.PAPERBACK
        "kindle edition", "ebook", "e-book", "nook" -> BookFormat.EBOOK
        "audiobook", "audio cd", "audible audio" -> BookFormat.AUDIOBOOK
        else -> BookFormat.PHYSICAL // includes blank -- see KDoc above.
    }

/**
 * Maps Goodreads' `Exclusive Shelf` column to [ReadingStatus] (ROADMAP Task 8's Goodreads bullet).
 * Goodreads' exclusive shelf is always exactly one of three values for a normally-generated export:
 * `read` -> [ReadingStatus.FINISHED], `currently-reading` -> [ReadingStatus.READING], `to-read` ->
 * [ReadingStatus.TO_READ].
 *
 * ### Nothing maps to [ReadingStatus.DNF] -- deliberately
 * Goodreads' built-in exclusive-shelf concept has no "did not finish" state at all; users who track
 * DNFs on Goodreads do it with a *custom, non-exclusive* shelf (which shows up in the `Bookshelves`
 * column this phase already drops -- see [GoodreadsCsvImporter.NOT_IMPORTED_COLUMNS_NOTICE]), not
 * `Exclusive Shelf`. There is therefore no reliable signal in this column to derive [ReadingStatus.DNF]
 * from, and guessing (e.g. treating some other column's presence as an implicit DNF signal) risks
 * silently mislabeling a book the user never abandoned. A book genuinely DNF'd stays importable as
 * whatever its actual exclusive shelf says (almost always `to-read`, sometimes a stale
 * `currently-reading`) and, once `Bookshelves` gets a home (Task 12's genre/tag work), a future
 * enhancement could reconsider a custom "did-not-finish" shelf tag as a DNF signal -- out of scope
 * for this phase.
 *
 * A blank cell (the column is present in the header but empty for a row) or an unrecognized value
 * (a custom/renamed shelf somehow appearing here, or a future Goodreads shelf this importer
 * predates) both fall back to [ReadingStatus.TO_READ] -- the same "honest default when we simply
 * don't know" this app already applies to a pre-status book row (see
 * [com.hub.media.core.database.entities.ReadingStatus]'s KDoc on [com.hub.media.core.database.MIGRATION_2_3])
 * and to [com.hub.media.features.portability.csv.LibraryCsvImporter]'s own blank-status handling --
 * never a per-row rejection, since `Exclusive Shelf` is an optional column under this phase's
 * "tolerate ... missing columns" brief.
 */
internal fun mapExclusiveShelf(raw: String): ReadingStatus =
    when (raw.trim().lowercase()) {
        "read" -> ReadingStatus.FINISHED
        "currently-reading" -> ReadingStatus.READING
        "to-read" -> ReadingStatus.TO_READ
        else -> ReadingStatus.TO_READ
    }

/**
 * Parses a Goodreads `Date Read`/`Date Added` cell (format `yyyy/MM/dd`, e.g. `2023/05/12` -- the
 * fixed format Goodreads exports both date columns in) into an [Instant] at local midnight
 * ([TimeZone.currentSystemDefault], matching [com.hub.media.features.stats.data.StatsRepository]'s
 * existing day-boundary convention elsewhere in this codebase). A blank cell, one that doesn't
 * split into exactly three `/`-separated numeric parts, or one that doesn't form a real calendar
 * date (`2023/02/30`) all return `null` rather than rejecting the row -- both date columns are
 * optional/foreign data this importer doesn't control the format of, and this phase's tolerance
 * brief favors "import what we can, drop what we can't confidently parse" over failing an entire
 * row's import (or the whole file) over one cosmetic date field.
 */
internal fun parseGoodreadsDate(raw: String): Instant? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split("/")
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    return try {
        LocalDate(year, month, day).atStartOfDayIn(TimeZone.currentSystemDefault())
    } catch (e: IllegalArgumentException) {
        null
    }
}
