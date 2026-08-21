package com.hub.media.features.portability.csv

import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.features.media.data.MediaWithDetails

/**
 * Produces `library_export.csv` (ROADMAP Task 8 Phase A / vision doc §"Data Portability"): one row
 * per [MediaWithDetails.Book], carrying every [com.hub.media.core.database.entities.MediaItemEntity] and
 * [com.hub.media.core.database.entities.BookDetailsEntity] column plus that item's
 * [ExternalIdentifierEntity] rows. Consolidated and generalized per Issue #67.
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
 * 5. `authors` (schema v5 / CSV `v2`, ROADMAP Task 9 Phase A) --
 *    [com.hub.media.core.database.entities.BookDetailsEntity.authors]'s stored form verbatim,
 *    empty when null or when details are null.
 * 6. `release_year` -- empty when null.
 * 7. `purchase_price` -- empty when null.
 * 8. `created_at` -- ISO-8601 UTC.
 * 9. `cover_image_hash` -- empty when null (no cover downloaded).
 * 10. `isbn` -- empty when null, or when this book has no details row at all.
 * 11. `format` -- by enum name; empty when details are null.
 * 12. `total_pages` -- empty when null.
 * 13. `status` -- by enum name; empty when details are null.
 * 14. `finished_at` -- ISO-8601 UTC; empty when null.
 * 15. `tracking_mode` -- by enum name; empty when details are null.
 * 16. `external_identifiers` -- every [ExternalIdentifierEntity] for this item packed into one
 *     field as `PROVIDER:externalId` pairs joined by `|`.
 */
public object LibraryCsvExporter {
    /** Header row, in column order -- see class KDoc for what each column holds. */
    public val HEADER: List<String> =
        listOf(
            CSV_SCHEMA_VERSION_COLUMN,
            "media_id",
            "type",
            "title",
            "authors",
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
     * The `csv_schema_version=1` header shape (ROADMAP Task 9 Phase A).
     */
    public val HEADER_V1: List<String> =
        listOf(
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
     * Builds the complete CSV text for [mediaItems], including the header row.
     *
     * @param mediaItems Every item to export.
     * @param identifiersByMediaId Every [ExternalIdentifierEntity] in the library.
     */
    public fun export(
        mediaItems: List<MediaWithDetails>,
        identifiersByMediaId: Map<String, List<ExternalIdentifierEntity>>,
    ): String =
        buildString {
            append(CsvUtil.buildLine(HEADER))
            for (media in mediaItems) {
                append(CsvUtil.buildLine(rowFor(media, identifiersByMediaId[media.item.id].orEmpty())))
            }
        }

    private fun rowFor(
        media: MediaWithDetails,
        identifiers: List<ExternalIdentifierEntity>,
    ): List<String> {
        val item = media.item
        val authors =
            when (media) {
                is MediaWithDetails.Book -> media.details?.authors.orEmpty()
                is MediaWithDetails.Movie,
                is MediaWithDetails.TVShow,
                -> ""
            }
        val isbn =
            when (media) {
                is MediaWithDetails.Book -> media.details?.isbn.orEmpty()
                is MediaWithDetails.Movie,
                is MediaWithDetails.TVShow,
                -> ""
            }
        val format =
            when (media) {
                is MediaWithDetails.Book ->
                    media.details
                        ?.format
                        ?.name
                        .orEmpty()
                is MediaWithDetails.Movie,
                is MediaWithDetails.TVShow,
                -> ""
            }
        val totalPages =
            when (media) {
                is MediaWithDetails.Book ->
                    media.details
                        ?.totalPages
                        ?.toString()
                        .orEmpty()
                is MediaWithDetails.Movie,
                is MediaWithDetails.TVShow,
                -> ""
            }
        val status =
            when (media) {
                is MediaWithDetails.Book ->
                    media.details
                        ?.status
                        ?.name
                        .orEmpty()
                is MediaWithDetails.Movie,
                is MediaWithDetails.TVShow,
                -> ""
            }
        val finishedAt =
            when (media) {
                is MediaWithDetails.Book ->
                    media.details
                        ?.finishedAt
                        ?.toString()
                        .orEmpty()
                is MediaWithDetails.Movie,
                is MediaWithDetails.TVShow,
                -> ""
            }
        val trackingMode =
            when (media) {
                is MediaWithDetails.Book ->
                    media.details
                        ?.trackingMode
                        ?.name
                        .orEmpty()
                is MediaWithDetails.Movie,
                is MediaWithDetails.TVShow,
                -> ""
            }

        return listOf(
            CSV_SCHEMA_VERSION.toString(),
            item.id,
            item.type.name,
            item.title,
            authors,
            item.releaseYear?.toString().orEmpty(),
            item.purchasePrice?.toString().orEmpty(),
            item.createdAt.toString(),
            item.coverImageHash.orEmpty(),
            isbn,
            format,
            totalPages,
            status,
            finishedAt,
            trackingMode,
            packIdentifiers(identifiers),
        )
    }

    private fun packIdentifiers(identifiers: List<ExternalIdentifierEntity>): String =
        identifiers.joinToString(separator = "|") { "${it.provider.name}:${it.externalId}" }
}
