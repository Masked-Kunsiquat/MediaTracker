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
 * 9. `runtime_minutes` (CSV `v5`) -- [EpisodeEntity.runtimeMinutes]; empty when null.
 * 10. `overview` -- [EpisodeEntity.overview]; empty when null.
 * 11. `still_image_hash` -- [EpisodeEntity.stillImageHash]; empty when null. The hash only, never
 *     the image bytes -- see [LibraryCsvImporter]'s cover-image note, which applies identically:
 *     this column round-trips for completeness and the importer deliberately never writes it.
 * 12. `community_rating` -- [EpisodeEntity.communityRating]; empty when null.
 *
 * ### Why the four `v5` columns were added before anything populates them
 * Columns 9-12 were added to [EpisodeEntity] in PR #86, sized for the Task 13 Phase D backfill, and
 * this exporter did not pick them up -- so its own promise above ("every column that entity holds")
 * had quietly stopped being true. Nothing is lost by that today, because nothing writes those
 * columns until Phase D. They are added now anyway, for the same reason [LibraryCsvExporter] wrote
 * its movie columns before [LibraryCsvImporter] could read them: this file is the user's backup, and
 * a column only survives a lost device if it was written down at export time. Adding them once
 * Phase D starts filling them would be too late for every export taken in between.
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
            "runtime_minutes",
            "overview",
            "still_image_hash",
            "community_rating",
        )

    /**
     * The `csv_schema_version=4` header shape (ROADMAP Task 13 Phase C), before `v5` appended the
     * four [EpisodeEntity] columns PR #86 added.
     *
     * They went on the **end** so every column a `v4` file already had keeps its index -- which is
     * what lets [EpisodeCsvImporter.padLegacyV4Row] be a pad rather than a reshuffle, exactly as
     * [LibraryCsvExporter.HEADER_V3] does for the library file.
     */
    public val HEADER_V4: List<String> =
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
            episode.runtimeMinutes?.toString().orEmpty(),
            episode.overview.orEmpty(),
            episode.stillImageHash.orEmpty(),
            episode.communityRating?.toString().orEmpty(),
        )
}
