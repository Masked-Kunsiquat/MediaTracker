package com.hub.media.features.portability.csv

import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.features.books.data.BookWithDetails

/**
 * Produces `library_export.csv` (ROADMAP Task 8 Phase A / vision doc §"Data Portability"): one row
 * per [BookWithDetails], carrying every [com.hub.media.core.database.entities.MediaItemEntity] and
 * [com.hub.media.core.database.entities.BookDetailsEntity] column plus that book's
 * [ExternalIdentifierEntity] rows, so the file round-trips everything the schema holds for a book
 * (AGENTS.md §7 "round-trip completeness is the bar" per this phase's task brief).
 *
 * Pure Kotlin/KMP-clean (no Android APIs) -- the app module is responsible for writing the
 * returned [String] to a file (SAF `ACTION_CREATE_DOCUMENT`), this object only ever produces text.
 *
 * ### Column set (in order)
 * 1. [CSV_SCHEMA_VERSION_COLUMN] -- see [CSV_SCHEMA_VERSION]'s KDoc for the version-marker choice.
 * 2. `media_id` -- [com.hub.media.core.database.entities.MediaItemEntity.id].
 * 3. `type` -- [com.hub.media.core.database.entities.MediaItemEntity.type], by enum **name**
 *    (matches how [com.hub.media.core.database.converters.Converters] persists it).
 * 4. `title`
 * 5. `release_year` -- empty when null.
 * 6. `purchase_price` -- empty when null.
 * 7. `created_at` -- ISO-8601 UTC (`kotlin.time.Instant.toString()`), never a locale-dependent
 *    format.
 * 8. `cover_image_hash` -- empty when null (no cover downloaded).
 * 9. `isbn` -- empty when null, or when this book has no [BookWithDetails.details] row at all
 *    (the data-integrity edge case documented on
 *    [com.hub.media.features.books.data.BookRepository.observeBookDetail]).
 * 10. `format` -- by enum name; empty when [BookWithDetails.details] is null.
 * 11. `total_pages` -- empty when null.
 * 12. `status` -- by enum name; empty when [BookWithDetails.details] is null.
 * 13. `finished_at` -- ISO-8601 UTC; empty when null.
 * 14. `tracking_mode` -- by enum name; empty when [BookWithDetails.details] is null.
 * 15. `external_identifiers` -- every [ExternalIdentifierEntity] for this book packed into one
 *     field as `PROVIDER:externalId` pairs joined by `|` (e.g. `ISBN:9780143127796|OPEN_LIBRARY:
 *     OL123M`), empty when there are none. A book has at most a handful of these (one per
 *     provider, per AGENTS.md §3.3's composite-key model), so packing into a single field avoids
 *     either a variable-width column set or a second joined file for what is, in practice, 0-2
 *     rows per book today. The whole packed field still passes through
 *     [CsvUtil.escapeField] like any other, so a provider id that happened to contain a comma or
 *     quote would still be safely quoted -- only the `:`/`|` separators are assumed identifier-safe
 *     (true of every provider id this app currently produces: ISBN digits, Open Library `OL...`
 *     keys, Google Books volume ids).
 */
public object LibraryCsvExporter {

    /** Header row, in column order -- see class KDoc for what each column holds. */
    public val HEADER: List<String> = listOf(
        CSV_SCHEMA_VERSION_COLUMN,
        "media_id",
        "type",
        "title",
        "release_year",
        "purchase_price",
        "created_at",
        "cover_image_hash",
        "isbn",
        "format",
        "total_pages",
        "status",
        "finished_at",
        "tracking_mode",
        "external_identifiers",
    )

    /**
     * Builds the complete CSV text for [books], including the header row.
     *
     * @param books Every book to export, in the order they should appear (callers typically pass
     *   [com.hub.media.features.books.data.BookRepository.observeAllBooksWithDetails]'s
     *   title-ordered snapshot).
     * @param identifiersByMediaId Every [ExternalIdentifierEntity] in the library, grouped by
     *   [ExternalIdentifierEntity.mediaId]. A book with no entry (or an empty list) exports an
     *   empty `external_identifiers` field.
     */
    public fun export(
        books: List<BookWithDetails>,
        identifiersByMediaId: Map<String, List<ExternalIdentifierEntity>>,
    ): String = buildString {
        append(CsvUtil.buildLine(HEADER))
        for (book in books) {
            append(CsvUtil.buildLine(rowFor(book, identifiersByMediaId[book.mediaItem.id].orEmpty())))
        }
    }

    private fun rowFor(book: BookWithDetails, identifiers: List<ExternalIdentifierEntity>): List<String> {
        val mediaItem = book.mediaItem
        val details = book.details
        return listOf(
            CSV_SCHEMA_VERSION.toString(),
            mediaItem.id,
            mediaItem.type.name,
            mediaItem.title,
            mediaItem.releaseYear?.toString().orEmpty(),
            mediaItem.purchasePrice?.toString().orEmpty(),
            mediaItem.createdAt.toString(),
            mediaItem.coverImageHash.orEmpty(),
            details?.isbn.orEmpty(),
            details?.format?.name.orEmpty(),
            details?.totalPages?.toString().orEmpty(),
            details?.status?.name.orEmpty(),
            details?.finishedAt?.toString().orEmpty(),
            details?.trackingMode?.name.orEmpty(),
            packIdentifiers(identifiers),
        )
    }

    private fun packIdentifiers(identifiers: List<ExternalIdentifierEntity>): String =
        identifiers.joinToString(separator = "|") { "${it.provider.name}:${it.externalId}" }
}
