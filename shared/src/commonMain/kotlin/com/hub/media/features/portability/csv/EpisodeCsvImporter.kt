package com.hub.media.features.portability.csv

import com.hub.media.features.tv.data.TVMetadataValidation
import kotlin.time.Instant

/** One successfully parsed `episodes_export.csv` data row -- mirrors [EpisodeCsvExporter]'s column set. */
public data class ParsedEpisodeRow(
    public val episodeId: String,
    /**
     * The show this episode belongs to, as the file recorded it. Resolved against the set of known
     * shows by [com.hub.media.features.portability.domain.ImportDataUseCase], which may map it onto
     * a different id when the show's own library row matched an existing show under one -- see that
     * class's KDoc, "Ordering and orphan rows".
     */
    public val mediaId: String,
    public val seasonNumber: Int,
    public val episodeNumber: Int,
    public val title: String?,
    public val airDate: Instant?,
    public val watchedAt: Instant?,
    public val runtimeMinutes: Int?,
    public val overview: String?,
    /**
     * Parsed for completeness and deliberately never written -- see
     * [com.hub.media.features.portability.domain.ImportDataUseCase]'s KDoc, "Images are never
     * restored by CSV import". The CSV carries the hash naming an image file, never the bytes, so
     * writing a foreign device's hash would point at a file that does not exist here.
     */
    public val stillImageHash: String?,
    public val communityRating: Double?,
)

/** Outcome of parsing one `episodes_export.csv` data row. */
public sealed class EpisodeRowParseResult {
    public data class Parsed(
        public val row: ParsedEpisodeRow,
    ) : EpisodeRowParseResult()

    public data class Rejected(
        public val reason: String,
    ) : EpisodeRowParseResult()
}

/**
 * Parses `episodes_export.csv` data rows (Issue #106) -- the reader
 * [EpisodeCsvExporter] shipped without, which is why a library exported with its shows and imported
 * into a fresh install came back with every show present and every episode gone, reporting nothing.
 *
 * Structural validation ([CsvTableReader]) has already run, so what is left here is per-row
 * semantic validity, reported as [EpisodeRowParseResult.Rejected] with a human-readable reason and
 * never aborting the rest of the file -- the same skip-with-report contract
 * [LibraryCsvImporter]/[ReadingLogCsvImporter] follow, and for the reason
 * [com.hub.media.features.portability.domain.ImportDataUseCase]'s KDoc gives.
 *
 * Business-rule bounds delegate to [TVMetadataValidation] rather than being re-derived, exactly as
 * [LibraryCsvImporter] delegates its own -- so an imported episode is held to the same standard as
 * one this app created itself.
 *
 * ### What this deliberately does not check
 * That the row's `media_id` names a show that exists, and that the show is a `TV_SHOW` rather than
 * a book, are both **cross-row** questions -- they need the library file and the current database,
 * neither of which this object can see. They are settled one layer up, in
 * [com.hub.media.features.portability.domain.ImportDataUseCase], which owns the same question for
 * `reading_logs_export.csv` and answers it the same way.
 */
public object EpisodeCsvImporter {
    public fun parseRow(row: List<String>): EpisodeRowParseResult =
        try {
            EpisodeRowParseResult.Parsed(buildRow(row))
        } catch (e: RowRejectedException) {
            EpisodeRowParseResult.Rejected(e.message ?: "Invalid row")
        }

    private fun buildRow(row: List<String>): ParsedEpisodeRow {
        // Neither id is checked for UUID syntax, for the reasons LibraryCsvImporter's media_id
        // comment sets out at length -- this is consuming a file, not minting an id.
        val episodeId = row[COL_EPISODE_ID].ifBlank { reject("episode_id is required") }
        val mediaId = row[COL_MEDIA_ID].ifBlank { reject("media_id is required") }

        val seasonNumber =
            parseOptionalInt(row[COL_SEASON_NUMBER], "season_number")
                ?: reject("season_number is required")
        TVMetadataValidation.validateSeasonNumber(seasonNumber)?.let { reject(it) }

        val episodeNumber =
            parseOptionalInt(row[COL_EPISODE_NUMBER], "episode_number")
                ?: reject("episode_number is required")
        TVMetadataValidation.validateEpisodeNumber(episodeNumber)?.let { reject(it) }

        val runtimeMinutes = parseOptionalInt(row[COL_RUNTIME_MINUTES], "runtime_minutes")
        TVMetadataValidation.validateEpisodeRuntimeMinutes(runtimeMinutes)?.let { reject(it) }

        val communityRating = parseOptionalDouble(row[COL_COMMUNITY_RATING], "community_rating")
        TVMetadataValidation.validateCommunityRating(communityRating)?.let { reject(it) }

        return ParsedEpisodeRow(
            episodeId = episodeId,
            mediaId = mediaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            // Blank is a legitimate value, not a missing one: a quick-filled episode has no title
            // yet by design (see EpisodeEntity's "rows exist before their titles do").
            title = row[COL_TITLE].ifBlank { null },
            airDate = parseOptionalInstant(row[COL_AIR_DATE], "air_date"),
            watchedAt = parseOptionalInstant(row[COL_WATCHED_AT], "watched_at"),
            runtimeMinutes = runtimeMinutes,
            overview = row[COL_OVERVIEW].ifBlank { null },
            stillImageHash = row[COL_STILL_IMAGE_HASH].ifBlank { null },
            communityRating = communityRating,
        )
    }

    // Column indices, matching EpisodeCsvExporter.HEADER's order exactly.
    private const val COL_EPISODE_ID = 1
    private const val COL_MEDIA_ID = 2
    private const val COL_SEASON_NUMBER = 3
    private const val COL_EPISODE_NUMBER = 4
    private const val COL_TITLE = 5
    private const val COL_AIR_DATE = 6
    private const val COL_WATCHED_AT = 7
    private const val COL_RUNTIME_MINUTES = 8
    private const val COL_OVERVIEW = 9
    private const val COL_STILL_IMAGE_HASH = 10
    private const val COL_COMMUNITY_RATING = 11

    /**
     * Adapts one `csv_schema_version=4` data row (shaped like [EpisodeCsvExporter.HEADER_V4]) into
     * the current [EpisodeCsvExporter.HEADER] shape by appending every column `v5` added as blanks
     * -- registered with [CsvTableReader.read]'s `legacyHeaders` parameter so a pre-existing `v4`
     * episodes export still imports cleanly rather than being rejected as the wrong width.
     *
     * A pure append, because `v5` added its four columns at the end -- see
     * [EpisodeCsvExporter.HEADER_V4] for why there. Blank is the honest value: a `v4` file predates
     * those columns entirely, and nothing had written them even when it was exported.
     *
     * Derived from the two header widths rather than a literal count, so a later column addition
     * cannot leave this padding silently one short -- the same construction
     * [LibraryCsvImporter.padLegacyV2Row] uses.
     */
    public fun padLegacyV4Row(row: List<String>): List<String> =
        row + List(EpisodeCsvExporter.HEADER.size - EpisodeCsvExporter.HEADER_V4.size) { "" }
}
