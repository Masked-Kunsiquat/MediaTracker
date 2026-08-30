package com.hub.media.features.portability.csv

import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.features.books.domain.BookMetadataValidation
import com.hub.media.features.movies.data.MovieMetadataValidation
import com.hub.media.features.tv.data.TVMetadataValidation
import kotlin.time.Instant

/**
 * The half of a parsed `library_export.csv` row that belongs to one specific media type (Issue
 * #106) -- exactly one variant per row, chosen by the row's `type` column.
 *
 * Sealed rather than a flat row carrying every type's columns as nullables, for the reason
 * [com.hub.media.core.database.dao.ImportDetails] gives on the write side: a flat shape makes "a
 * film with a book's `tracking_mode`" representable, and something has to then decide, at every
 * read, whether that field meant anything. Here the question cannot be asked. It also keeps the
 * book fields non-null, which is what they were before movies and shows became importable -- a
 * flat shape would have had to make [Book.format]/[Book.status]/[Book.trackingMode] nullable and
 * push that nullability into every existing book consumer.
 */
public sealed class ParsedRowDetails {
    /** @see ParsedLibraryRow */
    public data class Book(
        /**
         * [com.hub.media.core.database.entities.BookDetailsEntity.authors]' stored form, passed
         * through verbatim (schema v5 / CSV `v2`, ROADMAP Task 9 Phase A) -- already `"; "`-joined
         * by whichever writer produced this row, never re-split/re-joined here. `null` for a blank
         * cell, including every row read from a `v1` file via [LibraryCsvExporter.HEADER_V1]'s
         * legacy adapter (see [LibraryCsvImporter.padLegacyV1Row]).
         */
        public val authors: String?,
        public val isbn: String?,
        public val format: BookFormat,
        public val totalPages: Int?,
        public val status: ReadingStatus,
        public val finishedAt: Instant?,
        public val trackingMode: TrackingMode,
    ) : ParsedRowDetails()

    /** @see ParsedLibraryRow */
    public data class Movie(
        public val runtimeMinutes: Int?,
        public val status: WatchStatus,
        public val watchedAt: Instant?,
    ) : ParsedRowDetails()

    /**
     * A show's own row. Its *episodes* are not here -- they are one-to-many under the show and live
     * in `episodes_export.csv`, read by [EpisodeCsvImporter].
     *
     * @see ParsedLibraryRow
     */
    public data class TVShow(
        public val totalSeasons: Int?,
        public val status: WatchStatus,
    ) : ParsedRowDetails()
}

/**
 * One successfully parsed `library_export.csv` data row -- mirrors [LibraryCsvExporter]'s column
 * set, split into the columns every media type shares and the [details] only one type uses.
 */
public data class ParsedLibraryRow(
    public val mediaId: String,
    public val title: String,
    public val releaseYear: Int?,
    public val purchasePrice: Double?,
    public val createdAt: Instant,
    public val coverImageHash: String?,
    public val externalIdentifiers: List<Pair<IdentifierProvider, String>>,
    public val details: ParsedRowDetails,
) {
    /**
     * Derived from [details] rather than stored alongside it, so the two can never disagree about
     * what this row is -- the duplicate-matching keys in
     * [com.hub.media.features.portability.domain.ImportDataUseCase] are all scoped by this value,
     * and a row whose `type` said `MOVIE` while its details said book would match against the wrong
     * half of the library.
     */
    public val type: MediaType
        get() =
            when (details) {
                is ParsedRowDetails.Book -> MediaType.BOOK
                is ParsedRowDetails.Movie -> MediaType.MOVIE
                is ParsedRowDetails.TVShow -> MediaType.TV_SHOW
            }
}

/** Outcome of parsing one `library_export.csv` data row. */
public sealed class LibraryRowParseResult {
    public data class Parsed(
        public val row: ParsedLibraryRow,
    ) : LibraryRowParseResult()

    public data class Rejected(
        public val reason: String,
    ) : LibraryRowParseResult()
}

/**
 * Parses `library_export.csv` data rows (ROADMAP Task 8 Phase B) -- rows already known to be
 * structurally sound (right column count, recognized/supported schema version) by
 * [CsvTableReader], which runs first. What's left here is per-row *semantic* validity: does this
 * row's data actually describe a valid book?
 *
 * A row that fails is reported as [LibraryRowParseResult.Rejected] with a human-readable reason
 * and does **not** abort the rest of the file -- see `ImportDataUseCase`'s KDoc for why per-row
 * semantic problems are skip-with-report rather than whole-file failures, unlike the structural
 * problems [CsvTableReader] already fails closed on.
 *
 * Business-rule bounds (title non-blank, price `>= 0`, pages `> 0`, runtime and seasons `> 0`,
 * release year in range) are NOT re-derived here -- they delegate to
 * [BookMetadataValidation]/[MovieMetadataValidation]/[TVMetadataValidation], the exact same rules
 * [com.hub.media.features.books.data.BookRepository.updateBookMetadata] and its movie/show
 * counterparts enforce on a manual edit, so an imported row can never be held to a looser or
 * stricter standard than a hand-typed one.
 *
 * ### All three media types, since Issue #106
 * This importer used to reject every row whose `type` was not `BOOK`, with the reason "not yet
 * supported for import" -- written when there was genuinely nowhere to put a film or a show. The
 * consequence was that `library_export.csv` had been *writing* movie and show columns since CSV
 * `v3`/`v4` (deliberately -- see [LibraryCsvExporter]'s KDoc) that nothing could read back, so
 * exporting a library and importing it into a fresh install silently returned only its books.
 *
 * A row's `type` column now selects one [ParsedRowDetails] variant instead, and the media-specific
 * columns are read into it. The rejection that remains is for a `type` this app does not know at
 * all, which is still the right answer: an unrecognised media type means the file was written by a
 * newer build, and guessing at which columns it filled is exactly what
 * [CsvTableReader]'s version check exists to prevent.
 */
public object LibraryCsvImporter {
    public fun parseRow(row: List<String>): LibraryRowParseResult =
        try {
            LibraryRowParseResult.Parsed(buildRow(row))
        } catch (e: RowRejectedException) {
            LibraryRowParseResult.Rejected(e.message ?: "Invalid row")
        }

    private fun buildRow(row: List<String>): ParsedLibraryRow {
        // media_id is required non-blank, but deliberately NOT validated as UUID-shaped, even
        // though AGENTS.md §3.1 requires every id *this app generates* to be one. That rule governs
        // generation, not every string this app is ever asked to accept as a primary key: this
        // importer is consuming a file, not minting an id, and the row's own media_id (whatever it
        // is) becomes a fresh insert's primary key whenever it doesn't match an existing/in-file
        // book -- see ImportDataUseCase's KDoc "In-file duplicates"/matching precedence. Two
        // considerations kept this permissive rather than adding a UUID-syntax check:
        // 1. A genuine MediaTracker re-export always carries a real newId()-generated UUID here
        //    (this column round-trips the row's own database primary key unchanged), so a syntax
        //    check would never fire against normal round-trip use -- it would only ever reject a
        //    hand-edited or hand-crafted file.
        // 2. Rejecting that case has a real cost: a user hand-building a library_export.csv-shaped
        //    file to bulk-add books they haven't gone through AddBookByIsbnUseCase for is a
        //    legitimate use of this format (nothing else in this importer requires the file to have
        //    originated from this app's own exporter), and such a user has no obvious reason to know
        //    generated ids must be UUID-shaped -- that's an internal implementation detail, not
        //    something the CSV format's own documentation asks of a file's author. Hard-rejecting a
        //    plausible, human-chosen id like "book-1" over a technicality this format doesn't itself
        //    need enforced would violate AGENTS.md §1's "user data safety over shortcuts" for no
        //    corresponding safety benefit -- nothing downstream (SQLite's TEXT primary key, the
        //    matching tiers above) requires or assumes UUID syntax to function correctly.
        val mediaId = row[COL_MEDIA_ID].ifBlank { reject("media_id is required") }

        val type =
            try {
                MediaType.valueOf(row[COL_TYPE])
            } catch (e: IllegalArgumentException) {
                reject("Unknown media type '${row[COL_TYPE]}'")
            }

        val title = row[COL_TITLE]
        // Validated against the medium's own rules from here on. The title/price checks are
        // media-agnostic and identical whichever object they are reached through (they all forward
        // to MediaMetadataValidation), but the release-year floor is not -- see
        // MediaMetadataValidation's KDoc -- so each branch below must go through its own validator
        // rather than a shared one, or a film released before film existed would import cleanly.
        when (type) {
            MediaType.BOOK -> BookMetadataValidation.validateTitle(title)
            MediaType.MOVIE -> MovieMetadataValidation.validateTitle(title)
            MediaType.TV_SHOW -> TVMetadataValidation.validateTitle(title)
        }?.let { reject(it) }

        val releaseYear = parseOptionalInt(row[COL_RELEASE_YEAR], "release_year")
        when (type) {
            MediaType.BOOK -> BookMetadataValidation.validateReleaseYear(releaseYear)
            MediaType.MOVIE -> MovieMetadataValidation.validateReleaseYear(releaseYear)
            MediaType.TV_SHOW -> TVMetadataValidation.validateReleaseYear(releaseYear)
        }?.let { reject(it) }

        val purchasePrice = parseOptionalDouble(row[COL_PURCHASE_PRICE], "purchase_price")
        when (type) {
            MediaType.BOOK -> BookMetadataValidation.validatePurchasePrice(purchasePrice)
            MediaType.MOVIE -> MovieMetadataValidation.validatePurchasePrice(purchasePrice)
            MediaType.TV_SHOW -> TVMetadataValidation.validatePurchasePrice(purchasePrice)
        }?.let { reject(it) }

        val createdAt = parseRequiredInstant(row[COL_CREATED_AT], "created_at")
        val coverImageHash = row[COL_COVER_IMAGE_HASH].ifBlank { null }
        val externalIdentifiers = unpackIdentifiers(row[COL_EXTERNAL_IDENTIFIERS])

        val details =
            when (type) {
                MediaType.BOOK -> buildBookDetails(row)
                MediaType.MOVIE -> buildMovieDetails(row)
                MediaType.TV_SHOW -> buildTvDetails(row)
            }

        return ParsedLibraryRow(
            mediaId = mediaId,
            title = title,
            releaseYear = releaseYear,
            purchasePrice = purchasePrice,
            createdAt = createdAt,
            coverImageHash = coverImageHash,
            externalIdentifiers = externalIdentifiers,
            details = details,
        )
    }

    private fun buildBookDetails(row: List<String>): ParsedRowDetails.Book {
        val authors = row[COL_AUTHORS].ifBlank { null }
        val isbn = row[COL_ISBN].ifBlank { null }

        val format =
            row[COL_FORMAT].let { raw ->
                if (raw.isBlank()) {
                    BookFormat.PHYSICAL
                } else {
                    try {
                        BookFormat.valueOf(raw)
                    } catch (e: IllegalArgumentException) {
                        reject("Unknown book format '$raw'")
                    }
                }
            }

        val totalPages = parseOptionalInt(row[COL_TOTAL_PAGES], "total_pages")
        BookMetadataValidation.validateTotalPages(totalPages)?.let { reject(it) }

        val status =
            row[COL_STATUS].let { raw ->
                if (raw.isBlank()) {
                    ReadingStatus.TO_READ
                } else {
                    try {
                        ReadingStatus.valueOf(raw)
                    } catch (e: IllegalArgumentException) {
                        reject("Unknown reading status '$raw'")
                    }
                }
            }

        val finishedAt = parseOptionalInstant(row[COL_FINISHED_AT], "finished_at")

        val trackingMode =
            row[COL_TRACKING_MODE].let { raw ->
                if (raw.isBlank()) {
                    if (totalPages != null) TrackingMode.PAGES else TrackingMode.PERCENT
                } else {
                    try {
                        TrackingMode.valueOf(raw)
                    } catch (e: IllegalArgumentException) {
                        reject("Unknown tracking mode '$raw'")
                    }
                }
            }

        return ParsedRowDetails.Book(
            authors = authors,
            isbn = isbn,
            format = format,
            totalPages = totalPages,
            status = status,
            finishedAt = finishedAt,
            trackingMode = trackingMode,
        )
    }

    private fun buildMovieDetails(row: List<String>): ParsedRowDetails.Movie {
        val runtimeMinutes = parseOptionalInt(row[COL_RUNTIME_MINUTES], "runtime_minutes")
        MovieMetadataValidation.validateRuntimeMinutes(runtimeMinutes)?.let { reject(it) }

        return ParsedRowDetails.Movie(
            runtimeMinutes = runtimeMinutes,
            status = parseWatchStatus(row[COL_WATCH_STATUS]),
            watchedAt = parseOptionalInstant(row[COL_WATCHED_AT], "watched_at"),
        )
    }

    private fun buildTvDetails(row: List<String>): ParsedRowDetails.TVShow {
        val totalSeasons = parseOptionalInt(row[COL_TOTAL_SEASONS], "total_seasons")
        TVMetadataValidation.validateTotalSeasons(totalSeasons)?.let { reject(it) }

        // A show's watch_status shares the movie column -- see LibraryCsvExporter's KDoc for why
        // watch state is its own column rather than sharing `status` with books. The exporter writes
        // it only for movies today, so a show row's cell is blank and defaults below; reading it
        // here anyway costs nothing and means a hand-written file that fills it in is honoured.
        return ParsedRowDetails.TVShow(
            totalSeasons = totalSeasons,
            status = parseWatchStatus(row[COL_WATCH_STATUS]),
        )
    }

    /**
     * Shared by the movie and show branches. Blank defaults to [WatchStatus.WATCHLIST], matching
     * both [com.hub.media.core.database.entities.MovieDetailsEntity] and
     * [com.hub.media.core.database.entities.TVDetailsEntity]'s own declared default -- so a row with
     * no watch state imports as "not watched yet" rather than being rejected, exactly as a book row
     * with a blank `status` defaults to [ReadingStatus.TO_READ] above.
     */
    private fun parseWatchStatus(raw: String): WatchStatus =
        if (raw.isBlank()) {
            WatchStatus.WATCHLIST
        } else {
            try {
                WatchStatus.valueOf(raw)
            } catch (e: IllegalArgumentException) {
                reject("Unknown watch status '$raw'")
            }
        }

    // Column indices, matching LibraryCsvExporter.HEADER's order exactly.
    private const val COL_MEDIA_ID = 1
    private const val COL_TYPE = 2
    private const val COL_TITLE = 3
    private const val COL_AUTHORS = 4
    private const val COL_RELEASE_YEAR = 5
    private const val COL_PURCHASE_PRICE = 6
    private const val COL_CREATED_AT = 7
    private const val COL_COVER_IMAGE_HASH = 8
    private const val COL_ISBN = 9
    private const val COL_FORMAT = 10
    private const val COL_TOTAL_PAGES = 11
    private const val COL_STATUS = 12
    private const val COL_FINISHED_AT = 13
    private const val COL_TRACKING_MODE = 14
    private const val COL_EXTERNAL_IDENTIFIERS = 15
    private const val COL_RUNTIME_MINUTES = 16
    private const val COL_WATCH_STATUS = 17
    private const val COL_WATCHED_AT = 18
    private const val COL_TOTAL_SEASONS = 19

    /**
     * Adapts one `csv_schema_version=1` data row (shaped like [LibraryCsvExporter.HEADER_V1], i.e.
     * missing the `authors` column) into the current [LibraryCsvExporter.HEADER] shape, by
     * inserting a blank field at [COL_AUTHORS] -- registered with
     * [com.hub.media.features.portability.csv.CsvTableReader.read]'s `legacyHeaders` parameter
     * (ROADMAP Task 9 Phase A) so a pre-existing `v1` export still imports cleanly rather than being
     * rejected outright by the header check. A blank `authors` cell parses to `null` exactly like a
     * blank cell in a genuine `v2` file would (see [ParsedRowDetails.Book.authors]) -- there is no author
     * to recover from a file that never recorded one.
     *
     * Since `v3` (ROADMAP Task 13 Phase B) and `v4` (ROADMAP Task 13 Phase C) it also pads every
     * trailing column added since: a `v1` file is three format changes behind, and an adapter that
     * only caught up with some of them would produce a row of the wrong width, which
     * `CsvTableReader` rejects as truncated. Delegating to [padLegacyV2Row] rather than duplicating
     * its padding keeps that "pad to current width" logic in exactly one place.
     */
    public fun padLegacyV1Row(row: List<String>): List<String> =
        padLegacyV2Row(row.toMutableList().apply { add(COL_AUTHORS, "") })

    /**
     * Adapts one `csv_schema_version=2` data row (shaped like [LibraryCsvExporter.HEADER_V2]) into
     * the current [LibraryCsvExporter.HEADER] shape by appending every column added since as blanks
     * -- the movie columns (`v3`) and `total_seasons` (`v4`).
     *
     * A pure append, because both `v3` and `v4` added their columns at the end -- see
     * [LibraryCsvExporter.HEADER_V2]/[LibraryCsvExporter.HEADER_V3] for why there. Blank is the
     * honest value: a `v2` file predates movie and show export entirely, and every row in one is a
     * book anyway.
     *
     * Derived from the two header widths rather than a literal count, so a later column addition
     * cannot leave this padding silently one short -- and automatically keeps pace with the current
     * width whenever [LibraryCsvExporter.HEADER] grows again.
     */
    public fun padLegacyV2Row(row: List<String>): List<String> =
        row + List(LibraryCsvExporter.HEADER.size - LibraryCsvExporter.HEADER_V2.size) { "" }

    /**
     * Adapts one `csv_schema_version=3` data row (shaped like [LibraryCsvExporter.HEADER_V3]) into
     * the current [LibraryCsvExporter.HEADER] shape by appending `total_seasons` as blank --
     * registered with [com.hub.media.features.portability.csv.CsvTableReader.read]'s
     * `legacyHeaders` parameter (ROADMAP Task 13 Phase C) so a pre-existing `v3` export still
     * imports cleanly.
     *
     * A pure append, because `v4` added its one column at the end -- see
     * [LibraryCsvExporter.HEADER_V3] for why there. Blank is the honest value: a `v3` file predates
     * show export entirely, and every row in one is a book or a movie anyway.
     *
     * Derived from the two header widths rather than a literal count, so a later column addition
     * cannot leave this padding silently one short.
     */
    public fun padLegacyV3Row(row: List<String>): List<String> =
        row + List(LibraryCsvExporter.HEADER.size - LibraryCsvExporter.HEADER_V3.size) { "" }
}
