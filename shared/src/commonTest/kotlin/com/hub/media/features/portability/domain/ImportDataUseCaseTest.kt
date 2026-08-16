package com.hub.media.features.portability.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleExternalIdentifier
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.sampleReadingSession
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.LogLevel
import com.hub.media.core.util.RecordingLogger
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.BookWithDetails
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.portability.csv.CSV_SCHEMA_VERSION
import com.hub.media.features.portability.csv.CsvUtil
import com.hub.media.features.portability.csv.LibraryCsvExporter
import com.hub.media.features.portability.csv.ReadingLogCsvExporter
import com.hub.media.features.portability.data.ImportWriteRepository
import com.hub.media.features.portability.goodreads.GoodreadsColumns
import com.hub.media.features.portability.goodreads.GoodreadsCsvImporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Integration tests for [ImportDataUseCase] against a real (in-memory) [AppDatabase], following
 * [ExportDataUseCaseTest]'s pattern -- Room-touching, so this lives in
 * `com.hub.media.features.portability.domain` and is excluded from the Android unit-test variant
 * by the existing package-wide filter in `shared/build.gradle.kts`; `:shared:jvmTest` is the
 * authoritative gate.
 *
 * The single highest-value test here is [roundTrip_exportThenImportIntoFreshDatabase_reproducesOriginalData]:
 * export a populated database, import that exact output into a brand-new empty database, and
 * confirm the result matches field-for-field -- the strongest proof this phase's reader/importer
 * genuinely inverts Phase A's exporter, rather than merely passing hand-crafted unit tests.
 */
class ImportDataUseCaseTest {
    private lateinit var sourceDb: AppDatabase
    private lateinit var sourceBookRepository: BookRepository
    private lateinit var sourceSessionRepository: ReadingSessionRepository

    private lateinit var db: AppDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var sessionRepository: ReadingSessionRepository
    private lateinit var useCase: ImportDataUseCase

    @BeforeTest
    fun setUp() {
        sourceDb = testAppDatabase()
        sourceBookRepository = BookRepository(sourceDb)
        sourceSessionRepository = ReadingSessionRepository(sourceDb)

        db = testAppDatabase()
        bookRepository = BookRepository(db)
        sessionRepository = ReadingSessionRepository(db)
        useCase = ImportDataUseCase(bookRepository, sessionRepository, ImportWriteRepository(db))
    }

    // ---- Logging adoption (ROADMAP Task 15 Phase C) ------------------------------------------

    @Test
    fun execute_withAMalformedHeader_recordsWhyTheImportWasRefused() =
        runTest {
            // A refused import is the single most likely thing to be asked about, and before this the
            // log said nothing at all -- the reason existed only in a message the UI showed once and
            // then discarded. WARN, not ERROR: a malformed file is the user's to fix, not a fault.
            val recorder = RecordingLogger()
            val useCase =
                ImportDataUseCase(
                    bookRepository,
                    sessionRepository,
                    ImportWriteRepository(db),
                    logger = recorder,
                )

            val result =
                useCase.execute(
                    libraryCsv =
                        """
                        not,a,valid,header
                        1,2,3,4
                        """.trimIndent(),
                    readingLogsCsv = null,
                    duplicatePolicy = DuplicatePolicy.SKIP,
                )

            assertIs<Resource.Error>(result)
            val warnings = recorder.entries.filter { it.level == LogLevel.WARN }
            assertEquals(1, warnings.size, "the refusal must be recorded exactly once")
            assertEquals("ImportDataUseCase", warnings.single().tag)
            assertTrue(
                warnings.single().message.contains("library_export.csv"),
                "the entry must name which file was rejected",
            )
        }

    @Test
    fun execute_withNoFileSelected_recordsTheRefusalWithoutAnError() =
        runTest {
            val recorder = RecordingLogger()
            val useCase =
                ImportDataUseCase(
                    bookRepository,
                    sessionRepository,
                    ImportWriteRepository(db),
                    logger = recorder,
                )

            useCase.execute(libraryCsv = null, readingLogsCsv = null, duplicatePolicy = DuplicatePolicy.SKIP)

            assertEquals(1, recorder.entries.count { it.level == LogLevel.WARN })
            assertFalse(
                recorder.entries.any { it.level == LogLevel.ERROR },
                "nothing failed here -- selecting no file is not an app error",
            )
        }

    @Test
    fun execute_withRejectedRows_summarisesThemWithoutLeakingTheirContents() =
        runTest {
            // Rejection reasons embed the raw cell that failed -- reject("$field ... : '$raw'") -- so
            // logging them would put arbitrary column values, titles included, into a file that
            // outlives the import. This asserts the summary counts the rows and names their positions
            // while carrying neither the reason nor the title.
            val recorder = RecordingLogger()
            val useCase =
                ImportDataUseCase(
                    bookRepository,
                    sessionRepository,
                    ImportWriteRepository(db),
                    logger = recorder,
                )
            val csv =
                """
                csv_schema_version,media_id,type,title,authors,release_year,purchase_price,created_at,cover_image_hash,isbn,format,total_pages,status,finished_at,tracking_mode,external_identifiers
                2,11111111-1111-4111-8111-111111111111,BOOK,Fine Book,An Author,1969,,2026-01-05T09:15:00Z,,,PAPERBACK,304,TO_READ,,PAGES,
                2,22222222-2222-4222-8222-222222222222,BOOK,A Title That Must Never Reach The Log,An Author,SECRET_CELL_VALUE,,2026-01-05T09:15:00Z,,,PAPERBACK,304,TO_READ,,PAGES,
                """.trimIndent()

            val result = useCase.execute(csv, readingLogsCsv = null, duplicatePolicy = DuplicatePolicy.SKIP)

            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.rejections.size, "the bad row is still reported to the user in full")
            val warning = recorder.entries.single { it.level == LogLevel.WARN }
            assertEquals("ImportDataUseCase", warning.tag)
            assertTrue(warning.message.contains("1 row(s) rejected"), "the count is the point: ${warning.message}")
            assertTrue(warning.message.contains("#3"), "the row position is what makes it actionable")
            assertFalse(
                warning.message.contains("SECRET_CELL_VALUE"),
                "the reason embeds the raw cell and must not be logged",
            )
            assertFalse(
                warning.message.contains("A Title That Must Never Reach The Log"),
                "a book title must never be persisted to the log",
            )
        }

    @Test
    fun execute_withMoreRejectedRowsThanTheLoggedCap_summarisesTheOverflowCorrectly() =
        runTest {
            // MAX_LOGGED_REJECTED_ROWS (20) caps how many row references the rejection-summary WARN
            // names, so a badly-formed export with thousands of rejects can't crowd everything else
            // out of a capped log file. Nothing before this test exercised that bound. Every row here
            // fails on an unparseable release_year -- a non-sensitive field -- so what's under test is
            // the summary's shape (total count, the first 20 named, the rest counted as "N more"), not
            // the identifier-redaction rule the test above already covers.
            val recorder = RecordingLogger()
            val useCase =
                ImportDataUseCase(
                    bookRepository,
                    sessionRepository,
                    ImportWriteRepository(db),
                    logger = recorder,
                )
            val rejectedRowCount = 25
            val csv = libraryCsvWithInvalidReleaseYears(rejectedRowCount)

            val result = useCase.execute(csv, readingLogsCsv = null, duplicatePolicy = DuplicatePolicy.SKIP)

            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(
                rejectedRowCount,
                result.data.rejections.size,
                "every bad row is still reported to the user in full",
            )
            val warning = recorder.entries.single { it.level == LogLevel.WARN }
            assertTrue(
                warning.message.contains("$rejectedRowCount row(s) rejected"),
                "the true total must be reported even though only some rows are named individually: ${warning.message}",
            )
            // Data rows are CSV rows #2..#26 (row 1 is the header); the first 20 of those (#2..#21)
            // are within MAX_LOGGED_REJECTED_ROWS and must each be named.
            for (row in 2..21) {
                assertTrue(
                    warning.message.contains("BOOK#$row"),
                    "row #$row is within the logged cap and must be named: ${warning.message}",
                )
            }
            // Row #26 is the 25th (last) data row -- beyond the 20-row cap -- and must NOT be named.
            assertFalse(
                warning.message.contains("BOOK#26"),
                "a row beyond the logged cap must not be named individually: ${warning.message}",
            )
            assertTrue(warning.message.contains("and 5 more"), "the omitted count must be reported: ${warning.message}")
        }

    @Test
    fun execute_withNoRejections_logsNoRejectionWarning() =
        runTest {
            // Positive control for the test above: proves the warning is absent because nothing was
            // rejected, not because the summary never fires.
            val recorder = RecordingLogger()
            val useCase =
                ImportDataUseCase(
                    bookRepository,
                    sessionRepository,
                    ImportWriteRepository(db),
                    logger = recorder,
                )
            val csv =
                """
                csv_schema_version,media_id,type,title,authors,release_year,purchase_price,created_at,cover_image_hash,isbn,format,total_pages,status,finished_at,tracking_mode,external_identifiers
                2,33333333-3333-4333-8333-333333333333,BOOK,Fine Book,An Author,1969,,2026-01-05T09:15:00Z,,,PAPERBACK,304,TO_READ,,PAGES,
                """.trimIndent()

            val result = useCase.execute(csv, readingLogsCsv = null, duplicatePolicy = DuplicatePolicy.SKIP)

            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(emptyList(), recorder.entries.filter { it.level == LogLevel.WARN })
        }

    @Test
    fun execute_onCompletion_tracesTheOutcomeAtInfoWithoutLibraryContent() =
        runTest {
            // The half of Phase C that changes what the log *is*: before this, nothing in the codebase
            // emitted below WARN, so the file stayed empty until something broke -- which reads as a
            // broken feature rather than a healthy one. A completed import is the context that explains
            // whatever fails next.
            val recorder = RecordingLogger()
            val useCase =
                ImportDataUseCase(
                    bookRepository,
                    sessionRepository,
                    ImportWriteRepository(db),
                    logger = recorder,
                )
            val csv =
                """
                csv_schema_version,media_id,type,title,authors,release_year,purchase_price,created_at,cover_image_hash,isbn,format,total_pages,status,finished_at,tracking_mode,external_identifiers
                2,44444444-4444-4444-8444-444444444444,BOOK,A Title That Must Never Reach The Log,An Author,1969,,2026-01-05T09:15:00Z,,,PAPERBACK,304,TO_READ,,PAGES,
                """.trimIndent()

            useCase.execute(csv, readingLogsCsv = null, duplicatePolicy = DuplicatePolicy.SKIP)

            val info = recorder.entries.single { it.level == LogLevel.INFO }
            assertEquals("ImportDataUseCase", info.tag)
            assertTrue(info.message.contains("1 imported"), "the counts are the trace: ${info.message}")
            assertFalse(
                info.message.contains("A Title That Must Never Reach The Log"),
                "lifecycle tracing is still bound by the identifier rule",
            )
        }

    @AfterTest
    fun tearDown() {
        sourceDb.close()
        db.close()
    }

    private suspend fun addBook(
        title: String = "Sample Book",
        releaseYear: Int? = 2020,
        purchasePrice: Double? = 9.99,
        format: BookFormat = BookFormat.PAPERBACK,
        totalPages: Int? = 300,
        isbn: String? = "9780000000001",
        status: ReadingStatus = ReadingStatus.TO_READ,
        externalIdentifiers: List<Pair<IdentifierProvider, String>> = emptyList(),
        authors: String? = null,
        repository: BookRepository = sourceBookRepository,
    ): String {
        val result =
            repository.addBook(
                title = title,
                releaseYear = releaseYear,
                purchasePrice = purchasePrice,
                format = format,
                totalPages = totalPages,
                isbn = isbn,
                externalIdentifiers = externalIdentifiers,
                status = status,
                authors = authors,
            )
        assertIs<Resource.Success<String>>(result)
        return result.data
    }

    /**
     * Builds a `library_export.csv` with [count] data rows that each fail validation the same,
     * safe way -- an unparseable `release_year` -- so [execute_withMoreRejectedRowsThanTheLoggedCap_summarisesTheOverflowCorrectly]
     * can exercise [ImportDataUseCase]'s `MAX_LOGGED_REJECTED_ROWS` boundary without any row
     * carrying content that rule forbids logging (title, reason). Built as a `List<String>` of
     * lines joined with `"\n"` rather than a literal multi-line string, to keep the embedded
     * newline unambiguous.
     */
    private fun libraryCsvWithInvalidReleaseYears(count: Int): String {
        val header =
            "csv_schema_version,media_id,type,title,authors,release_year,purchase_price," +
                "created_at,cover_image_hash,isbn,format,total_pages,status,finished_at,tracking_mode," +
                "external_identifiers"
        val rows =
            (1..count).map { i ->
                // Distinct, valid-shaped UUID per row -- media_id itself isn't validated as UUID-shaped
                // (see LibraryCsvImporter's KDoc on COL_MEDIA_ID), but each row still needs its own id.
                val mediaId = "aaaaaaaa-aaaa-4aaa-8aaa-" + i.toString().padStart(12, '0')
                listOf(
                    "2",
                    mediaId,
                    "BOOK",
                    "Fine Book",
                    "An Author",
                    "invalid-year-$i",
                    "",
                    "2026-01-05T09:15:00Z",
                    "",
                    "",
                    "PAPERBACK",
                    "304",
                    "TO_READ",
                    "",
                    "PAGES",
                    "",
                ).joinToString(",")
            }
        return (listOf(header) + rows).joinToString(separator = "\n")
    }

    private suspend fun exportCurrentSourceDb(): Pair<String, String> {
        val books = sourceBookRepository.observeAllBooksWithDetails().first()
        val identifiers = sourceBookRepository.observeAllExternalIdentifiers().first().groupBy { it.mediaId }
        val sessions = sourceSessionRepository.observeAllSessions().first()
        return LibraryCsvExporter.export(books, identifiers) to ReadingLogCsvExporter.export(sessions)
    }

    @Test
    fun execute_bothFilesNull_returnsError() =
        runTest {
            val result = useCase.execute(null, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Error>(result)
        }

    @Test
    fun execute_emptyExportFiles_succeedsWithZeroCounts() =
        runTest {
            val (libraryCsv, logsCsv) = exportCurrentSourceDb() // nothing added yet -- header-only
            val result = useCase.execute(libraryCsv, logsCsv, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(0, result.data.booksImported)
            assertEquals(0, result.data.sessionsImported)
            assertTrue(result.data.rejections.isEmpty())
        }

    @Test
    fun roundTrip_exportThenImportIntoFreshDatabase_reproducesOriginalData() =
        runTest {
            val mediaId =
                addBook(
                    title = "Dune, Book One",
                    isbn = "9780441013593",
                    externalIdentifiers =
                        listOf(
                            IdentifierProvider.ISBN to "9780441013593",
                            IdentifierProvider.OPEN_LIBRARY to "OL893415M",
                        ),
                    status = ReadingStatus.READING,
                )
            sourceSessionRepository.logSession(
                mediaId = mediaId,
                timestampStart = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_000_000),
                timestampEnd = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_600_000),
                durationSeconds = null, // unknown-duration edge case must round-trip too
                startUnit = 0.0,
                endUnit = 42.0,
                deltaPages = 42,
                notes = "Great start, \"couldn't put it down\"\nSecond line of the note.",
            )

            val (libraryCsv, logsCsv) = exportCurrentSourceDb()

            val result = useCase.execute(libraryCsv, logsCsv, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.booksImported)
            assertEquals(1, result.data.sessionsImported)
            assertTrue(result.data.rejections.isEmpty())

            val importedBooks = bookRepository.observeAllBooksWithDetails().first()
            val originalBooks = sourceBookRepository.observeAllBooksWithDetails().first()
            assertEquals(originalBooks, importedBooks)

            val importedIdentifiers = bookRepository.observeAllExternalIdentifiers().first().toSet()
            val originalIdentifiers = sourceBookRepository.observeAllExternalIdentifiers().first().toSet()
            assertEquals(originalIdentifiers, importedIdentifiers)

            val importedSessions = sessionRepository.observeAllSessions().first()
            val originalSessions = sourceSessionRepository.observeAllSessions().first()
            assertEquals(originalSessions, importedSessions)
        }

    // ---- authors (schema v5 / CSV v2, ROADMAP Task 9 Phase A) ----------------------------------

    @Test
    fun roundTrip_bookWithAuthors_reproducesAuthorsFieldExactly() =
        runTest {
            addBook(title = "Multi-Author Anthology", authors = "Ann Sample Author; B. Other Author")
            val (libraryCsv, _) = exportCurrentSourceDb()

            val result = useCase.execute(libraryCsv, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.booksImported)

            val imported = bookRepository.observeAllBooksWithDetails().first().single()
            assertEquals("Ann Sample Author; B. Other Author", imported.details?.authors)
        }

    /**
     * The CSV-v1-compatibility deliverable (ROADMAP Task 9 Phase A): a `library_export.csv`
     * written before this phase existed (no `authors` column at all -- [LibraryCsvExporter.HEADER_V1]'s
     * shape, `csv_schema_version=1`) must still import cleanly at the current
     * `CSV_SCHEMA_VERSION=2` build, with `authors` landing `null` (nothing to recover) rather than
     * the whole file being refused as an unrecognized header.
     */
    @Test
    fun execute_v1LibraryFile_importsCleanly_authorsLandsNull() =
        runTest {
            val v1Csv =
                buildString {
                    append(CsvUtil.buildLine(LibraryCsvExporter.HEADER_V1))
                    append(
                        CsvUtil.buildLine(
                            listOf(
                                "1",
                                "v1-media-id",
                                "BOOK",
                                "A Pre-Task-9 Book",
                                "2015",
                                "12.50",
                                "2024-01-01T00:00:00Z",
                                "",
                                "9780000000002",
                                "PAPERBACK",
                                "250",
                                "TO_READ",
                                "",
                                "PAGES",
                                "",
                            ),
                        ),
                    )
                }

            val result = useCase.execute(v1Csv, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.booksImported)
            assertTrue(result.data.rejections.isEmpty())

            val imported = bookRepository.observeAllBooksWithDetails().first().single()
            assertEquals("A Pre-Task-9 Book", imported.mediaItem.title)
            assertEquals(null, imported.details?.authors, "a v1 file never recorded an author -- null, not fabricated")
            assertEquals("9780000000002", imported.details?.isbn)
            assertEquals(250, imported.details?.totalPages)
        }

    @Test
    fun execute_duplicateByMediaId_skipPolicy_leavesExistingBookUnchanged() =
        runTest {
            val mediaId = addBook(title = "Original Title", repository = bookRepository)
            val existingBefore = bookRepository.getBookWithDetails(mediaId)

            // A "newer" export of the SAME media_id with different metadata.
            val incomingBooks =
                listOf(
                    BookWithDetails(
                        mediaItem = sampleMediaItem(id = mediaId, title = "Changed Title", releaseYear = 1999),
                        details = sampleBookDetails(mediaId = mediaId, isbn = "9789999999999"),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingBooks, emptyMap())

            val result = useCase.execute(libraryCsv, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.booksSkipped)
            assertEquals(0, result.data.booksImported)

            assertEquals(existingBefore, bookRepository.getBookWithDetails(mediaId))
        }

    @Test
    fun execute_duplicateByMediaId_replacePolicy_overwritesFields() =
        runTest {
            val mediaId = addBook(title = "Original Title", releaseYear = 2000, repository = bookRepository)

            val incomingBooks =
                listOf(
                    BookWithDetails(
                        mediaItem = sampleMediaItem(id = mediaId, title = "Replaced Title", releaseYear = 1999),
                        details =
                            sampleBookDetails(
                                mediaId = mediaId,
                                isbn = "9789999999999",
                                format = BookFormat.HARDCOVER,
                            ),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingBooks, emptyMap())

            val result = useCase.execute(libraryCsv, null, DuplicatePolicy.REPLACE)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.booksReplaced)

            val updated = bookRepository.getBookWithDetails(mediaId)
            assertEquals("Replaced Title", updated?.mediaItem?.title)
            assertEquals(1999, updated?.mediaItem?.releaseYear)
            assertEquals("9789999999999", updated?.details?.isbn)
            assertEquals(BookFormat.HARDCOVER, updated?.details?.format)
        }

    @Test
    fun execute_duplicateByMediaId_replacePolicy_neverTouchesCreatedAtOrCoverImageHash() =
        runTest {
            val mediaId = addBook(title = "Original Title", repository = bookRepository)
            val before = bookRepository.getBookWithDetails(mediaId)!!

            val incomingBooks =
                listOf(
                    BookWithDetails(
                        mediaItem =
                            sampleMediaItem(
                                id = mediaId,
                                title = "Replaced Title",
                                createdAt = kotlin.time.Instant.fromEpochMilliseconds(1_234_567_890_000),
                                coverImageHash = "some-foreign-hash",
                            ),
                        details = sampleBookDetails(mediaId = mediaId),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingBooks, emptyMap())

            val result = useCase.execute(libraryCsv, null, DuplicatePolicy.REPLACE)
            assertIs<Resource.Success<ImportSummary>>(result)

            val after = bookRepository.getBookWithDetails(mediaId)
            assertEquals(before.mediaItem.createdAt, after?.mediaItem?.createdAt)
            assertEquals(before.mediaItem.coverImageHash, after?.mediaItem?.coverImageHash)
        }

    @Test
    fun execute_duplicateByMediaId_mergePolicy_backfillsNullFieldsOnly_keepsExistingNonNullValues() =
        runTest {
            // Existing book already has a purchasePrice/isbn on record; releaseYear is unset.
            val mediaId =
                addBook(
                    title = "Existing Title",
                    releaseYear = null,
                    purchasePrice = 14.99,
                    isbn = "9781111111111",
                    repository = bookRepository,
                )

            val incomingBooks =
                listOf(
                    BookWithDetails(
                        mediaItem =
                            sampleMediaItem(
                                id = mediaId,
                                title = "Incoming Title",
                                releaseYear = 2015,
                                purchasePrice = 1.00,
                            ),
                        details = sampleBookDetails(mediaId = mediaId, isbn = "9782222222222"),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingBooks, emptyMap())

            val result = useCase.execute(libraryCsv, null, DuplicatePolicy.MERGE)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.booksMerged)

            val merged = bookRepository.getBookWithDetails(mediaId)
            assertEquals("Existing Title", merged?.mediaItem?.title, "title is identity -- merge must never touch it")
            assertEquals(2015, merged?.mediaItem?.releaseYear, "releaseYear was null -- merge should backfill it")
            assertEquals(
                14.99,
                merged?.mediaItem?.purchasePrice,
                "purchasePrice was already set -- merge must not overwrite it",
            )
            assertEquals("9781111111111", merged?.details?.isbn, "isbn was already set -- merge must not overwrite it")
        }

    @Test
    fun execute_duplicateByMergePolicy_addsNewExternalIdentifierProviderWithoutRemovingExisting() =
        runTest {
            val mediaId = addBook(title = "Book", isbn = "9781111111111", repository = bookRepository)
            db.externalIdentifierDao().insert(
                sampleExternalIdentifier(mediaId, IdentifierProvider.ISBN, "9781111111111"),
            )

            val incomingBooks =
                listOf(
                    BookWithDetails(
                        mediaItem = sampleMediaItem(id = mediaId, title = "Book"),
                        details = sampleBookDetails(mediaId = mediaId, isbn = "9781111111111"),
                    ),
                )
            val incomingIdentifiers =
                mapOf(
                    mediaId to
                        listOf(
                            // must NOT overwrite
                            sampleExternalIdentifier(mediaId, IdentifierProvider.ISBN, "9789999999999"),
                            // must be added
                            sampleExternalIdentifier(mediaId, IdentifierProvider.OPEN_LIBRARY, "OL999M"),
                        ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingBooks, incomingIdentifiers)

            val result = useCase.execute(libraryCsv, null, DuplicatePolicy.MERGE)
            assertIs<Resource.Success<ImportSummary>>(result)

            val identifiers = bookRepository.observeAllExternalIdentifiers().first().filter { it.mediaId == mediaId }
            val byProvider = identifiers.associate { it.provider to it.externalId }
            assertEquals(
                "9781111111111",
                byProvider[IdentifierProvider.ISBN],
                "existing ISBN mapping must survive merge untouched",
            )
            assertEquals("OL999M", byProvider[IdentifierProvider.OPEN_LIBRARY], "new provider must be added by merge")
        }

    @Test
    fun roundTrip_openLibraryWorkKeySurvivesExportAndImport() =
        runTest {
            // The work key is a new IdentifierProvider, not a new column, so the CSV surface carries it
            // with no format change -- the exporter packs every ExternalIdentifierEntity and
            // unpackIdentifiers resolves the provider by name. That is worth *proving* rather than
            // assuming: a book whose work key is dropped on a CSV round-trip becomes a silent backfill
            // candidate again on the next device, paying for the same rate-limited lookup twice.
            val mediaId = "media-with-a-work-key"
            val incomingBooks =
                listOf(
                    BookWithDetails(
                        mediaItem = sampleMediaItem(id = mediaId, title = "The Hobbit"),
                        details = sampleBookDetails(mediaId = mediaId, isbn = "9780547928227"),
                    ),
                )
            val incomingIdentifiers =
                mapOf(
                    mediaId to
                        listOf(
                            sampleExternalIdentifier(mediaId, IdentifierProvider.ISBN, "9780547928227"),
                            sampleExternalIdentifier(mediaId, IdentifierProvider.OPEN_LIBRARY, "/books/OL33891995M"),
                            sampleExternalIdentifier(mediaId, IdentifierProvider.OPEN_LIBRARY_WORK, "/works/OL27482W"),
                        ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingBooks, incomingIdentifiers)
            assertTrue(
                libraryCsv.contains("OPEN_LIBRARY_WORK:/works/OL27482W"),
                "the exporter must pack the work key: $libraryCsv",
            )

            val result = useCase.execute(libraryCsv, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)

            val byProvider =
                bookRepository
                    .observeAllExternalIdentifiers()
                    .first()
                    .filter { it.mediaId == mediaId }
                    .associate { it.provider to it.externalId }
            assertEquals("/works/OL27482W", byProvider[IdentifierProvider.OPEN_LIBRARY_WORK])
            assertEquals(
                "/books/OL33891995M",
                byProvider[IdentifierProvider.OPEN_LIBRARY],
                "the edition key must round-trip alongside it, not be collapsed into one row",
            )
        }

    @Test
    fun execute_duplicateByIsbn_matchesEvenWhenMediaIdDiffers() =
        runTest {
            val existingMediaId = addBook(title = "Existing", isbn = "9783333333333", repository = bookRepository)

            val incomingBooks =
                listOf(
                    BookWithDetails(
                        mediaItem = sampleMediaItem(id = "a-completely-different-media-id", title = "Incoming Copy"),
                        details =
                            sampleBookDetails(
                                mediaId = "a-completely-different-media-id",
                                isbn = "9783333333333",
                            ),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingBooks, emptyMap())

            val result = useCase.execute(libraryCsv, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(
                1,
                result.data.booksSkipped,
                "should match the existing book by ISBN despite a different media_id",
            )
            assertEquals(0, result.data.booksImported)

            // No new book was inserted under the "different" media_id.
            assertEquals(null, bookRepository.getBookWithDetails("a-completely-different-media-id"))
            assertTrue(bookRepository.getBookWithDetails(existingMediaId) != null)
        }

    @Test
    fun execute_duplicateByTitleAndYear_matchesWhenNoIsbnOnEitherSide() =
        runTest {
            val existingMediaId =
                addBook(
                    title = "Foundation",
                    releaseYear = 1951,
                    isbn = null,
                    repository = bookRepository,
                )

            val incomingBooks =
                listOf(
                    BookWithDetails(
                        mediaItem = sampleMediaItem(id = "different-id", title = "FOUNDATION", releaseYear = 1951),
                        details = sampleBookDetails(mediaId = "different-id", isbn = null),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingBooks, emptyMap())

            val result = useCase.execute(libraryCsv, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.booksSkipped, "case-insensitive title + matching release year should match")
            assertTrue(bookRepository.getBookWithDetails(existingMediaId) != null)
        }

    // ---- Finding 2: ISBN-ingestion (edition year) vs Goodreads (work year) release-year mismatch ----
    // These reproduce the "2026 anniversary printing masks a 2016 original" scenario: a book added
    // via ISBN stores the scanned edition's year, a later Goodreads import prefers the work's
    // Original Publication Year. Without a title-only fallback tier, media_id/isbn/title+year all
    // miss and the book would be silently duplicated instead of merged.

    @Test
    fun execute_titleOnly_differingReleaseYears_matchesAndReportsReviewNote() =
        runTest {
            // Existing book was added by ISBN scan of a later reprint -- stores the EDITION year.
            val existingMediaId =
                addBook(
                    title = "The Long Way Home",
                    releaseYear = 2026,
                    isbn = "9781111111111",
                    repository = bookRepository,
                )

            // Incoming row (e.g. Goodreads) has no ISBN in common and stores the WORK year instead.
            val incomingBooks =
                listOf(
                    BookWithDetails(
                        mediaItem =
                            sampleMediaItem(
                                id = "different-id",
                                title = "The Long Way Home",
                                releaseYear = 2016,
                            ),
                        details = sampleBookDetails(mediaId = "different-id", isbn = null),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingBooks, emptyMap())

            val result = useCase.execute(libraryCsv, null, DuplicatePolicy.MERGE)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(
                1,
                result.data.booksMerged,
                "differing release years must not defeat matching -- this would otherwise silently duplicate the book (Finding 2)",
            )
            assertEquals(0, result.data.booksImported, "must not have been inserted as a brand-new book")

            // MERGE must never overwrite the existing (non-null) edition year with the incoming one.
            val merged = bookRepository.getBookWithDetails(existingMediaId)
            assertEquals(
                2026,
                merged?.mediaItem?.releaseYear,
                "existing releaseYear was already set -- merge must not overwrite it",
            )

            assertEquals(1, result.data.notes.size, "a title-only match must be reported, never applied silently")
            val note = result.data.notes.single()
            assertTrue(note.contains("The Long Way Home"))
            assertTrue(note.contains("2026"))
            assertTrue(note.contains("2016"))
        }

    @Test
    fun execute_titleOnly_missingIsbnOnOneSide_stillMatchesByTitleWhenYearsDiffer() =
        runTest {
            // Existing book has an ISBN on record (e.g. added by ISBN scan); incoming row has none at
            // all (e.g. an older Goodreads entry with a blank ISBN13 column) and a different year.
            val existingMediaId =
                addBook(
                    title = "Salt and Ember",
                    releaseYear = 2026,
                    isbn = "9782222222222",
                    repository = bookRepository,
                )

            val incomingBooks =
                listOf(
                    BookWithDetails(
                        mediaItem = sampleMediaItem(id = "goodreads-id", title = "Salt and Ember", releaseYear = 1990),
                        details = sampleBookDetails(mediaId = "goodreads-id", isbn = null),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingBooks, emptyMap())

            val result = useCase.execute(libraryCsv, null, DuplicatePolicy.MERGE)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(
                1,
                result.data.booksMerged,
                "missing ISBN on the incoming side must still fall through to the title-only tier",
            )
            assertEquals(0, result.data.booksImported)
            assertEquals(1, result.data.notes.size)

            assertTrue(bookRepository.getBookWithDetails(existingMediaId) != null)
        }

    @Test
    fun execute_titleOnly_missingIsbnOnBothSides_stillMatchesByTitleWhenYearsDiffer() =
        runTest {
            val existingMediaId =
                addBook(
                    title = "Nebula's Edge",
                    releaseYear = 2015,
                    isbn = null,
                    repository = bookRepository,
                )

            val incomingBooks =
                listOf(
                    BookWithDetails(
                        mediaItem = sampleMediaItem(id = "goodreads-id", title = "Nebula's Edge", releaseYear = 1975),
                        details = sampleBookDetails(mediaId = "goodreads-id", isbn = null),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingBooks, emptyMap())

            val result = useCase.execute(libraryCsv, null, DuplicatePolicy.MERGE)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(
                1,
                result.data.booksMerged,
                "no ISBN on either side must still fall through to the title-only tier when years differ",
            )
            assertEquals(0, result.data.booksImported)
            assertEquals(1, result.data.notes.size)

            assertTrue(bookRepository.getBookWithDetails(existingMediaId) != null)
        }

    @Test
    fun execute_matchingIsbns_matchesOnIsbnTier_regardlessOfDifferingYears_noReviewNote() =
        runTest {
            // Same physical edition (same ISBN) but two different recorded years -- the ISBN tier
            // should resolve this with full confidence, never falling through to the title-only tier.
            val existingMediaId =
                addBook(
                    title = "The Long Way Home",
                    releaseYear = 2026,
                    isbn = "9783333333333",
                    repository = bookRepository,
                )

            val incomingBooks =
                listOf(
                    BookWithDetails(
                        mediaItem =
                            sampleMediaItem(
                                id = "different-id",
                                title = "The Long Way Home",
                                releaseYear = 2016,
                            ),
                        details = sampleBookDetails(mediaId = "different-id", isbn = "9783333333333"),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingBooks, emptyMap())

            val result = useCase.execute(libraryCsv, null, DuplicatePolicy.MERGE)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(
                1,
                result.data.booksMerged,
                "matching ISBNs must match on the ISBN tier even when years disagree",
            )
            assertEquals(0, result.data.booksImported)
            assertTrue(
                result.data.notes.isEmpty(),
                "an ISBN-tier match is high-confidence and must not be flagged for review",
            )

            assertTrue(bookRepository.getBookWithDetails(existingMediaId) != null)
        }

    // ---- In-file duplicates: later rows must resolve against earlier rows from the SAME file ----

    @Test
    fun execute_inFileDuplicateByMediaId_doesNotAbortImport_skipPolicyKeepsFirstRow() =
        runTest {
            val sharedMediaId = "shared-media-id"
            val incomingBooks =
                listOf(
                    BookWithDetails(
                        mediaItem = sampleMediaItem(id = sharedMediaId, title = "First Row"),
                        details = sampleBookDetails(mediaId = sharedMediaId, isbn = "9781111111111"),
                    ),
                    BookWithDetails(
                        // Same media_id as the row above -- a re-exported file can legitimately contain
                        // this (e.g. two edits of the same book queued in one export), and inserting both
                        // as fresh rows would collide on media_items' primary key (OnConflictStrategy.ABORT)
                        // and abort the ENTIRE atomic import, not just this one row.
                        mediaItem = sampleMediaItem(id = sharedMediaId, title = "Second Row (same media_id)"),
                        details = sampleBookDetails(mediaId = sharedMediaId, isbn = "9782222222222"),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingBooks, emptyMap())

            val result = useCase.execute(libraryCsv, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(
                result,
                "two rows sharing a media_id within one file must not abort the whole atomic import",
            )
            assertEquals(1, result.data.booksImported, "first row is a fresh insert")
            assertEquals(
                1,
                result.data.booksSkipped,
                "second row must resolve against the first row this same import already added, not attempt a second insert",
            )

            val books = bookRepository.observeAllBooksWithDetails().first()
            assertEquals(1, books.size, "must not end up with two rows sharing the same primary key")
            assertEquals("First Row", books.single().mediaItem.title, "SKIP: earliest row in the file wins")
        }

    @Test
    fun execute_inFileDuplicateByMediaId_replacePolicy_lastRowInFileWins() =
        runTest {
            val sharedMediaId = "shared-media-id"
            val incomingBooks =
                listOf(
                    BookWithDetails(
                        mediaItem = sampleMediaItem(id = sharedMediaId, title = "First Row", releaseYear = 2000),
                        details = sampleBookDetails(mediaId = sharedMediaId, isbn = "9781111111111"),
                    ),
                    BookWithDetails(
                        mediaItem = sampleMediaItem(id = sharedMediaId, title = "Second Row", releaseYear = 2010),
                        details = sampleBookDetails(mediaId = sharedMediaId, isbn = "9782222222222"),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingBooks, emptyMap())

            val result = useCase.execute(libraryCsv, null, DuplicatePolicy.REPLACE)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.booksImported)
            assertEquals(1, result.data.booksReplaced)

            val books = bookRepository.observeAllBooksWithDetails().first()
            assertEquals(1, books.size)
            val book = books.single()
            assertEquals("Second Row", book.mediaItem.title, "REPLACE: last row in the file wins for managed fields")
            assertEquals(2010, book.mediaItem.releaseYear)
            assertEquals("9782222222222", book.details?.isbn)
        }

    @Test
    fun execute_inFileDuplicateByIsbn_doesNotCreateTwoBooks() =
        runTest {
            val sharedIsbn = "9783333333333"
            val incomingBooks =
                listOf(
                    BookWithDetails(
                        mediaItem = sampleMediaItem(id = "media-1", title = "First Copy"),
                        details = sampleBookDetails(mediaId = "media-1", isbn = sharedIsbn),
                    ),
                    BookWithDetails(
                        // Different media_id, same isbn -- without matching against rows already added by
                        // this same import, this would insert as a second, unrelated book despite the ISBN
                        // tier's whole purpose being to recognize re-imports of the same book.
                        mediaItem = sampleMediaItem(id = "media-2", title = "Second Copy"),
                        details = sampleBookDetails(mediaId = "media-2", isbn = sharedIsbn),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingBooks, emptyMap())

            val result = useCase.execute(libraryCsv, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.booksImported)
            assertEquals(
                1,
                result.data.booksSkipped,
                "second row shares an isbn with a row this same import already added -- must be treated as a duplicate",
            )

            val books = bookRepository.observeAllBooksWithDetails().first()
            assertEquals(1, books.size, "a duplicate ISBN within the same file must not silently create two books")
            assertEquals(
                "media-1",
                books.single().mediaItem.id,
                "the fresh-inserted media_id (\"media-1\") is the one that actually exists",
            )
            assertEquals(
                null,
                bookRepository.getBookWithDetails("media-2"),
                "the second row's own media_id was never written as its own book",
            )
        }

    // ---- Sessions must land on a book matched by isbn/title+year, not only an exact media_id ----

    @Test
    fun execute_sessionForBook_matchedByIsbn_isNotOrphaned_andRewrittenToMatchedBooksId() =
        runTest {
            val existingMediaId = addBook(title = "Existing Book", isbn = "9784444444444", repository = bookRepository)

            // The library row re-imports the SAME book under a different media_id than the one already
            // in the database (e.g. a Goodreads-style import, or a restore where ids were regenerated)
            // -- it must match the existing book via the isbn tier rather than insert a duplicate.
            val fileMediaId = "file-own-media-id"
            val incomingBooks =
                listOf(
                    BookWithDetails(
                        mediaItem = sampleMediaItem(id = fileMediaId, title = "Existing Book (re-imported)"),
                        details = sampleBookDetails(mediaId = fileMediaId, isbn = "9784444444444"),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingBooks, emptyMap())

            // A real reading_logs_export.csv from the same source references the file's OWN media_id,
            // not the pre-existing book's real (different) id.
            val session = sampleReadingSession(mediaId = fileMediaId)
            val logsCsv = ReadingLogCsvExporter.export(listOf(session))

            val result = useCase.execute(libraryCsv, logsCsv, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertTrue(
                result.data.rejections.isEmpty(),
                "session must not be rejected as an orphan just because its book matched by isbn under a different media_id",
            )
            assertEquals(1, result.data.sessionsImported)

            val sessionsOnExistingBook = sessionRepository.observeSessionsForMedia(existingMediaId).first()
            assertEquals(1, sessionsOnExistingBook.size, "session must be rewritten onto the existing book's real id")
            assertEquals(session.id, sessionsOnExistingBook.single().id)

            val sessionsOnFileMediaId = sessionRepository.observeSessionsForMedia(fileMediaId).first()
            assertTrue(
                sessionsOnFileMediaId.isEmpty(),
                "session must not remain attached to the file's own media_id, which was never written as a book",
            )
        }

    @Test
    fun execute_sessionForBook_matchedByTitleAndYear_isNotOrphaned_andRewrittenToMatchedBooksId() =
        runTest {
            val existingMediaId =
                addBook(title = "Foundation", releaseYear = 1951, isbn = null, repository = bookRepository)

            val fileMediaId = "file-own-media-id"
            val incomingBooks =
                listOf(
                    BookWithDetails(
                        mediaItem = sampleMediaItem(id = fileMediaId, title = "FOUNDATION", releaseYear = 1951),
                        details = sampleBookDetails(mediaId = fileMediaId, isbn = null),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingBooks, emptyMap())

            val session = sampleReadingSession(mediaId = fileMediaId)
            val logsCsv = ReadingLogCsvExporter.export(listOf(session))

            val result = useCase.execute(libraryCsv, logsCsv, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertTrue(
                result.data.rejections.isEmpty(),
                "session must not be rejected as an orphan just because its book matched by title+year under a different media_id",
            )
            assertEquals(1, result.data.sessionsImported)

            val sessionsOnExistingBook = sessionRepository.observeSessionsForMedia(existingMediaId).first()
            assertEquals(1, sessionsOnExistingBook.size, "session must be rewritten onto the existing book's real id")
            assertEquals(session.id, sessionsOnExistingBook.single().id)
        }

    @Test
    fun execute_orphanSession_isSkippedAndReported_otherRowsStillImport() =
        runTest {
            val mediaId = addBook(title = "Known Book", repository = bookRepository)

            val knownSession = sampleReadingSession(mediaId = mediaId)
            val orphanSession = sampleReadingSession(mediaId = "no-such-book-id")
            val logsCsv = ReadingLogCsvExporter.export(listOf(knownSession, orphanSession))

            val result = useCase.execute(null, logsCsv, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.sessionsImported)
            assertEquals(1, result.data.rejections.size)
            val rejection = result.data.rejections.single()
            assertEquals(ImportRowSource.SESSION, rejection.source)
            assertTrue(rejection.reason.contains("no-such-book-id"))

            assertTrue(sessionRepository.observeSessionsForMedia(mediaId).first().isNotEmpty())
            assertTrue(sessionRepository.observeSessionsForMedia("no-such-book-id").first().isEmpty())
        }

    @Test
    fun execute_invalidBusinessRuleRow_isSkippedWithReport_othersStillImport() =
        runTest {
            val validRow =
                BookWithDetails(
                    mediaItem = sampleMediaItem(id = "media-good", title = "Valid Book"),
                    details = sampleBookDetails(mediaId = "media-good"),
                )
            val invalidRow =
                BookWithDetails(
                    mediaItem = sampleMediaItem(id = "media-bad", title = ""), // blank title -- fails validation
                    details = sampleBookDetails(mediaId = "media-bad"),
                )
            val libraryCsv = LibraryCsvExporter.export(listOf(validRow, invalidRow), emptyMap())

            val result = useCase.execute(libraryCsv, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.booksImported)
            assertEquals(1, result.data.rejections.size)
            assertEquals(
                ImportRowSource.BOOK,
                result.data.rejections
                    .single()
                    .source,
            )
            assertTrue(
                result.data.rejections
                    .single()
                    .reason
                    .contains("Title"),
            )

            assertTrue(bookRepository.getBookWithDetails("media-good") != null)
            assertTrue(bookRepository.getBookWithDetails("media-bad") == null)
        }

    @Test
    fun execute_wrongColumnCountRow_refusesWholeImport_nothingWritten() =
        runTest {
            val validRow =
                BookWithDetails(
                    mediaItem = sampleMediaItem(id = "media-good", title = "Valid Book"),
                    details = sampleBookDetails(mediaId = "media-good"),
                )
            val wellFormedCsv = LibraryCsvExporter.export(listOf(validRow), emptyMap())
            // Truncate the last line to simulate a structurally corrupted/truncated file.
            val corrupted = wellFormedCsv.trimEnd().substringBeforeLast(",") + "\r\n"

            val result = useCase.execute(corrupted, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Error>(result)

            assertTrue(
                bookRepository.observeAllBooksWithDetails().first().isEmpty(),
                "a structurally rejected file must write nothing",
            )
        }

    @Test
    fun execute_newerSchemaVersion_refusesImportEntirely() =
        runTest {
            val validRow =
                BookWithDetails(
                    mediaItem = sampleMediaItem(id = "media-good", title = "Valid Book"),
                    details = sampleBookDetails(mediaId = "media-good"),
                )
            val wellFormedCsv = LibraryCsvExporter.export(listOf(validRow), emptyMap())
            val bumped = wellFormedCsv.replaceFirst("\r\n${CSV_SCHEMA_VERSION},", "\r\n${CSV_SCHEMA_VERSION + 1},")

            val result = useCase.execute(bumped, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Error>(result)
            assertTrue(result.message.contains("newer", ignoreCase = true))
            assertTrue(bookRepository.observeAllBooksWithDetails().first().isEmpty())
        }

    @Test
    fun execute_unterminatedQuote_refusesImportEntirely() =
        runTest {
            val header = LibraryCsvExporter.HEADER.joinToString(",")
            val malformed = "$header\r\n1,media-1,BOOK,\"unterminated,,,,,,,,,,\r\n"

            val result = useCase.execute(malformed, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Error>(result)
            assertTrue(bookRepository.observeAllBooksWithDetails().first().isEmpty())
        }

    // ---- executeGoodreads (ROADMAP Task 8 Phase D) ---------------------------------------------
    // Every title/author/date below is invented -- no real personal Goodreads export was used.

    private val goodreadsHeader =
        listOf(
            "Book Id",
            GoodreadsColumns.TITLE,
            "Author",
            GoodreadsColumns.ISBN,
            GoodreadsColumns.ISBN13,
            GoodreadsColumns.MY_RATING,
            "Average Rating",
            "Publisher",
            GoodreadsColumns.BINDING,
            GoodreadsColumns.NUMBER_OF_PAGES,
            GoodreadsColumns.YEAR_PUBLISHED,
            GoodreadsColumns.ORIGINAL_PUBLICATION_YEAR,
            GoodreadsColumns.DATE_READ,
            GoodreadsColumns.DATE_ADDED,
            GoodreadsColumns.BOOKSHELVES,
            GoodreadsColumns.EXCLUSIVE_SHELF,
            GoodreadsColumns.READ_COUNT,
        )

    private fun goodreadsRow(
        bookId: String,
        title: String,
        isbn: String = "",
        isbn13: String = "",
        myRating: String = "0",
        binding: String = "",
        numberOfPages: String = "",
        yearPublished: String = "",
        originalPublicationYear: String = "",
        dateRead: String = "",
        dateAdded: String = "",
        bookshelves: String = "",
        exclusiveShelf: String = "to-read",
        readCount: String = "0",
    ): List<String> =
        listOf(
            bookId,
            title,
            "Some Author",
            isbn,
            isbn13,
            myRating,
            "4.10",
            "Some Publisher",
            binding,
            numberOfPages,
            yearPublished,
            originalPublicationYear,
            dateRead,
            dateAdded,
            bookshelves,
            exclusiveShelf,
            readCount,
        )

    private fun goodreadsCsv(vararg rows: List<String>): String =
        buildString {
            append(CsvUtil.buildLine(goodreadsHeader))
            rows.forEach { append(CsvUtil.buildLine(it)) }
        }

    @Test
    fun executeGoodreads_realisticMultiRowFixture_importsBooksWithExpectedFields() =
        runTest {
            val csv =
                goodreadsCsv(
                    goodreadsRow(
                        bookId = "1",
                        title = "The Clockwork Atlas",
                        isbn13 = "=\"9780593135204\"",
                        myRating = "5",
                        binding = "Hardcover",
                        numberOfPages = "412",
                        yearPublished = "2026",
                        originalPublicationYear = "1926",
                        dateRead = "2023/06/01",
                        dateAdded = "2022/01/01",
                        bookshelves = "fantasy, adventure",
                        exclusiveShelf = "read",
                        readCount = "2",
                    ),
                    goodreadsRow(
                        bookId = "2",
                        title = "Nebula's Edge",
                        binding = "Paperback",
                        yearPublished = "2015",
                        dateAdded = "2023/03/10",
                        exclusiveShelf = "currently-reading",
                    ),
                    goodreadsRow(
                        bookId = "3",
                        title = "Salt and Ember",
                        exclusiveShelf = "to-read",
                    ),
                )

            val result = useCase.executeGoodreads(csv, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(3, result.data.booksImported)
            assertTrue(result.data.rejections.isEmpty())
            assertEquals(listOf(GoodreadsCsvImporter.NOT_IMPORTED_COLUMNS_NOTICE), result.data.notes)

            val books = bookRepository.observeAllBooksWithDetails().first().associateBy { it.mediaItem.title }
            assertEquals(3, books.size)

            val atlas = books.getValue("The Clockwork Atlas")
            assertEquals(1926, atlas.mediaItem.releaseYear, "Original Publication Year preferred over Year Published")
            assertEquals("9780593135204", atlas.details?.isbn, "Excel-armor stripped")
            assertEquals(BookFormat.HARDCOVER, atlas.details?.format)
            assertEquals(412, atlas.details?.totalPages)
            assertEquals(ReadingStatus.FINISHED, atlas.details?.status)
            assertTrue(atlas.details?.finishedAt != null, "read shelf with a Date Read must produce a finishedAt")
            val atlasIdentifiers =
                bookRepository
                    .observeAllExternalIdentifiers()
                    .first()
                    .filter { it.mediaId == atlas.mediaItem.id }
            assertEquals(
                listOf(IdentifierProvider.ISBN to "9780593135204"),
                atlasIdentifiers.map {
                    it.provider to
                        it.externalId
                },
            )

            val nebula = books.getValue("Nebula's Edge")
            assertEquals(
                2015,
                nebula.mediaItem.releaseYear,
                "Original Publication Year blank -- falls back to Year Published",
            )
            assertEquals(null, nebula.details?.isbn)
            assertEquals(ReadingStatus.READING, nebula.details?.status)
            assertEquals(null, nebula.details?.finishedAt)

            val salt = books.getValue("Salt and Ember")
            assertEquals(null, salt.mediaItem.releaseYear)
            assertEquals(BookFormat.PHYSICAL, salt.details?.format, "blank Binding falls back to PHYSICAL")
            assertEquals(ReadingStatus.TO_READ, salt.details?.status)
        }

    @Test
    fun executeGoodreads_missingTitleColumn_refusesWholeImport_nothingWritten() =
        runTest {
            val csv = "${GoodreadsColumns.ISBN},${GoodreadsColumns.BINDING}\r\n9780000000001,Hardcover\r\n"

            val result = useCase.executeGoodreads(csv, DuplicatePolicy.SKIP)
            assertIs<Resource.Error>(result)
            assertTrue(bookRepository.observeAllBooksWithDetails().first().isEmpty())
        }

    @Test
    fun executeGoodreads_blankTitleRow_isSkippedWithReport_othersStillImport() =
        runTest {
            val csv =
                goodreadsCsv(
                    goodreadsRow(bookId = "1", title = "Valid Goodreads Book"),
                    goodreadsRow(bookId = "2", title = ""),
                )

            val result = useCase.executeGoodreads(csv, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.booksImported)
            assertEquals(1, result.data.rejections.size)
            assertEquals(
                ImportRowSource.BOOK,
                result.data.rejections
                    .single()
                    .source,
            )
            assertTrue(
                bookRepository.observeAllBooksWithDetails().first().any {
                    it.mediaItem.title ==
                        "Valid Goodreads Book"
                },
            )
        }

    @Test
    fun executeGoodreads_reimportSameFile_matchesByIsbnAndSkips() =
        runTest {
            val csv =
                goodreadsCsv(
                    goodreadsRow(
                        bookId = "1",
                        title = "Reimported Book",
                        isbn13 = "=\"9780593135204\"",
                        exclusiveShelf = "read",
                    ),
                )

            val first = useCase.executeGoodreads(csv, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(first)
            assertEquals(1, first.data.booksImported)

            // The user kept goodreads_library_export.csv and re-imports the exact same file later --
            // ROADMAP Task 8's documented recovery path. Even with a fresh mediaId generated for the
            // row both times, the ISBN tier must still recognize this as the same book.
            val second = useCase.executeGoodreads(csv, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(second)
            assertEquals(0, second.data.booksImported)
            assertEquals(1, second.data.booksSkipped)
            assertEquals(
                1,
                bookRepository.observeAllBooksWithDetails().first().size,
                "must not have duplicated the book",
            )
        }

    @Test
    fun executeGoodreads_mergePolicy_reimportBackfillsBlankReleaseYear_neverOverwritesTitle() =
        runTest {
            // Simulates the "keep the file, re-import later" recovery path: the first import has no
            // year data at all for this book; a later export of the same library (Goodreads finally
            // cataloged an Original Publication Year) backfills it via MERGE without disturbing
            // anything else already recorded.
            val firstCsv =
                goodreadsCsv(
                    goodreadsRow(
                        bookId = "1",
                        title = "Backfill Candidate",
                        isbn13 = "=\"9781111111111\"",
                        exclusiveShelf = "to-read",
                    ),
                )
            val first = useCase.executeGoodreads(firstCsv, DuplicatePolicy.MERGE)
            assertIs<Resource.Success<ImportSummary>>(first)
            assertEquals(1, first.data.booksImported)
            assertEquals(
                null,
                bookRepository
                    .observeAllBooksWithDetails()
                    .first()
                    .single()
                    .mediaItem.releaseYear,
            )

            val secondCsv =
                goodreadsCsv(
                    goodreadsRow(
                        bookId = "1",
                        title = "A Different Title Goodreads Might Show", // must NOT overwrite the existing title
                        isbn13 = "=\"9781111111111\"",
                        originalPublicationYear = "1987",
                        exclusiveShelf = "to-read",
                    ),
                )
            val second = useCase.executeGoodreads(secondCsv, DuplicatePolicy.MERGE)
            assertIs<Resource.Success<ImportSummary>>(second)
            assertEquals(1, second.data.booksMerged)

            val merged = bookRepository.observeAllBooksWithDetails().first().single()
            assertEquals("Backfill Candidate", merged.mediaItem.title, "title is identity -- merge must never touch it")
            assertEquals(1987, merged.mediaItem.releaseYear, "releaseYear was null -- merge should backfill it")
        }
}
