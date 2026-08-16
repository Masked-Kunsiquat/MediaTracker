package com.hub.media.features.portability.csv

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
        assertEquals(12, parsedRows().size, "every row in the fixture must import")
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

        assertEquals(
            4,
            parsed.mapNotNull { it.status }.toSet().size,
            "every reading status must be represented, or the filter chips cannot all be exercised",
        )
        assertTrue(
            parsed.any { it.authors?.contains(";") == true },
            "a multi-author book is what proves author search matches inside the joined string",
        )
        assertTrue(
            parsed.any { it.isbn.isNullOrEmpty() },
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
}
