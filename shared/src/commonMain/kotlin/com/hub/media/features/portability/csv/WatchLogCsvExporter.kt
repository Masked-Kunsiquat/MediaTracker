package com.hub.media.features.portability.csv

import com.hub.media.core.database.entities.WatchLogEntity

/**
 * `watch_logs_export.csv` (#90, CSV schema v6): one row per viewing. Without this file a re-watch
 * history would survive only a `.sqlite` backup, never an export -- the data-safety gap AGENTS.md
 * §1 rules out.
 *
 * `episode_id` is blank for a film viewing; this app writes none yet (films are #90's follow-up),
 * but the column is there so the format does not change when it does.
 */
public object WatchLogCsvExporter {
    /** Header row, in column order. */
    public val HEADER: List<String> =
        listOf(
            CSV_SCHEMA_VERSION_COLUMN,
            "log_id",
            "media_id",
            "episode_id",
            "watched_at",
            "duration_seconds",
        )

    public fun export(viewings: List<WatchLogEntity>): String =
        buildString {
            append(CsvUtil.buildLine(HEADER))
            for (log in viewings) {
                append(
                    CsvUtil.buildLine(
                        listOf(
                            CSV_SCHEMA_VERSION.toString(),
                            log.id,
                            log.mediaId,
                            log.episodeId.orEmpty(),
                            log.watchedAt.toString(),
                            log.durationSeconds?.toString().orEmpty(),
                        ),
                    ),
                )
            }
        }
}
