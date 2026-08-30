package com.hub.media.features.portability.csv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests [EpisodeCsvImporter] -- the reader `episodes_export.csv` shipped without (Issue #106).
 *
 * Priority follows what actually loses data: `watched_at` above all (the column
 * [EpisodeCsvExporter]'s KDoc calls "the point of the file"), then the cases where a bad row must be
 * rejected rather than quietly coerced, then the `v4` legacy shape.
 *
 * Cross-row concerns -- whether `media_id` names a show that exists, and whether it is a show at all
 * -- are not here, because this object cannot see them; they belong to
 * [com.hub.media.features.portability.domain.ImportDataUseCase] and are tested there.
 */
class EpisodeCsvImporterTest {
    @Test
    fun parseRow_happyPath_parsesEveryColumn() {
        val result = EpisodeCsvImporter.parseRow(validRow())
        assertIs<EpisodeRowParseResult.Parsed>(result)
        val row = result.row
        assertEquals("episode-1", row.episodeId)
        assertEquals("show-1", row.mediaId)
        assertEquals(1, row.seasonNumber)
        assertEquals(3, row.episodeNumber)
        assertEquals("Open Wide, O Earth", row.title)
        assertEquals(Instant.parse("2019-05-20T00:00:00Z"), row.airDate)
        assertEquals(Instant.parse("2024-02-03T04:05:06Z"), row.watchedAt)
        assertEquals(60, row.runtimeMinutes)
        assertEquals("The consequences of the explosion.", row.overview)
        assertEquals("abc123", row.stillImageHash)
        assertEquals(8.7, row.communityRating)
    }

    /**
     * The column the whole file exists to carry. Asserted on its own rather than only inside the
     * happy path, because a regression here is silent: every other column would still round-trip
     * and the import would still report success.
     */
    @Test
    fun parseRow_watchedAt_isRestoredExactly() {
        val result = EpisodeCsvImporter.parseRow(validRow(watchedAt = "2021-12-25T18:30:00Z"))
        assertIs<EpisodeRowParseResult.Parsed>(result)
        assertEquals(Instant.parse("2021-12-25T18:30:00Z"), result.row.watchedAt)
    }

    @Test
    fun parseRow_blankWatchedAt_meansUnwatched() {
        val result = EpisodeCsvImporter.parseRow(validRow(watchedAt = ""))
        assertIs<EpisodeRowParseResult.Parsed>(result)
        assertNull(result.row.watchedAt)
    }

    /**
     * A quick-filled episode has no title by design ([com.hub.media.core.database.entities
     * .EpisodeEntity]'s "rows exist before their titles do"), so a blank cell is a legitimate value
     * and must not be mistaken for a malformed row.
     */
    @Test
    fun parseRow_blankTitle_isAcceptedAsUnknown() {
        val result = EpisodeCsvImporter.parseRow(validRow(title = ""))
        assertIs<EpisodeRowParseResult.Parsed>(result)
        assertNull(result.row.title)
    }

    @Test
    fun parseRow_blankEpisodeId_isRejected() {
        val result = EpisodeCsvImporter.parseRow(validRow(episodeId = ""))
        assertIs<EpisodeRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("episode_id"))
    }

    @Test
    fun parseRow_blankMediaId_isRejected() {
        val result = EpisodeCsvImporter.parseRow(validRow(mediaId = ""))
        assertIs<EpisodeRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("media_id"))
    }

    @Test
    fun parseRow_missingSeasonNumber_isRejected() {
        val result = EpisodeCsvImporter.parseRow(validRow(seasonNumber = ""))
        assertIs<EpisodeRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("season_number"))
    }

    /**
     * Season 0 is specials and is explicitly legal -- see `TVMetadataValidation.validateSeasonNumber`.
     * Rejecting it here would make a specials season impossible to restore from a backup that
     * legitimately contains one.
     */
    @Test
    fun parseRow_seasonZero_isAcceptedAsSpecials() {
        val result = EpisodeCsvImporter.parseRow(validRow(seasonNumber = "0"))
        assertIs<EpisodeRowParseResult.Parsed>(result)
        assertEquals(0, result.row.seasonNumber)
    }

    @Test
    fun parseRow_negativeSeasonNumber_isRejected() {
        val result = EpisodeCsvImporter.parseRow(validRow(seasonNumber = "-1"))
        assertIs<EpisodeRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("Season number"))
    }

    /** Unlike a season, an episode number is 1-based with no specials exception. */
    @Test
    fun parseRow_episodeNumberZero_isRejected() {
        val result = EpisodeCsvImporter.parseRow(validRow(episodeNumber = "0"))
        assertIs<EpisodeRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("Episode number"))
    }

    @Test
    fun parseRow_malformedWatchedAt_isRejected() {
        val result = EpisodeCsvImporter.parseRow(validRow(watchedAt = "last tuesday"))
        assertIs<EpisodeRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("watched_at"))
    }

    @Test
    fun parseRow_zeroRuntime_isRejected() {
        val result = EpisodeCsvImporter.parseRow(validRow(runtimeMinutes = "0"))
        assertIs<EpisodeRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("Runtime"))
    }

    /**
     * `communityRating` is documented as normalised to 0-10, and a file is the one place an
     * un-normalised value can arrive from.
     */
    @Test
    fun parseRow_outOfRangeCommunityRating_isRejected() {
        val result = EpisodeCsvImporter.parseRow(validRow(communityRating = "87"))
        assertIs<EpisodeRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("Community rating"))
    }

    /**
     * `NaN` parses as a valid Double and compares `false` against every bound, so a range check
     * alone would admit it and poison any later average -- the same trap
     * `MediaMetadataValidation.validatePurchasePrice` documents for prices.
     */
    @Test
    fun parseRow_nanCommunityRating_isRejected() {
        val result = EpisodeCsvImporter.parseRow(validRow(communityRating = "NaN"))
        assertIs<EpisodeRowParseResult.Rejected>(result)
        assertTrue(result.reason.contains("community_rating") || result.reason.contains("Community rating"))
    }

    @Test
    fun padLegacyV4Row_padsToTheCurrentWidth() {
        val v4Row = listOf("4", "episode-1", "show-1", "1", "3", "Open Wide, O Earth", "", "")
        assertEquals(EpisodeCsvExporter.HEADER_V4.size, v4Row.size, "the fixture must be a real v4 row")

        val padded = EpisodeCsvImporter.padLegacyV4Row(v4Row)

        assertEquals(EpisodeCsvExporter.HEADER.size, padded.size)
        assertEquals(v4Row, padded.take(v4Row.size), "no existing column may move")
        assertTrue(padded.drop(v4Row.size).all { it.isEmpty() }, "every added column pads blank")
    }

    /** A padded `v4` row must parse, with the four columns it never had reading as unknown. */
    @Test
    fun parseRow_paddedLegacyV4Row_parsesWithTheNewColumnsUnknown() {
        val v4Row =
            listOf("4", "episode-1", "show-1", "1", "3", "Open Wide, O Earth", "", "2024-02-03T04:05:06Z")
        val result = EpisodeCsvImporter.parseRow(EpisodeCsvImporter.padLegacyV4Row(v4Row))

        assertIs<EpisodeRowParseResult.Parsed>(result)
        assertEquals(Instant.parse("2024-02-03T04:05:06Z"), result.row.watchedAt)
        assertNull(result.row.runtimeMinutes)
        assertNull(result.row.overview)
        assertNull(result.row.stillImageHash)
        assertNull(result.row.communityRating)
    }

    private fun validRow(
        episodeId: String = "episode-1",
        mediaId: String = "show-1",
        seasonNumber: String = "1",
        episodeNumber: String = "3",
        title: String = "Open Wide, O Earth",
        airDate: String = "2019-05-20T00:00:00Z",
        watchedAt: String = "2024-02-03T04:05:06Z",
        runtimeMinutes: String = "60",
        overview: String = "The consequences of the explosion.",
        stillImageHash: String = "abc123",
        communityRating: String = "8.7",
    ): List<String> =
        listOf(
            CSV_SCHEMA_VERSION.toString(),
            episodeId,
            mediaId,
            seasonNumber,
            episodeNumber,
            title,
            airDate,
            watchedAt,
            runtimeMinutes,
            overview,
            stillImageHash,
            communityRating,
        )
}
