package com.hub.media.features.portability.csv

import com.hub.media.core.database.entities.WatchLogEntity
import com.hub.media.core.database.entities.WatchThroughEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * `watch_throughs_export.csv` and `watch_logs_export.csv`'s two new columns (#231, CSV 8): a show's
 * runs, a film viewing's rating, and an episode viewing's run number, written and read back.
 */
class WatchThroughCsvTest {
    private fun runRow(line: String) = WatchThroughCsvImporter.parseRow(line.split(","))

    private fun logRow(line: String) = WatchLogCsvImporter.parseRow(line.split(","))

    @Test
    fun aRun_roundTrips_abandonedAndRated() {
        val run = WatchThroughEntity(id = "r2", mediaId = "s", number = 2, abandoned = true, rating = 9)
        val lines = WatchThroughCsvExporter.export(listOf(run)).trim().lines()

        assertEquals(WatchThroughCsvExporter.HEADER.joinToString(","), lines[0])
        assertEquals("4.5", lines[1].split(",").last(), "a rating is written in stars")
        val parsed =
            WatchThroughCsvImporter.parseRow(
                (CsvReader.parse(lines[1]) as CsvParseResult.Success).rows.single(),
            )
        assertIs<WatchThroughRowParseResult.Parsed>(parsed)
        assertEquals(ParsedWatchThrough("s", 2, abandoned = true, rating = 9), parsed.run)
    }

    @Test
    fun offGridRatings_areRejected_withAReason() {
        for (bad in listOf("4.3", "0", "6", "abc", "NaN", "Infinity")) {
            val result = runRow("$CSV_SCHEMA_VERSION,s,1,false,$bad")
            assertIs<WatchThroughRowParseResult.Rejected>(result, "rating '$bad'")
            assertTrue(result.reason.contains("rating"), result.reason)
        }
    }

    @Test
    fun aBadNumber_aBadFlag_andABlankShow_areRejected() {
        assertIs<WatchThroughRowParseResult.Rejected>(runRow("$CSV_SCHEMA_VERSION,s,0,false,"))
        assertIs<WatchThroughRowParseResult.Rejected>(runRow("$CSV_SCHEMA_VERSION,s,,false,"))
        assertIs<WatchThroughRowParseResult.Rejected>(runRow("$CSV_SCHEMA_VERSION,s,1,maybe,"))
        assertIs<WatchThroughRowParseResult.Rejected>(runRow("$CSV_SCHEMA_VERSION,,1,false,"))
        val unrated = runRow("$CSV_SCHEMA_VERSION,s,1,FALSE,")
        assertIs<WatchThroughRowParseResult.Parsed>(unrated, "the flag reads case-insensitively")
        assertNull(unrated.run.rating)
    }

    @Test
    fun aFilmViewing_roundTripsItsRating_andAnEpisodeItsRunNumber() {
        val at = Instant.parse("2025-03-01T12:00:00Z")
        val film = WatchLogEntity(id = "f", mediaId = "m", watchedAt = at, rating = 8)
        val episode = WatchLogEntity(id = "e", mediaId = "s", episodeId = "ep", watchedAt = at, watchThroughId = "r2")
        val lines = WatchLogCsvExporter.export(listOf(film, episode), runNumberById = mapOf("r2" to 2)).trim().lines()

        assertEquals(WatchLogCsvExporter.HEADER.joinToString(","), lines[0])
        val parsedFilm = assertIs<WatchLogRowParseResult.Parsed>(logRow(lines[1])).row
        assertEquals(8, parsedFilm.rating)
        assertNull(parsedFilm.runNumber)
        val parsedEpisode = assertIs<WatchLogRowParseResult.Parsed>(logRow(lines[2])).row
        assertEquals(2, parsedEpisode.runNumber)
        assertNull(parsedEpisode.rating)
    }

    @Test
    fun anEpisodeViewingWithARating_andAFilmViewingWithARun_areRejected() {
        val at = "2025-03-01T12:00:00Z"
        assertIs<WatchLogRowParseResult.Rejected>(logRow("$CSV_SCHEMA_VERSION,l,s,ep,$at,,1,4"))
        assertIs<WatchLogRowParseResult.Rejected>(logRow("$CSV_SCHEMA_VERSION,l,m,,$at,,2,"))
        assertIs<WatchLogRowParseResult.Rejected>(logRow("$CSV_SCHEMA_VERSION,l,m,,$at,,,4.3"))
    }

    @Test
    fun aV7Row_paddedToV8_hasNoRunAndNoRating() {
        val v7 = "7,l,s,ep,2025-03-01T12:00:00Z,".split(",")
        assertEquals(WatchLogCsvExporter.HEADER_V7.size, v7.size)
        val parsed =
            assertIs<WatchLogRowParseResult.Parsed>(
                WatchLogCsvImporter.parseRow(WatchLogCsvImporter.padLegacyV7Row(v7)),
            )
        assertNull(parsed.row.runNumber)
        assertNull(parsed.row.rating)
    }
}
