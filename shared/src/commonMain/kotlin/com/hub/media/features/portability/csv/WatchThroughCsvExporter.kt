package com.hub.media.features.portability.csv

import com.hub.media.core.database.entities.WatchThroughEntity
import com.hub.media.features.books.data.toStars

/**
 * `watch_throughs_export.csv` (#231, CSV schema v8): one row per watch-through of a show, so rewatches,
 * each run's abandoned flag and its rating survive an export rather than only a `.sqlite` backup.
 *
 * 1. `csv_schema_version` -- [CSV_SCHEMA_VERSION].
 * 2. `media_id` -- the show.
 * 3. `number` -- 1, 2, 3... per show.
 * 4. `abandoned` -- `true` or `false`.
 * 5. `rating` -- in stars (`4.5`), empty when unrated, as `read_throughs_export.csv` writes it.
 */
public object WatchThroughCsvExporter {
    public val HEADER: List<String> = listOf(CSV_SCHEMA_VERSION_COLUMN, "media_id", "number", "abandoned", "rating")

    public fun export(runs: List<WatchThroughEntity>): String =
        buildString {
            append(CsvUtil.buildLine(HEADER))
            for (run in runs) {
                append(
                    CsvUtil.buildLine(
                        listOf(
                            CSV_SCHEMA_VERSION.toString(),
                            run.mediaId,
                            run.number.toString(),
                            run.abandoned.toString(),
                            run.rating
                                ?.toStars()
                                ?.toString()
                                .orEmpty(),
                        ),
                    ),
                )
            }
        }
}
