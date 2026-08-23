package com.hub.media.features.portability.csv

import com.hub.media.core.database.entities.EpisodeEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests [EpisodeCsvExporter] (ROADMAP Task 13 Phase C). The single highest-priority case here is
 * [export_quickFilledEpisode_nullableFieldsExportAsEmpty] -- a quick-filled episode
 * ([EpisodeEntity]'s "rows exist before their titles do" KDoc) is a perfectly normal row, not an
 * error case, and must round-trip its unknowns as empty fields rather than "null" or "0".
 */
class EpisodeCsvExporterTest {
    private val airDate = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val watchedAt = Instant.fromEpochMilliseconds(1_700_086_400_000)

    @Test
    fun export_emptyLibrary_producesOnlyHeaderRow() {
        val csv = EpisodeCsvExporter.export(emptyList())
        assertEquals(CsvUtil.buildLine(EpisodeCsvExporter.HEADER), csv)
    }

    @Test
    fun export_headerRow_includesSchemaVersionColumnFirst() {
        val csv = EpisodeCsvExporter.export(emptyList())
        val header = csv.substringBefore(CsvUtil.LINE_ENDING)
        assertEquals(CSV_SCHEMA_VERSION_COLUMN, header.split(",").first())
    }

    @Test
    fun export_fullyPopulatedEpisode_includesEveryFieldInOrder() {
        val episode =
            EpisodeEntity(
                id = "episode-1",
                mediaId = "show-1",
                seasonNumber = 2,
                episodeNumber = 5,
                title = "The One Where Everything Happens",
                airDate = airDate,
                watchedAt = watchedAt,
            )

        val csv = EpisodeCsvExporter.export(listOf(episode))
        val fields = csv.split(CsvUtil.LINE_ENDING)[1].split(",")

        assertEquals(CSV_SCHEMA_VERSION.toString(), fields[0])
        assertEquals("episode-1", fields[1])
        assertEquals("show-1", fields[2])
        assertEquals("2", fields[3])
        assertEquals("5", fields[4])
        assertEquals("The One Where Everything Happens", fields[5])
        assertEquals(airDate.toString(), fields[6])
        assertEquals(watchedAt.toString(), fields[7])
    }

    @Test
    fun export_quickFilledEpisode_nullableFieldsExportAsEmpty() {
        // Quick-fill (EpisodeEntity's "rows exist before their titles do" KDoc): a normal row with
        // unknown title/airDate and, before it's watched, no watchedAt either -- none of these
        // should ever export as the literal "null" or "0".
        val episode =
            EpisodeEntity(
                id = "episode-2",
                mediaId = "show-1",
                seasonNumber = 1,
                episodeNumber = 1,
                title = null,
                airDate = null,
                watchedAt = null,
            )

        val csv = EpisodeCsvExporter.export(listOf(episode))
        val fields = csv.split(CsvUtil.LINE_ENDING)[1].split(",")

        assertEquals("", fields[5], "title should be empty, not 'null'")
        assertEquals("", fields[6], "air_date should be empty, not 'null'")
        assertEquals("", fields[7], "watched_at should be empty, not 'null'")
        assertTrue(!csv.contains("null"), "no field should ever literally contain the text 'null': $csv")
    }

    @Test
    fun export_titleContainingCommaAndQuote_isEscapedAndStillParsesAsOneField() {
        val episode =
            EpisodeEntity(
                id = "episode-3",
                mediaId = "show-1",
                seasonNumber = 1,
                episodeNumber = 2,
                title = "The \"Pilot\", Part One",
                airDate = null,
                watchedAt = null,
            )

        val csv = EpisodeCsvExporter.export(listOf(episode))
        val dataLine = csv.split(CsvUtil.LINE_ENDING)[1]

        assertTrue(dataLine.contains("\"The \"\"Pilot\"\", Part One\""))
        // Sanity: still exactly one data row despite the embedded comma inside the quoted field.
        assertEquals(3, csv.split(CsvUtil.LINE_ENDING).size) // [header, dataRow, ""]
    }

    @Test
    fun export_multipleEpisodes_producesOneRowPerEpisode() {
        val episodes =
            listOf(
                EpisodeEntity("e1", "show-1", 1, 1, null, null, null),
                EpisodeEntity("e2", "show-1", 1, 2, "Titled", airDate, watchedAt),
                EpisodeEntity("e3", "show-2", 1, 1, null, null, null),
            )
        val csv = EpisodeCsvExporter.export(episodes)
        val lines = csv.trimEnd().split(CsvUtil.LINE_ENDING)
        assertEquals(4, lines.size) // header + 3 episodes
    }
}
