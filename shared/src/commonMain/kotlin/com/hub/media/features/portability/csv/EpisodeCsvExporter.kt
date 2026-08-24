package com.hub.media.features.portability.csv

import com.hub.media.core.database.entities.EpisodeEntity

/**
 * Produces `episodes_export.csv` (ROADMAP Task 13 Phase C): one row per [EpisodeEntity], carrying
 * every column that entity holds.
 *
 * ### Why episodes get their own file rather than a column on `library_export.csv`
 * A show's episodes are one-to-many under the show -- exactly like a book's reading sessions are
 * one-to-many under the book (see [ReadingLogCsvExporter]) -- and so do not fit
 * `library_export.csv`'s one-row-per-item shape. This file is that exporter's TV counterpart:
 * show-level data (`total_seasons`) stays on the show's own row in `library_export.csv`, the way a
 * movie's `runtime_minutes` does, and only the per-episode rows live here.
 *
 * Pure Kotlin/KMP-clean (no Android APIs) -- see [LibraryCsvExporter]'s KDoc for the same note,
 * which applies identically here.
 *
 * ### Column set (in order)
 * 1. [CSV_SCHEMA_VERSION_COLUMN] -- see [CSV_SCHEMA_VERSION]'s KDoc.
 * 2. `episode_id` -- [EpisodeEntity.id].
 * 3. `media_id` -- [EpisodeEntity.mediaId], the FK linking a row here back to the show's row in
 *    `library_export.csv`.
 * 4. `season_number` -- [EpisodeEntity.seasonNumber].
 * 5. `episode_number` -- [EpisodeEntity.episodeNumber].
 * 6. `title` -- empty when null. A quick-filled episode ([EpisodeEntity]'s "rows exist before their
 *    titles do" KDoc) legitimately has none yet.
 * 7. `air_date` -- ISO-8601 UTC; empty when null.
 * 8. `watched_at` -- ISO-8601 UTC; empty when null. **This column is the point of the file**: it is
 *    the watched state that episode-level tracking exists to record -- see [EpisodeEntity]'s KDoc
 *    ("Watched state and its timestamp are one column deliberately"). Everything else in this file
 *    exists so this column has somewhere to live; a backup that dropped it would silently discard
 *    every episode a user had ticked off.
 */
public object EpisodeCsvExporter {
    /** Header row, in column order -- see class KDoc for what each column holds. */
    public val HEADER: List<String> =
        listOf(
            CSV_SCHEMA_VERSION_COLUMN,
            "episode_id",
            "media_id",
            "season_number",
            "episode_number",
            "title",
            "air_date",
            "watched_at",
        )

    /**
     * Builds the complete CSV text for [episodes], including the header row.
     *
     * @param episodes Every episode to export, in the order they should appear (callers typically
     *   pass a full-library snapshot -- row order carries no semantic meaning here, since every row
     *   is fully self-identified by `episode_id`/`media_id`).
     */
    public fun export(episodes: List<EpisodeEntity>): String =
        buildString {
            append(CsvUtil.buildLine(HEADER))
            for (episode in episodes) {
                append(CsvUtil.buildLine(rowFor(episode)))
            }
        }

    private fun rowFor(episode: EpisodeEntity): List<String> =
        listOf(
            CSV_SCHEMA_VERSION.toString(),
            episode.id,
            episode.mediaId,
            episode.seasonNumber.toString(),
            episode.episodeNumber.toString(),
            episode.title.orEmpty(),
            episode.airDate?.toString().orEmpty(),
            episode.watchedAt?.toString().orEmpty(),
        )
}
