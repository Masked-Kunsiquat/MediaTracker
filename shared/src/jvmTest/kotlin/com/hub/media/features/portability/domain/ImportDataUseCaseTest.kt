package com.hub.media.features.portability.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.MediaRepository
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleExternalIdentifier
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.sampleReadingSession
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.LogLevel
import com.hub.media.core.util.RecordingLogger
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.media.data.MediaWithDetails
import com.hub.media.features.movies.data.MovieRepository
import com.hub.media.features.portability.csv.CSV_SCHEMA_VERSION
import com.hub.media.features.portability.csv.CsvUtil
import com.hub.media.features.portability.csv.EpisodeCsvExporter
import com.hub.media.features.portability.csv.LibraryCsvExporter
import com.hub.media.features.portability.csv.ReadingLogCsvExporter
import com.hub.media.features.portability.data.ImportWriteRepository
import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.data.TVShowRepository
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
 * Integration tests for [ImportDataUseCase] against a real (in-memory) [AppDatabase].
 * Consolidated and generalized per Issue #67.
 */
class ImportDataUseCaseTest {
    private lateinit var sourceDb: AppDatabase
    private lateinit var sourceBookRepository: BookRepository
    private lateinit var sourceSessionRepository: ReadingSessionRepository

    private lateinit var db: AppDatabase
    private lateinit var mediaRepository: MediaRepository
    private lateinit var bookRepository: BookRepository
    private lateinit var sessionRepository: ReadingSessionRepository
    private lateinit var useCase: ImportDataUseCase

    @BeforeTest
    fun setUp() {
        sourceDb = testAppDatabase()
        sourceBookRepository = BookRepository(sourceDb)
        sourceSessionRepository = ReadingSessionRepository(sourceDb)

        db = testAppDatabase()
        mediaRepository = MediaRepository(db)
        bookRepository = BookRepository(db)
        sessionRepository = ReadingSessionRepository(db)
        useCase = ImportDataUseCase(mediaRepository, bookRepository, sessionRepository, ImportWriteRepository(db))
    }

    @AfterTest
    fun tearDown() {
        sourceDb.close()
        db.close()
    }

    // ---- Logging adoption (ROADMAP Task 15 Phase C) ------------------------------------------

    @Test
    fun execute_withAMalformedHeader_recordsWhyTheImportWasRefused() =
        runTest {
            val recorder = RecordingLogger()
            val useCase =
                ImportDataUseCase(
                    mediaRepository,
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
                    episodesCsv = null,
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
                    mediaRepository,
                    bookRepository,
                    sessionRepository,
                    ImportWriteRepository(db),
                    logger = recorder,
                )

            useCase.execute(libraryCsv = null, readingLogsCsv = null, episodesCsv = null, duplicatePolicy = DuplicatePolicy.SKIP)

            assertEquals(1, recorder.entries.count { it.level == LogLevel.WARN })
            assertFalse(
                recorder.entries.any { it.level == LogLevel.ERROR },
                "nothing failed here -- selecting no file is not an app error",
            )
        }

    @Test
    fun execute_withRejectedRows_summarisesThemWithoutLeakingTheirContents() =
        runTest {
            val recorder = RecordingLogger()
            val useCase =
                ImportDataUseCase(
                    mediaRepository,
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

            val result = useCase.execute(csv, readingLogsCsv = null, episodesCsv = null, duplicatePolicy = DuplicatePolicy.SKIP)

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
            val recorder = RecordingLogger()
            val useCase =
                ImportDataUseCase(
                    mediaRepository,
                    bookRepository,
                    sessionRepository,
                    ImportWriteRepository(db),
                    logger = recorder,
                )
            val rejectedRowCount = 25
            val csv = libraryCsvWithInvalidReleaseYears(rejectedRowCount)

            val result = useCase.execute(csv, readingLogsCsv = null, episodesCsv = null, duplicatePolicy = DuplicatePolicy.SKIP)

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
                    warning.message.contains("MEDIA#$row"),
                    "row #$row is within the logged cap and must be named: ${warning.message}",
                )
            }
            // Row #26 is the 25th (last) data row -- beyond the 20-row cap -- and must NOT be named.
            assertFalse(
                warning.message.contains("MEDIA#26"),
                "a row beyond the logged cap must not be named individually: ${warning.message}",
            )
            assertTrue(warning.message.contains("and 5 more"), "the omitted count must be reported: ${warning.message}")
        }

    @Test
    fun execute_withNoRejections_logsNoRejectionWarning() =
        runTest {
            val recorder = RecordingLogger()
            val useCase =
                ImportDataUseCase(
                    mediaRepository,
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

            val result = useCase.execute(csv, readingLogsCsv = null, episodesCsv = null, duplicatePolicy = DuplicatePolicy.SKIP)

            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(emptyList(), recorder.entries.filter { it.level == LogLevel.WARN })
        }

    @Test
    fun execute_onCompletion_tracesTheOutcomeAtInfoWithoutLibraryContent() =
        runTest {
            val recorder = RecordingLogger()
            val useCase =
                ImportDataUseCase(
                    mediaRepository,
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

            useCase.execute(csv, readingLogsCsv = null, episodesCsv = null, duplicatePolicy = DuplicatePolicy.SKIP)

            val info = recorder.entries.single { it.level == LogLevel.INFO }
            assertEquals("ImportDataUseCase", info.tag)
            assertTrue(info.message.contains("1 imported"), "the counts are the trace: ${info.message}")
            assertFalse(
                info.message.contains("A Title That Must Never Reach The Log"),
                "lifecycle tracing is still bound by the identifier rule",
            )
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

    private fun libraryCsvWithInvalidReleaseYears(count: Int): String {
        val header =
            "csv_schema_version,media_id,type,title,authors,release_year,purchase_price," +
                "created_at,cover_image_hash,isbn,format,total_pages,status,finished_at,tracking_mode," +
                "external_identifiers"
        val rows =
            (1..count).map { i ->
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
        val mediaItems = sourceBookRepository.observeAllBooksWithDetails().first()
        val identifiers = sourceBookRepository.observeAllExternalIdentifiers().first().groupBy { it.mediaId }
        val sessions = sourceSessionRepository.observeAllSessions().first()
        return LibraryCsvExporter.export(mediaItems, identifiers) to ReadingLogCsvExporter.export(sessions)
    }

    @Test
    fun execute_bothFilesNull_returnsError() =
        runTest {
            val result = useCase.execute(null, null, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Error>(result)
        }

    @Test
    fun execute_emptyExportFiles_succeedsWithZeroCounts() =
        runTest {
            val (libraryCsv, logsCsv) = exportCurrentSourceDb()
            val result = useCase.execute(libraryCsv, logsCsv, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(0, result.data.itemsImported)
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
                durationSeconds = null,
                startUnit = 0.0,
                endUnit = 42.0,
                deltaPages = 42,
                notes = "Great start, \"couldn't put it down\"\nSecond line of the note.",
            )

            val (libraryCsv, logsCsv) = exportCurrentSourceDb()

            val result = useCase.execute(libraryCsv, logsCsv, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.itemsImported)
            assertEquals(1, result.data.sessionsImported)
            assertTrue(result.data.rejections.isEmpty())

            val importedMedia = bookRepository.observeAllBooksWithDetails().first()
            val originalMedia = sourceBookRepository.observeAllBooksWithDetails().first()
            assertEquals(originalMedia, importedMedia)

            val importedIdentifiers = bookRepository.observeAllExternalIdentifiers().first().toSet()
            val originalIdentifiers = sourceBookRepository.observeAllExternalIdentifiers().first().toSet()
            assertEquals(originalIdentifiers, importedIdentifiers)

            val importedSessions = sessionRepository.observeAllSessions().first()
            val originalSessions = sourceSessionRepository.observeAllSessions().first()
            assertEquals(originalSessions, importedSessions)
        }

    @Test
    fun roundTrip_bookWithAuthors_reproducesAuthorsFieldExactly() =
        runTest {
            addBook(title = "Multi-Author Anthology", authors = "Ann Sample Author; B. Other Author")
            val (libraryCsv, _) = exportCurrentSourceDb()

            val result = useCase.execute(libraryCsv, null, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.itemsImported)

            val imported = bookRepository.observeAllBooksWithDetails().first().single()
            assertEquals("Ann Sample Author; B. Other Author", imported.details?.authors)
        }

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

            val result = useCase.execute(v1Csv, null, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.itemsImported)
            assertTrue(result.data.rejections.isEmpty())

            val imported = bookRepository.observeAllBooksWithDetails().first().single()
            assertEquals("A Pre-Task-9 Book", imported.item.title)
            assertEquals(null, imported.details?.authors)
            assertEquals("9780000000002", imported.details?.isbn)
            assertEquals(250, imported.details?.totalPages)
        }

    @Test
    fun execute_duplicateByMediaId_skipPolicy_leavesExistingBookUnchanged() =
        runTest {
            val mediaId = addBook(title = "Original Title", repository = bookRepository)
            val existingBefore = getDetails(mediaId)

            val incomingMedia =
                listOf(
                    MediaWithDetails.Book(
                        item = sampleMediaItem(id = mediaId, title = "Changed Title", releaseYear = 1999),
                        details = sampleBookDetails(mediaId = mediaId, isbn = "9789999999999"),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingMedia, emptyMap())

            val result = useCase.execute(libraryCsv, null, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.itemsSkipped)
            assertEquals(0, result.data.itemsImported)

            assertEquals(existingBefore, getDetails(mediaId))
        }

    @Test
    fun execute_duplicateByMediaId_replacePolicy_overwritesFields() =
        runTest {
            val mediaId = addBook(title = "Original Title", releaseYear = 2000, repository = bookRepository)

            val incomingMedia =
                listOf(
                    MediaWithDetails.Book(
                        item = sampleMediaItem(id = mediaId, title = "Replaced Title", releaseYear = 1999),
                        details =
                            sampleBookDetails(
                                mediaId = mediaId,
                                isbn = "9789999999999",
                                format = BookFormat.HARDCOVER,
                            ),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingMedia, emptyMap())

            val result = useCase.execute(libraryCsv, null, null, DuplicatePolicy.REPLACE)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.itemsReplaced)

            val updated = getDetails(mediaId)
            assertEquals("Replaced Title", updated?.item?.title)
            assertEquals(1999, updated?.item?.releaseYear)
            assertEquals("9789999999999", updated?.details?.isbn)
            assertEquals(BookFormat.HARDCOVER, updated?.details?.format)
        }

    @Test
    fun execute_duplicateByMediaId_replacePolicy_neverTouchesCreatedAtOrCoverImageHash() =
        runTest {
            val mediaId = addBook(title = "Original Title", repository = bookRepository)
            val before = getDetails(mediaId)!!

            val incomingMedia =
                listOf(
                    MediaWithDetails.Book(
                        item =
                            sampleMediaItem(
                                id = mediaId,
                                title = "Replaced Title",
                                createdAt = kotlin.time.Instant.fromEpochMilliseconds(1_234_567_890_000),
                                coverImageHash = "some-foreign-hash",
                            ),
                        details = sampleBookDetails(mediaId = mediaId),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingMedia, emptyMap())

            val result = useCase.execute(libraryCsv, null, null, DuplicatePolicy.REPLACE)
            assertIs<Resource.Success<ImportSummary>>(result)

            val after = getDetails(mediaId)
            assertEquals(before.item.createdAt, after?.item?.createdAt)
            assertEquals(before.item.coverImageHash, after?.item?.coverImageHash)
        }

    @Test
    fun execute_duplicateByMediaId_mergePolicy_backfillsNullFieldsOnly_keepsExistingNonNullValues() =
        runTest {
            val mediaId =
                addBook(
                    title = "Existing Title",
                    releaseYear = null,
                    purchasePrice = 14.99,
                    isbn = "9781111111111",
                    repository = bookRepository,
                )

            val incomingMedia =
                listOf(
                    MediaWithDetails.Book(
                        item =
                            sampleMediaItem(
                                id = mediaId,
                                title = "Incoming Title",
                                releaseYear = 2015,
                                purchasePrice = 1.00,
                            ),
                        details = sampleBookDetails(mediaId = mediaId, isbn = "9782222222222"),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingMedia, emptyMap())

            val result = useCase.execute(libraryCsv, null, null, DuplicatePolicy.MERGE)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.itemsMerged)

            val merged = getDetails(mediaId)
            assertEquals("Existing Title", merged?.item?.title)
            assertEquals(2015, merged?.item?.releaseYear)
            assertEquals(14.99, merged?.item?.purchasePrice)
            assertEquals("9781111111111", merged?.details?.isbn)
        }

    @Test
    fun execute_duplicateByMergePolicy_addsNewExternalIdentifierProviderWithoutRemovingExisting() =
        runTest {
            val mediaId = addBook(title = "Book", isbn = "9781111111111", repository = bookRepository)
            db.externalIdentifierDao().insert(
                sampleExternalIdentifier(mediaId, IdentifierProvider.ISBN, "9781111111111"),
            )

            val incomingMedia =
                listOf(
                    MediaWithDetails.Book(
                        item = sampleMediaItem(id = mediaId, title = "Book"),
                        details = sampleBookDetails(mediaId = mediaId, isbn = "9781111111111"),
                    ),
                )
            val incomingIdentifiers =
                mapOf(
                    mediaId to
                        listOf(
                            sampleExternalIdentifier(mediaId, IdentifierProvider.ISBN, "9789999999999"),
                            sampleExternalIdentifier(mediaId, IdentifierProvider.OPEN_LIBRARY, "OL999M"),
                        ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingMedia, incomingIdentifiers)

            val result = useCase.execute(libraryCsv, null, null, DuplicatePolicy.MERGE)
            assertIs<Resource.Success<ImportSummary>>(result)

            val identifiers = bookRepository.observeAllExternalIdentifiers().first().filter { it.mediaId == mediaId }
            val byProvider = identifiers.associate { it.provider to it.externalId }
            assertEquals("9781111111111", byProvider[IdentifierProvider.ISBN])
            assertEquals("OL999M", byProvider[IdentifierProvider.OPEN_LIBRARY])
        }

    @Test
    fun roundTrip_openLibraryWorkKeySurvivesExportAndImport() =
        runTest {
            val mediaId = "media-with-a-work-key"
            val incomingMedia =
                listOf(
                    MediaWithDetails.Book(
                        item = sampleMediaItem(id = mediaId, title = "The Hobbit"),
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
            val libraryCsv = LibraryCsvExporter.export(incomingMedia, incomingIdentifiers)

            val result = useCase.execute(libraryCsv, null, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)

            val byProvider =
                bookRepository
                    .observeAllExternalIdentifiers()
                    .first()
                    .filter { it.mediaId == mediaId }
                    .associate { it.provider to it.externalId }
            assertEquals("/works/OL27482W", byProvider[IdentifierProvider.OPEN_LIBRARY_WORK])
            assertEquals("/books/OL33891995M", byProvider[IdentifierProvider.OPEN_LIBRARY])
        }

    @Test
    fun execute_duplicateByIsbn_matchesEvenWhenMediaIdDiffers() =
        runTest {
            val existingMediaId = addBook(title = "Existing", isbn = "9783333333333", repository = bookRepository)

            val incomingMedia =
                listOf(
                    MediaWithDetails.Book(
                        item = sampleMediaItem(id = "a-completely-different-media-id", title = "Incoming Copy"),
                        details =
                            sampleBookDetails(
                                mediaId = "a-completely-different-media-id",
                                isbn = "9783333333333",
                            ),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingMedia, emptyMap())

            val result = useCase.execute(libraryCsv, null, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.itemsSkipped)
            assertEquals(0, result.data.itemsImported)

            assertEquals(null, getDetails("a-completely-different-media-id"))
            assertTrue(getDetails(existingMediaId) != null)
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

            val incomingMedia =
                listOf(
                    MediaWithDetails.Book(
                        item = sampleMediaItem(id = "different-id", title = "FOUNDATION", releaseYear = 1951),
                        details = sampleBookDetails(mediaId = "different-id", isbn = null),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingMedia, emptyMap())

            val result = useCase.execute(libraryCsv, null, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.itemsSkipped)
            assertTrue(getDetails(existingMediaId) != null)
        }

    @Test
    fun execute_titleOnly_differingReleaseYears_matchesAndReportsReviewNote() =
        runTest {
            val existingMediaId =
                addBook(
                    title = "The Long Way Home",
                    releaseYear = 2026,
                    isbn = "9781111111111",
                    repository = bookRepository,
                )

            val incomingMedia =
                listOf(
                    MediaWithDetails.Book(
                        item =
                            sampleMediaItem(
                                id = "different-id",
                                title = "The Long Way Home",
                                releaseYear = 2016,
                            ),
                        details = sampleBookDetails(mediaId = "different-id", isbn = null),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingMedia, emptyMap())

            val result = useCase.execute(libraryCsv, null, null, DuplicatePolicy.MERGE)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.itemsMerged)
            assertEquals(0, result.data.itemsImported)

            val merged = getDetails(existingMediaId)
            assertEquals(2026, merged?.item?.releaseYear)

            assertEquals(1, result.data.notes.size)
            val note = result.data.notes.single()
            assertTrue(note.contains("The Long Way Home"))
            assertTrue(note.contains("2026"))
            assertTrue(note.contains("2016"))
        }

    @Test
    fun execute_titleOnly_missingIsbnOnOneSide_stillMatchesByTitleWhenYearsDiffer() =
        runTest {
            val existingMediaId =
                addBook(
                    title = "Salt and Ember",
                    releaseYear = 2026,
                    isbn = "9782222222222",
                    repository = bookRepository,
                )

            val incomingMedia =
                listOf(
                    MediaWithDetails.Book(
                        item = sampleMediaItem(id = "goodreads-id", title = "Salt and Ember", releaseYear = 1990),
                        details = sampleBookDetails(mediaId = "goodreads-id", isbn = null),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingMedia, emptyMap())

            val result = useCase.execute(libraryCsv, null, null, DuplicatePolicy.MERGE)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.itemsMerged)
            assertEquals(0, result.data.itemsImported)
            assertEquals(1, result.data.notes.size)

            assertTrue(getDetails(existingMediaId) != null)
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

            val incomingMedia =
                listOf(
                    MediaWithDetails.Book(
                        item = sampleMediaItem(id = "goodreads-id", title = "Nebula's Edge", releaseYear = 1975),
                        details = sampleBookDetails(mediaId = "goodreads-id", isbn = null),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingMedia, emptyMap())

            val result = useCase.execute(libraryCsv, null, null, DuplicatePolicy.MERGE)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.itemsMerged)
            assertEquals(0, result.data.itemsImported)
            assertEquals(1, result.data.notes.size)

            assertTrue(getDetails(existingMediaId) != null)
        }

    @Test
    fun execute_matchingIsbns_matchesOnIsbnTier_regardlessOfDifferingYears_noReviewNote() =
        runTest {
            val existingMediaId =
                addBook(
                    title = "The Long Way Home",
                    releaseYear = 2026,
                    isbn = "9783333333333",
                    repository = bookRepository,
                )

            val incomingMedia =
                listOf(
                    MediaWithDetails.Book(
                        item =
                            sampleMediaItem(
                                id = "different-id",
                                title = "The Long Way Home",
                                releaseYear = 2016,
                            ),
                        details = sampleBookDetails(mediaId = "different-id", isbn = "9783333333333"),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingMedia, emptyMap())

            val result = useCase.execute(libraryCsv, null, null, DuplicatePolicy.MERGE)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.itemsMerged)
            assertEquals(0, result.data.itemsImported)
            assertTrue(result.data.notes.isEmpty())

            assertTrue(getDetails(existingMediaId) != null)
        }

    @Test
    fun execute_inFileDuplicateByMediaId_doesNotAbortImport_skipPolicyKeepsFirstRow() =
        runTest {
            val sharedMediaId = "shared-media-id"
            val incomingMedia =
                listOf(
                    MediaWithDetails.Book(
                        item = sampleMediaItem(id = sharedMediaId, title = "First Row"),
                        details = sampleBookDetails(mediaId = sharedMediaId, isbn = "9781111111111"),
                    ),
                    MediaWithDetails.Book(
                        item = sampleMediaItem(id = sharedMediaId, title = "Second Row (same media_id)"),
                        details = sampleBookDetails(mediaId = sharedMediaId, isbn = "9782222222222"),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingMedia, emptyMap())

            val result = useCase.execute(libraryCsv, null, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.itemsImported)
            assertEquals(1, result.data.itemsSkipped)

            val items = bookRepository.observeAllBooksWithDetails().first()
            assertEquals(1, items.size)
            assertEquals("First Row", items.single().item.title)
        }

    @Test
    fun execute_inFileDuplicateByMediaId_replacePolicy_lastRowInFileWins() =
        runTest {
            val sharedMediaId = "shared-media-id"
            val incomingMedia =
                listOf(
                    MediaWithDetails.Book(
                        item = sampleMediaItem(id = sharedMediaId, title = "First Row", releaseYear = 2000),
                        details = sampleBookDetails(mediaId = sharedMediaId, isbn = "9781111111111"),
                    ),
                    MediaWithDetails.Book(
                        item = sampleMediaItem(id = sharedMediaId, title = "Second Row", releaseYear = 2010),
                        details = sampleBookDetails(mediaId = sharedMediaId, isbn = "9782222222222"),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingMedia, emptyMap())

            val result = useCase.execute(libraryCsv, null, null, DuplicatePolicy.REPLACE)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.itemsImported)
            assertEquals(1, result.data.itemsReplaced)

            val items = bookRepository.observeAllBooksWithDetails().first()
            assertEquals(1, items.size)
            val book = items.single()
            assertEquals("Second Row", book.item.title)
            assertEquals(2010, book.item.releaseYear)
            assertEquals("9782222222222", book.details?.isbn)
        }

    @Test
    fun execute_inFileDuplicateByIsbn_doesNotCreateTwoBooks() =
        runTest {
            val sharedIsbn = "9783333333333"
            val incomingMedia =
                listOf(
                    MediaWithDetails.Book(
                        item = sampleMediaItem(id = "media-1", title = "First Copy"),
                        details = sampleBookDetails(mediaId = "media-1", isbn = sharedIsbn),
                    ),
                    MediaWithDetails.Book(
                        item = sampleMediaItem(id = "media-2", title = "Second Copy"),
                        details = sampleBookDetails(mediaId = "media-2", isbn = sharedIsbn),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingMedia, emptyMap())

            val result = useCase.execute(libraryCsv, null, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.itemsImported)
            assertEquals(1, result.data.itemsSkipped)

            val items = bookRepository.observeAllBooksWithDetails().first()
            assertEquals(1, items.size)
            assertEquals("media-1", items.single().item.id)
            assertEquals(null, getDetails("media-2"))
        }

    @Test
    fun execute_sessionForBook_matchedByIsbn_isNotOrphaned_andRewrittenToMatchedBooksId() =
        runTest {
            val existingMediaId = addBook(title = "Existing Book", isbn = "9784444444444", repository = bookRepository)

            val fileMediaId = "file-own-media-id"
            val incomingMedia =
                listOf(
                    MediaWithDetails.Book(
                        item = sampleMediaItem(id = fileMediaId, title = "Existing Book (re-imported)"),
                        details = sampleBookDetails(mediaId = fileMediaId, isbn = "9784444444444"),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingMedia, emptyMap())

            val session = sampleReadingSession(mediaId = fileMediaId)
            val logsCsv = ReadingLogCsvExporter.export(listOf(session))

            val result = useCase.execute(libraryCsv, logsCsv, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertTrue(result.data.rejections.isEmpty())
            assertEquals(1, result.data.sessionsImported)

            val sessionsOnExistingBook = sessionRepository.observeSessionsForMedia(existingMediaId).first()
            assertEquals(1, sessionsOnExistingBook.size)
            assertEquals(session.id, sessionsOnExistingBook.single().id)

            val sessionsOnFileMediaId = sessionRepository.observeSessionsForMedia(fileMediaId).first()
            assertTrue(sessionsOnFileMediaId.isEmpty())
        }

    @Test
    fun execute_sessionForBook_matchedByTitleAndYear_isNotOrphaned_andRewrittenToMatchedBooksId() =
        runTest {
            val existingMediaId =
                addBook(title = "Foundation", releaseYear = 1951, isbn = null, repository = bookRepository)

            val fileMediaId = "file-own-media-id"
            val incomingMedia =
                listOf(
                    MediaWithDetails.Book(
                        item = sampleMediaItem(id = fileMediaId, title = "FOUNDATION", releaseYear = 1951),
                        details = sampleBookDetails(mediaId = fileMediaId, isbn = null),
                    ),
                )
            val libraryCsv = LibraryCsvExporter.export(incomingMedia, emptyMap())

            val session = sampleReadingSession(mediaId = fileMediaId)
            val logsCsv = ReadingLogCsvExporter.export(listOf(session))

            val result = useCase.execute(libraryCsv, logsCsv, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertTrue(result.data.rejections.isEmpty())
            assertEquals(1, result.data.sessionsImported)

            val sessionsOnExistingBook = sessionRepository.observeSessionsForMedia(existingMediaId).first()
            assertEquals(1, sessionsOnExistingBook.size)
            assertEquals(session.id, sessionsOnExistingBook.single().id)
        }

    @Test
    fun execute_orphanSession_isSkippedAndReported_otherRowsStillImport() =
        runTest {
            val mediaId = addBook(title = "Known Book", repository = bookRepository)

            val knownSession = sampleReadingSession(mediaId = mediaId)
            val orphanSession = sampleReadingSession(mediaId = "no-such-book-id")
            val logsCsv = ReadingLogCsvExporter.export(listOf(knownSession, orphanSession))

            val result = useCase.execute(null, logsCsv, null, DuplicatePolicy.SKIP)
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
                MediaWithDetails.Book(
                    item = sampleMediaItem(id = "media-good", title = "Valid Book"),
                    details = sampleBookDetails(mediaId = "media-good"),
                )
            val invalidRow =
                MediaWithDetails.Book(
                    item = sampleMediaItem(id = "media-bad", title = ""),
                    details = sampleBookDetails(mediaId = "media-bad"),
                )
            val libraryCsv = LibraryCsvExporter.export(listOf(validRow, invalidRow), emptyMap())

            val result = useCase.execute(libraryCsv, null, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.itemsImported)
            assertEquals(1, result.data.rejections.size)
            assertEquals(
                ImportRowSource.MEDIA,
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

            assertTrue(getDetails("media-good") != null)
            assertTrue(getDetails("media-bad") == null)
        }

    @Test
    fun execute_wrongColumnCountRow_refusesWholeImport_nothingWritten() =
        runTest {
            val validRow =
                MediaWithDetails.Book(
                    item = sampleMediaItem(id = "media-good", title = "Valid Book"),
                    details = sampleBookDetails(mediaId = "media-good"),
                )
            val wellFormedCsv = LibraryCsvExporter.export(listOf(validRow), emptyMap())
            val corrupted = wellFormedCsv.trimEnd().substringBeforeLast(",") + "\r\n"

            val result = useCase.execute(corrupted, null, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Error>(result)

            assertTrue(bookRepository.observeAllBooksWithDetails().first().isEmpty())
        }

    @Test
    fun execute_newerSchemaVersion_refusesImportEntirely() =
        runTest {
            val validRow =
                MediaWithDetails.Book(
                    item = sampleMediaItem(id = "media-good", title = "Valid Book"),
                    details = sampleBookDetails(mediaId = "media-good"),
                )
            val wellFormedCsv = LibraryCsvExporter.export(listOf(validRow), emptyMap())
            val bumped = wellFormedCsv.replaceFirst("\r\n${CSV_SCHEMA_VERSION},", "\r\n${CSV_SCHEMA_VERSION + 1},")

            val result = useCase.execute(bumped, null, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Error>(result)
            assertTrue(result.message.contains("newer", ignoreCase = true))
            assertTrue(bookRepository.observeAllBooksWithDetails().first().isEmpty())
        }

    @Test
    fun execute_unterminatedQuote_refusesImportEntirely() =
        runTest {
            val header = LibraryCsvExporter.HEADER.joinToString(",")
            val malformed = "$header\r\n1,media-1,BOOK,\"unterminated,,,,,,,,,,\r\n"

            val result = useCase.execute(malformed, null, null, DuplicatePolicy.SKIP)
            assertIs<Resource.Error>(result)
            assertTrue(bookRepository.observeAllBooksWithDetails().first().isEmpty())
        }

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
            assertEquals(3, result.data.itemsImported)
            assertTrue(result.data.rejections.isEmpty())
            assertEquals(listOf(GoodreadsCsvImporter.NOT_IMPORTED_COLUMNS_NOTICE), result.data.notes)

            val items = bookRepository.observeAllBooksWithDetails().first().associateBy { it.item.title }
            assertEquals(3, items.size)

            val atlas = items.getValue("The Clockwork Atlas")
            assertEquals(1926, atlas.item.releaseYear)
            assertEquals("9780593135204", atlas.details?.isbn)
            assertEquals(BookFormat.HARDCOVER, atlas.details?.format)
            assertEquals(412, atlas.details?.totalPages)
            assertEquals(ReadingStatus.FINISHED, atlas.details?.status)
            assertTrue(atlas.details?.finishedAt != null)

            val nebula = items.getValue("Nebula's Edge")
            assertEquals(2015, nebula.item.releaseYear)
            assertEquals(null, nebula.details?.isbn)
            assertEquals(ReadingStatus.READING, nebula.details?.status)
            assertEquals(null, nebula.details?.finishedAt)

            val salt = items.getValue("Salt and Ember")
            assertEquals(null, salt.item.releaseYear)
            assertEquals(BookFormat.PHYSICAL, salt.details?.format)
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
            assertEquals(1, result.data.itemsImported)
            assertEquals(1, result.data.rejections.size)
            assertEquals(
                ImportRowSource.MEDIA,
                result.data.rejections
                    .single()
                    .source,
            )
            assertTrue(
                bookRepository.observeAllBooksWithDetails().first().any { it.item.title == "Valid Goodreads Book" },
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
            assertEquals(1, first.data.itemsImported)

            val second = useCase.executeGoodreads(csv, DuplicatePolicy.SKIP)
            assertIs<Resource.Success<ImportSummary>>(second)
            assertEquals(0, second.data.itemsImported)
            assertEquals(1, second.data.itemsSkipped)
            assertEquals(1, bookRepository.observeAllBooksWithDetails().first().size)
        }

    @Test
    fun executeGoodreads_mergePolicy_reimportBackfillsBlankReleaseYear_neverOverwritesTitle() =
        runTest {
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
            assertEquals(1, first.data.itemsImported)
            assertEquals(
                null,
                bookRepository
                    .observeAllBooksWithDetails()
                    .first()
                    .single()
                    .item.releaseYear,
            )

            val secondCsv =
                goodreadsCsv(
                    goodreadsRow(
                        bookId = "1",
                        title = "A Different Title Goodreads Might Show",
                        isbn13 = "=\"9781111111111\"",
                        originalPublicationYear = "1987",
                        exclusiveShelf = "to-read",
                    ),
                )
            val second = useCase.executeGoodreads(secondCsv, DuplicatePolicy.MERGE)
            assertIs<Resource.Success<ImportSummary>>(second)
            assertEquals(1, second.data.itemsMerged)

            val merged = bookRepository.observeAllBooksWithDetails().first().single()
            assertEquals("Backfill Candidate", merged.item.title)
            assertEquals(1987, merged.item.releaseYear)
        }

    private suspend fun getDetails(id: String): MediaWithDetails.Book? {
        val result = bookRepository.getBookWithDetails(id)
        assertIs<Resource.Success<MediaWithDetails.Book?>>(result)
        return result.data
    }

    // ---- Issue #106: movies, shows and episodes survive a round trip ---------------------------

    /**
     * **The Definition-of-Done clause #74 failed on, asserted end to end.** Quick-fill a show, tick
     * some episodes, export all three files through the real [ExportDataUseCase], import them into a
     * genuinely empty database, and assert the watched set comes back identical.
     *
     * Deliberately driven through the real exporter rather than a hand-written CSV fixture: the bug
     * this closes was precisely that the two halves disagreed about what the format contained, and a
     * fixture written by hand encodes one person's belief about the format rather than what the app
     * actually writes. A hand-written fixture would have passed against the broken importer.
     */
    @Test
    fun execute_showWithWatchedEpisodes_roundTripsThroughExportIntoAnEmptyDatabase() =
        runTest {
            val sourceShows = TVShowRepository(sourceDb)
            val addResult =
                sourceShows.addShow(
                    title = "Chernobyl",
                    releaseYear = 2019,
                    totalSeasons = 1,
                    seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 5)),
                )
            assertIs<Resource.Success<String>>(addResult)
            val showId = addResult.data
            val sourceEpisodes = sourceShows.observeEpisodes(showId).first()
            assertEquals(5, sourceEpisodes.size, "quick-fill must have generated the episodes")

            // Two watched, three not -- the asymmetry is the point. A bug that marked everything
            // watched, or nothing, would still pass an all-or-nothing fixture.
            sourceEpisodes.filter { it.episodeNumber in 1..2 }.forEach {
                assertIs<Resource.Success<Unit>>(sourceShows.setEpisodeWatched(it.id, watched = true))
            }
            val watchedBefore = sourceShows.observeEpisodes(showId).first().filter { it.watchedAt != null }
            assertEquals(2, watchedBefore.size)

            val exported =
                ExportDataUseCase(
                    MediaRepository(sourceDb),
                    sourceBookRepository,
                    sourceSessionRepository,
                ).execute()
            assertIs<Resource.Success<CsvExportBundle>>(exported)

            val result =
                useCase.execute(
                    libraryCsv = exported.data.libraryCsv,
                    readingLogsCsv = exported.data.readingLogsCsv,
                    episodesCsv = exported.data.episodesCsv,
                    duplicatePolicy = DuplicatePolicy.SKIP,
                )
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(emptyList(), result.data.rejections, "no row should have been rejected")
            assertEquals(1, result.data.itemsImported, "the show itself must import")
            assertEquals(5, result.data.episodesImported, "every episode must import")

            val importedShow = MediaRepository(db).observeAllMediaWithDetails().first().single()
            assertIs<MediaWithDetails.TVShow>(importedShow)
            assertEquals("Chernobyl", importedShow.item.title)
            assertEquals(2019, importedShow.item.releaseYear)
            assertEquals(1, importedShow.details?.totalSeasons)

            val importedEpisodes = MediaRepository(db).observeAllEpisodes().first()
            assertEquals(5, importedEpisodes.size)
            // The watched *set*, keyed by (season, episode) rather than by row id, so this still
            // means something if ids ever stop round-tripping unchanged.
            assertEquals(
                watchedBefore.map { it.seasonNumber to it.episodeNumber }.toSet(),
                importedEpisodes.filter { it.watchedAt != null }.map { it.seasonNumber to it.episodeNumber }.toSet(),
                "exactly the episodes watched in the source must be watched after the import",
            )
            // And the timestamps, not just which rows are non-null: watchedAt is the state and the
            // date in one column, so a round trip preserving "watched" while resetting "when" would
            // still be losing what the user recorded.
            assertEquals(
                watchedBefore.mapNotNull { it.watchedAt }.toSet(),
                importedEpisodes.mapNotNull { it.watchedAt }.toSet(),
                "watched timestamps must survive unchanged",
            )
        }

    /**
     * The film half of the same claim. Narrower than the show test because a film has no
     * one-to-many child table -- what has to survive is the three columns `library_export.csv` has
     * carried since CSV `v3` and nothing could read back.
     */
    @Test
    fun execute_movieInTheLibrary_roundTripsWithItsRuntimeAndWatchState() =
        runTest {
            assertIs<Resource.Success<String>>(
                MovieRepository(sourceDb).addMovie(
                    title = "Arrival",
                    releaseYear = 2016,
                    runtimeMinutes = 116,
                    status = WatchStatus.WATCHED,
                ),
            )
            val sourceMovie = MediaRepository(sourceDb).observeAllMediaWithDetails().first().single()
            assertIs<MediaWithDetails.Movie>(sourceMovie)

            val exported =
                ExportDataUseCase(
                    MediaRepository(sourceDb),
                    sourceBookRepository,
                    sourceSessionRepository,
                ).execute()
            assertIs<Resource.Success<CsvExportBundle>>(exported)

            val result =
                useCase.execute(
                    libraryCsv = exported.data.libraryCsv,
                    readingLogsCsv = null,
                    episodesCsv = null,
                    duplicatePolicy = DuplicatePolicy.SKIP,
                )
            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(emptyList(), result.data.rejections)
            assertEquals(1, result.data.itemsImported)

            val imported = MediaRepository(db).observeAllMediaWithDetails().first().single()
            assertIs<MediaWithDetails.Movie>(imported)
            assertEquals("Arrival", imported.item.title)
            assertEquals(2016, imported.item.releaseYear)
            assertEquals(116, imported.details?.runtimeMinutes)
            assertEquals(WatchStatus.WATCHED, imported.details?.status)
            assertEquals(
                sourceMovie.details?.watchedAt,
                imported.details?.watchedAt,
                "when a film was watched must survive the round trip, not just that it was",
            )
        }

    /**
     * An episodes row whose `media_id` names nothing the import knows about is skipped and
     * reported -- never silently dropped, never fatal. This is the decision #106 asked for,
     * resolved the same way an orphan reading-log row already is.
     */
    @Test
    fun execute_episodeForAnUnknownShow_isSkippedAndReported() =
        runTest {
            val result =
                useCase.execute(
                    libraryCsv = null,
                    readingLogsCsv = null,
                    episodesCsv = episodesCsv(episodeRow(mediaId = "no-such-show")),
                    duplicatePolicy = DuplicatePolicy.SKIP,
                )

            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(0, result.data.episodesImported)
            val rejection = result.data.rejections.single()
            assertEquals(ImportRowSource.EPISODE, rejection.source)
            assertTrue(
                rejection.reason.contains("not a known show"),
                "the reason must say why: ${rejection.reason}",
            )
        }

    /**
     * An episode pointing at a book is a category error rather than an orphan, and is reported as
     * one. `episodes.mediaId` only requires *some* media item, so without this check the row would
     * insert cleanly and then be invisible to every screen -- a silent loss dressed as a success.
     */
    @Test
    fun execute_episodeWhoseMediaIdIsABook_isSkippedAndReported() =
        runTest {
            val bookId = addBook(title = "Not A Show")
            val (libraryCsv, _) = exportCurrentSourceDb()

            val result =
                useCase.execute(
                    libraryCsv = libraryCsv,
                    readingLogsCsv = null,
                    episodesCsv = episodesCsv(episodeRow(mediaId = bookId)),
                    duplicatePolicy = DuplicatePolicy.SKIP,
                )

            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.itemsImported, "the book itself still imports")
            assertEquals(0, result.data.episodesImported)
            val rejection = result.data.rejections.single()
            assertEquals(ImportRowSource.EPISODE, rejection.source)
            assertTrue(
                rejection.reason.contains("is a BOOK, not a TV show"),
                "the reason must name what it actually found: ${rejection.reason}",
            )
            assertEquals(emptyList(), MediaRepository(db).observeAllEpisodes().first())
        }

    /**
     * MERGE must not resurrect watched state. Re-importing a backup taken before the user un-ticked
     * an episode has to leave the device's own view alone -- REPLACE is the policy that exists for
     * "the file wins".
     */
    @Test
    fun execute_mergeOntoAnExistingEpisode_neverOverwritesWatchedState() =
        runTest {
            val shows = TVShowRepository(db)
            val addResult =
                shows.addShow(
                    title = "Chernobyl",
                    totalSeasons = 1,
                    seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 1)),
                )
            assertIs<Resource.Success<String>>(addResult)
            val showId = addResult.data
            val episode = shows.observeEpisodes(showId).first().single()
            // The device says unwatched; the file about to be imported says watched.
            assertEquals(null, episode.watchedAt)

            val result =
                useCase.execute(
                    libraryCsv = null,
                    readingLogsCsv = null,
                    episodesCsv =
                        episodesCsv(
                            episodeRow(
                                episodeId = episode.id,
                                mediaId = showId,
                                watchedAt = "2024-01-01T00:00:00Z",
                                title = "Backfilled Title",
                            ),
                        ),
                    duplicatePolicy = DuplicatePolicy.MERGE,
                )

            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(1, result.data.episodesMerged)

            val after = shows.observeEpisodes(showId).first().single()
            assertEquals(null, after.watchedAt, "MERGE must never mark an episode watched")
            // It does still backfill what was genuinely unknown, or it would not be a merge at all.
            assertEquals("Backfilled Title", after.title)
        }

    /**
     * A `v4` episodes file -- one exported before CSV `v5` appended the four columns PR #86 added --
     * must still import rather than being rejected as the wrong width.
     */
    @Test
    fun execute_legacyV4EpisodesFile_stillImports() =
        runTest {
            val shows = TVShowRepository(db)
            val addResult =
                shows.addShow(
                    title = "Chernobyl",
                    totalSeasons = 1,
                    seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 1)),
                )
            assertIs<Resource.Success<String>>(addResult)
            val showId = addResult.data
            val existing = shows.observeEpisodes(showId).first().single()

            val v4Csv =
                buildString {
                    append(CsvUtil.buildLine(EpisodeCsvExporter.HEADER_V4))
                    append(
                        CsvUtil.buildLine(
                            listOf("4", "fresh-episode", showId, "1", "2", "Vichnaya Pamyat", "", ""),
                        ),
                    )
                }

            val result =
                useCase.execute(
                    libraryCsv = null,
                    readingLogsCsv = null,
                    episodesCsv = v4Csv,
                    duplicatePolicy = DuplicatePolicy.SKIP,
                )

            assertIs<Resource.Success<ImportSummary>>(result)
            assertEquals(emptyList(), result.data.rejections)
            assertEquals(1, result.data.episodesImported)
            val imported = shows.observeEpisodes(showId).first()
            assertEquals(2, imported.size, "the v4 row must join the one already there")
            assertEquals("Vichnaya Pamyat", imported.single { it.id != existing.id }.title)
        }

    private fun episodeRow(
        episodeId: String = "episode-1",
        mediaId: String = "show-1",
        seasonNumber: String = "1",
        episodeNumber: String = "1",
        title: String = "1:23:45",
        airDate: String = "",
        watchedAt: String = "",
        runtimeMinutes: String = "",
        overview: String = "",
        stillImageHash: String = "",
        communityRating: String = "",
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

    private fun episodesCsv(vararg rows: List<String>): String =
        buildString {
            append(CsvUtil.buildLine(EpisodeCsvExporter.HEADER))
            rows.forEach { append(CsvUtil.buildLine(it)) }
        }
}
