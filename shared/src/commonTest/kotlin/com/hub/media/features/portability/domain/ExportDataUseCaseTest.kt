package com.hub.media.features.portability.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.portability.csv.CSV_SCHEMA_VERSION
import com.hub.media.features.portability.csv.CsvUtil
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Integration test for [ExportDataUseCase] against a real (in-memory) [AppDatabase], following the
 * same pattern as `BookRepositoryTest`/`LogReadingSessionUseCaseTest` -- Room-touching, so this
 * lives in `com.hub.media.features.portability.domain` and is excluded from the Android
 * unit-test variant by the package-wide filter in `shared/build.gradle.kts`; `:shared:jvmTest` is
 * the authoritative gate. Exercises the whole pipeline end to end (repository writes -> use case
 * read -> CSV formatting), complementing `LibraryCsvExporterTest`/`ReadingLogCsvExporterTest`'s
 * pure formatting-only coverage.
 */
class ExportDataUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var readingSessionRepository: ReadingSessionRepository
    private lateinit var useCase: ExportDataUseCase

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        bookRepository = BookRepository(db)
        readingSessionRepository = ReadingSessionRepository(db)
        useCase = ExportDataUseCase(bookRepository, readingSessionRepository)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun execute_emptyDatabase_producesHeaderOnlyCsvForBothFiles() = runTest {
        val result = useCase.execute()
        assertIs<Resource.Success<CsvExportBundle>>(result)

        val libraryLines = result.data.libraryCsv.trimEnd().split(CsvUtil.LINE_ENDING)
        val logsLines = result.data.readingLogsCsv.trimEnd().split(CsvUtil.LINE_ENDING)
        assertEquals(1, libraryLines.size)
        assertEquals(1, logsLines.size)
    }

    @Test
    fun execute_libraryWithBooksAndSessions_exportsEveryRowWithExternalIdentifiers() = runTest {
        val addResult = bookRepository.addBook(
            title = "Sample Book, With a Comma",
            releaseYear = 2021,
            purchasePrice = 19.99,
            format = BookFormat.PAPERBACK,
            totalPages = 250,
            isbn = "9781234567897",
            externalIdentifiers = listOf(
                IdentifierProvider.ISBN to "9781234567897",
                IdentifierProvider.OPEN_LIBRARY to "OL12345M",
            ),
        )
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data

        val sessionResult = readingSessionRepository.logSession(
            mediaId = mediaId,
            timestampStart = Instant.fromEpochMilliseconds(1_700_000_000_000),
            timestampEnd = Instant.fromEpochMilliseconds(1_700_000_600_000),
            durationSeconds = null, // backlogged manual entry, unknown duration
            startUnit = 0.0,
            endUnit = 42.0,
            deltaPages = 42,
            notes = "Great start, \"couldn't put it down\"",
        )
        assertIs<Resource.Success<String>>(sessionResult)

        val result = useCase.execute()
        assertIs<Resource.Success<CsvExportBundle>>(result)
        val bundle = result.data

        val libraryDataLine = bundle.libraryCsv.trimEnd().split(CsvUtil.LINE_ENDING)[1]
        assertTrue(libraryDataLine.contains("\"Sample Book, With a Comma\""))
        assertTrue(libraryDataLine.contains("ISBN:9781234567897"))
        assertTrue(libraryDataLine.contains("OPEN_LIBRARY:OL12345M"))
        assertTrue(libraryDataLine.startsWith("$CSV_SCHEMA_VERSION,"))

        val logsDataLine = bundle.readingLogsCsv.trimEnd().split(CsvUtil.LINE_ENDING)[1]
        assertTrue(logsDataLine.contains(mediaId))
        // Unknown duration must appear as an empty field, never "0" -- see
        // ReadingLogCsvExporterTest for the dedicated unit-level coverage of this rule; this
        // integration test confirms it survives the full repository round-trip too.
        assertTrue(!logsDataLine.contains(",0,"))
        assertTrue(logsDataLine.contains("\"Great start, \"\"couldn't put it down\"\"\""))
    }
}
