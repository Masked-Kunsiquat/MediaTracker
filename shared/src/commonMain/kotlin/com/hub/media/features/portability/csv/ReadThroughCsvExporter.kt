package com.hub.media.features.portability.csv

import com.hub.media.core.database.entities.ReadThroughEntity
import com.hub.media.features.books.data.toStars

/**
 * `read_throughs_export.csv` (#230, CSV schema v7): one row per read of a book, so re-reads and each
 * read's rating survive an export rather than only a `.sqlite` backup.
 *
 * 1. `csv_schema_version` -- [CSV_SCHEMA_VERSION].
 * 2. `media_id` -- the book.
 * 3. `number` -- 1, 2, 3… per book.
 * 4. `status` -- a [com.hub.media.core.database.entities.ReadingStatus] name.
 * 5. `started_at` / 6. `finished_at` -- ISO-8601 instants, empty when unknown.
 * 7. `rating` -- in stars (`4.5`), empty when unrated. Stars rather than the stored half-steps, so the
 *    file reads the way the app shows it.
 */
public object ReadThroughCsvExporter {
    public val HEADER: List<String> =
        listOf(CSV_SCHEMA_VERSION_COLUMN, "media_id", "number", "status", "started_at", "finished_at", "rating")

    public fun export(reads: List<ReadThroughEntity>): String =
        buildString {
            append(CsvUtil.buildLine(HEADER))
            for (read in reads) {
                append(
                    CsvUtil.buildLine(
                        listOf(
                            CSV_SCHEMA_VERSION.toString(),
                            read.mediaId,
                            read.number.toString(),
                            read.status.name,
                            read.startedAt?.toString().orEmpty(),
                            read.finishedAt?.toString().orEmpty(),
                            read.rating
                                ?.toStars()
                                ?.toString()
                                .orEmpty(),
                        ),
                    ),
                )
            }
        }
}
