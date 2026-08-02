package com.hub.media.features.portability.csv

import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.features.books.domain.BookMetadataValidation
import kotlin.time.Instant

/** One successfully parsed `library_export.csv` data row -- mirrors [LibraryCsvExporter]'s column set. */
public data class ParsedLibraryRow(
    public val mediaId: String,
    public val title: String,
    public val releaseYear: Int?,
    public val purchasePrice: Double?,
    public val createdAt: Instant,
    public val coverImageHash: String?,
    public val isbn: String?,
    public val format: BookFormat,
    public val totalPages: Int?,
    public val status: ReadingStatus,
    public val finishedAt: Instant?,
    public val trackingMode: TrackingMode,
    public val externalIdentifiers: List<Pair<IdentifierProvider, String>>,
)

/** Outcome of parsing one `library_export.csv` data row. */
public sealed class LibraryRowParseResult {
    public data class Parsed(public val row: ParsedLibraryRow) : LibraryRowParseResult()
    public data class Rejected(public val reason: String) : LibraryRowParseResult()
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
 * Business-rule bounds (title non-blank, price `>= 0`, pages `> 0`, release year in range) are
 * NOT re-derived here -- they delegate to [BookMetadataValidation], the exact same rules
 * [com.hub.media.features.books.data.BookRepository.updateBookMetadata] enforces on a manual edit,
 * so an imported row can never be held to a looser or stricter standard than a hand-typed one.
 */
public object LibraryCsvImporter {

    public fun parseRow(row: List<String>): LibraryRowParseResult = try {
        LibraryRowParseResult.Parsed(buildRow(row))
    } catch (e: RowRejectedException) {
        LibraryRowParseResult.Rejected(e.message ?: "Invalid row")
    }

    private fun buildRow(row: List<String>): ParsedLibraryRow {
        val mediaId = row[COL_MEDIA_ID].ifBlank { reject("media_id is required") }

        val type = try {
            MediaType.valueOf(row[COL_TYPE])
        } catch (e: IllegalArgumentException) {
            reject("Unknown media type '${row[COL_TYPE]}'")
        }
        if (type != MediaType.BOOK) {
            // Forward-compatible: a future export may carry MOVIE/TV_SHOW rows once Task 13 lands.
            // This app has no domain to import them into yet, so they're reported, not crashed on.
            reject("Media type $type is not yet supported for import (only BOOK is)")
        }

        val title = row[COL_TITLE]
        BookMetadataValidation.validateTitle(title)?.let { reject(it) }

        val releaseYear = parseOptionalInt(row[COL_RELEASE_YEAR], "release_year")
        BookMetadataValidation.validateReleaseYear(releaseYear)?.let { reject(it) }

        val purchasePrice = parseOptionalDouble(row[COL_PURCHASE_PRICE], "purchase_price")
        BookMetadataValidation.validatePurchasePrice(purchasePrice)?.let { reject(it) }

        val createdAt = parseRequiredInstant(row[COL_CREATED_AT], "created_at")
        val coverImageHash = row[COL_COVER_IMAGE_HASH].ifBlank { null }
        val isbn = row[COL_ISBN].ifBlank { null }

        val format = row[COL_FORMAT].let { raw ->
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

        val status = row[COL_STATUS].let { raw ->
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

        val trackingMode = row[COL_TRACKING_MODE].let { raw ->
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

        val externalIdentifiers = unpackIdentifiers(row[COL_EXTERNAL_IDENTIFIERS])

        return ParsedLibraryRow(
            mediaId = mediaId,
            title = title,
            releaseYear = releaseYear,
            purchasePrice = purchasePrice,
            createdAt = createdAt,
            coverImageHash = coverImageHash,
            isbn = isbn,
            format = format,
            totalPages = totalPages,
            status = status,
            finishedAt = finishedAt,
            trackingMode = trackingMode,
            externalIdentifiers = externalIdentifiers,
        )
    }

    // Column indices, matching LibraryCsvExporter.HEADER's order exactly.
    private const val COL_MEDIA_ID = 1
    private const val COL_TYPE = 2
    private const val COL_TITLE = 3
    private const val COL_RELEASE_YEAR = 4
    private const val COL_PURCHASE_PRICE = 5
    private const val COL_CREATED_AT = 6
    private const val COL_COVER_IMAGE_HASH = 7
    private const val COL_ISBN = 8
    private const val COL_FORMAT = 9
    private const val COL_TOTAL_PAGES = 10
    private const val COL_STATUS = 11
    private const val COL_FINISHED_AT = 12
    private const val COL_TRACKING_MODE = 13
    private const val COL_EXTERNAL_IDENTIFIERS = 14
}
