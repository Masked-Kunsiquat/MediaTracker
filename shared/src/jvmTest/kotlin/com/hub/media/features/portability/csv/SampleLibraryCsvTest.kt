package com.hub.media.features.portability.csv

import com.hub.media.core.database.entities.MediaType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies that the development fixture at `docs/sample-data/library_sample.csv` still imports
 * through the real parser.
 *
 * ### Why this is a test and not a promise in a README
 * That file exists so UI work can be looked at against realistic content instead of an empty
 * screen (the debug build installs under its own `applicationId` and therefore starts empty). Its
 * README claims a fixture that no longer imports is worse than none — which is only true if
 * something checks. The CSV schema has already been through one version bump; the next would
 * silently invalidate this file, and it would surface as a confusing import failure during manual
 * testing rather than here.
 *
 * `jvmTest` rather than `commonTest` because it reads a real file from the repository, which needs
 * filesystem access the common source set cannot assume.
 */
class SampleLibraryCsvTest {
    /**
     * Walks up from the working directory to find the repository root, since Gradle may invoke this
     * from either the root or the `shared` module directory.
     */
    private fun sampleFile(name: String): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = File(dir, "docs/sample-data/$name")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Could not locate docs/sample-data/$name from ${System.getProperty("user.dir")}")
    }

    private fun parsedRows(): List<ParsedLibraryRow> {
        val table = CsvTableReader.read(sampleFile("library_sample.csv").readText(), LibraryCsvExporter.HEADER)
        val rows = assertIs<CsvTableResult.Success>(table, "the fixture must read as a valid CSV table").rows
        assertTrue(rows.isNotEmpty(), "the fixture must actually contain books")
        return rows.mapIndexed { index, row ->
            val result = LibraryCsvImporter.parseRow(row)
            assertIs<LibraryRowParseResult.Parsed>(
                result,
                "row ${index + 2} of the sample CSV no longer parses: $result",
            ).row
        }
    }

    @Test
    fun sampleCsv_everyRowParsesThroughTheRealImporter() {
        assertEquals(19, parsedRows().size, "every row in the fixture must import")
    }

    private fun parsedEpisodes(): List<ParsedEpisodeRow> {
        val table =
            CsvTableReader.read(sampleFile("episodes_sample.csv").readText(), EpisodeCsvExporter.HEADER)
        val rows =
            assertIs<CsvTableResult.Success>(table, "the episode fixture must read as a valid CSV table").rows
        return rows.mapIndexed { index, row ->
            val result = EpisodeCsvImporter.parseRow(row)
            assertIs<EpisodeRowParseResult.Parsed>(
                result,
                "row ${index + 2} of the episode fixture no longer parses: $result",
            ).row
        }
    }

    @Test
    fun sampleEpisodes_everyRowParsesThroughTheRealImporter() {
        assertTrue(parsedEpisodes().isNotEmpty(), "the fixture must contain episodes")
    }

    /**
     * An episode whose `media_id` matches no show is skipped and reported rather than failing the
     * import, so this would otherwise degrade silently -- exactly as
     * [sampleReadingLogs_referenceOnlyBooksThatExistInTheLibraryFixture] guards the session fixture.
     */
    @Test
    fun sampleEpisodes_referenceOnlyShowsThatExistInTheLibraryFixture() {
        val episodes = parsedEpisodes()
        assertTrue(episodes.isNotEmpty(), "no episodes means this would pass vacuously")

        val showIds =
            parsedRows()
                .filter { it.type == MediaType.TV_SHOW }
                .map { it.mediaId }
                .toSet()
        val orphans = episodes.map { it.mediaId }.filterNot { it in showIds }

        assertTrue(orphans.isEmpty(), "episodes reference unknown shows: $orphans")
    }

    @Test
    fun sampleEpisodes_stillCoverTheirEdgeCases() {
        val episodes = parsedEpisodes()

        assertTrue(
            episodes.any { it.seasonNumber == 0 },
            "a specials season is what exercises #88's rule that specials count toward completion",
        )
        assertTrue(
            episodes.any { it.title == null },
            "a quick-filled episode has no title yet -- the render path that must not show a blank row",
        )
        assertTrue(
            episodes.any { it.title != null },
            "a backfilled episode has one, so both render paths are covered",
        )
        assertTrue(
            episodes.any { it.watchedAt == null } && episodes.any { it.watchedAt != null },
            "a partially watched show is the only way progress can be seen to be partial",
        )
        assertTrue(
            episodes
                .groupBy { it.mediaId to it.seasonNumber }
                .values
                .any { season ->
                    season.any { it.watchedAt != null } && season.any { it.watchedAt == null }
                },
            "a season that is only partly ticked renders differently from an all- or none-ticked " +
                "one, and is the case the show screen's checklist is most likely to get wrong",
        )
        assertTrue(
            episodes.all { it.stillImageHash.isNullOrEmpty() },
            "still hashes must stay blank: a hash here names a file no device has, so every " +
                "episode would render a broken still -- the same rule cover_image_hash follows",
        )
    }

    /**
     * The fixture must contain a show that is fully watched *except* for specials. That is the exact
     * shape #88 decided on -- specials count, so such a show reads In progress rather than Finished
     * -- and it cannot be checked by looking at any one row.
     */
    @Test
    fun sampleEpisodes_containAShowWatchedExceptForItsSpecials() {
        val bySpecialness =
            parsedEpisodes()
                .groupBy { it.mediaId }
                .mapValues { (_, eps) -> eps.partition { it.seasonNumber == 0 } }

        val match =
            bySpecialness.entries.any { (_, split) ->
                val (specials, regular) = split
                // `all`, not `any`. An earlier version of this asserted only that *some* regular
                // episode was watched, which a show that is simply half-finished satisfies -- so it
                // passed while the fixture did not actually contain the case, and the show it
                // pointed at read In progress for an ordinary reason. The whole point is a show
                // that would be Finished if specials did not count.
                regular.isNotEmpty() &&
                    regular.all { it.watchedAt != null } &&
                    specials.any { it.watchedAt == null }
            }

        assertTrue(
            match,
            "no show has every regular episode watched and a special still unwatched -- without " +
                "one, nothing in the fixture demonstrates that specials affect completion",
        )
    }

    private fun parsedSessions(): List<ParsedSessionRow> {
        val table =
            CsvTableReader.read(
                sampleFile("reading_logs_sample.csv").readText(),
                ReadingLogCsvExporter.HEADER,
            )
        val rows =
            assertIs<CsvTableResult.Success>(
                table,
                "the reading-log fixture must read as a valid CSV table -- note a wrong column count " +
                    "is a structural failure that rejects the WHOLE file, not just the offending row",
            ).rows
        return rows.mapIndexed { index, row ->
            val result = ReadingLogCsvImporter.parseRow(row)
            assertIs<SessionRowParseResult.Parsed>(
                result,
                "row ${index + 2} of the reading-log fixture no longer parses: $result",
            ).row
        }
    }

    @Test
    fun sampleReadingLogs_everyRowParsesThroughTheRealImporter() {
        assertTrue(parsedSessions().isNotEmpty(), "the fixture must contain reading sessions")
    }

    @Test
    fun sampleReadingLogs_referenceOnlyBooksThatExistInTheLibraryFixture() {
        // A session whose media_id matches no book is skipped and reported as an orphan rather than
        // failing the import, so this would otherwise degrade silently: the data would look fine
        // and the sessions would simply never appear.
        val sessions = parsedSessions()
        assertTrue(sessions.isNotEmpty(), "no sessions means this would pass vacuously")

        val knownIds = parsedRows().map { it.mediaId }.toSet()
        val orphans = sessions.map { it.mediaId }.filterNot { it in knownIds }

        assertTrue(orphans.isEmpty(), "reading sessions reference unknown books: $orphans")
    }

    @Test
    fun sampleReadingLogs_stillCoverTheirEdgeCases() {
        val sessions = parsedSessions()

        assertTrue(
            sessions.any { it.durationSeconds == null },
            "a session with unknown duration is the case that must stay empty rather than become 0",
        )
        assertTrue(
            sessions.any { !it.notes.isNullOrEmpty() && it.notes!!.contains(",") },
            "a note containing a comma is what catches unquoted CSV fields",
        )
        assertTrue(
            sessions.any { it.deltaPages == null },
            "a percent-tracked session has no page delta",
        )
    }

    @Test
    fun sampleCsv_stillCoversTheCasesItClaimsTo() {
        // The fixture's value is its edge cases, so a well-meaning tidy-up that removes them should
        // fail here rather than quietly leave manual testing weaker than it looks.
        val parsed = parsedRows()

        val books = parsed.filter { it.type == MediaType.BOOK }
        assertEquals(
            4,
            books.map { it.book.status }.toSet().size,
            "every reading status must be represented, or the filter chips cannot all be exercised",
        )
        assertTrue(
            books.any { it.book.authors?.contains(";") == true },
            "a multi-author book is what proves author search matches inside the joined string",
        )
        assertTrue(
            books.any { it.book.isbn.isNullOrEmpty() },
            "a book with no ISBN is the case the cover backfill must skip rather than retry",
        )
        assertTrue(
            parsed.any { it.title.contains(",") },
            "a title containing a comma is what catches naive CSV splitting",
        )
        assertTrue(
            parsed.all { it.coverImageHash.isNullOrEmpty() },
            "cover hashes must stay blank: a hash here points at a file no device has, so every " +
                "book would render a missing cover instead of one the backfill can fetch",
        )
    }

    /**
     * The fixture stopped being books-only in #87. These are the film and show cases it now claims
     * to cover, held to the same standard as the book ones above.
     */
    @Test
    fun sampleCsv_stillCoversItsFilmAndShowCases() {
        val parsed = parsedRows()
        val movies = parsed.filter { it.type == MediaType.MOVIE }
        val shows = parsed.filter { it.type == MediaType.TV_SHOW }

        assertTrue(movies.isNotEmpty(), "the fixture must contain films")
        assertTrue(shows.isNotEmpty(), "the fixture must contain shows")

        assertEquals(
            4,
            movies.map { it.movie.status }.toSet().size,
            "every watch status must be represented, or the film filter chips cannot all be exercised",
        )
        assertTrue(
            movies.any { it.movie.runtimeMinutes == null },
            "a film with no runtime is the case that renders as a blank rather than a stray 0",
        )
        assertTrue(
            movies.any { it.movie.watchedAt != null },
            "a watched film must carry when it was watched, or that column is never exercised",
        )
        assertTrue(
            shows.any { it.show.totalSeasons != null && it.show.totalSeasons!! > 1 },
            "a multi-season show is what exercises grouping on the show screen",
        )
    }
}
