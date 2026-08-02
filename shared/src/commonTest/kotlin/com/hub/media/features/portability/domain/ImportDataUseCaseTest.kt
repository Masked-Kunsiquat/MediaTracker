package com.hub.media.features.portability.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleExternalIdentifier
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.sampleReadingSession
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.data.BookWithDetails
import com.hub.media.features.books.data.ReadingSessionRepository
import com.hub.media.features.portability.csv.CSV_SCHEMA_VERSION
import com.hub.media.features.portability.csv.LibraryCsvExporter
import com.hub.media.features.portability.csv.ReadingLogCsvExporter
import com.hub.media.features.portability.data.ImportWriteRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

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
        repository: BookRepository = sourceBookRepository,
    ): String {
        val result = repository.addBook(
            title = title,
            releaseYear = releaseYear,
            purchasePrice = purchasePrice,
            format = format,
            totalPages = totalPages,
            isbn = isbn,
            externalIdentifiers = externalIdentifiers,
            status = status,
        )
        assertIs<Resource.Success<String>>(result)
        return result.data
    }

    private suspend fun exportCurrentSourceDb(): Pair<String, String> {
        val books = sourceBookRepository.observeAllBooksWithDetails().first()
        val identifiers = sourceBookRepository.observeAllExternalIdentifiers().first().groupBy { it.mediaId }
        val sessions = sourceSessionRepository.observeAllSessions().first()
        return LibraryCsvExporter.export(books, identifiers) to ReadingLogCsvExporter.export(sessions)
    }

    @Test
    fun execute_bothFilesNull_returnsError() = runTest {
        val result = useCase.execute(null, null, DuplicatePolicy.SKIP)
        assertIs<Resource.Error>(result)
    }

    @Test
    fun execute_emptyExportFiles_succeedsWithZeroCounts() = runTest {
        val (libraryCsv, logsCsv) = exportCurrentSourceDb() // nothing added yet -- header-only
        val result = useCase.execute(libraryCsv, logsCsv, DuplicatePolicy.SKIP)
        assertIs<Resource.Success<ImportSummary>>(result)
        assertEquals(0, result.data.booksImported)
        assertEquals(0, result.data.sessionsImported)
        assertTrue(result.data.rejections.isEmpty())
    }

    @Test
    fun roundTrip_exportThenImportIntoFreshDatabase_reproducesOriginalData() = runTest {
        val mediaId = addBook(
            title = "Dune, Book One",
            isbn = "9780441013593",
            externalIdentifiers = listOf(
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

    @Test
    fun execute_duplicateByMediaId_skipPolicy_leavesExistingBookUnchanged() = runTest {
        val mediaId = addBook(title = "Original Title", repository = bookRepository)
        val existingBefore = bookRepository.getBookWithDetails(mediaId)

        // A "newer" export of the SAME media_id with different metadata.
        val incomingBooks = listOf(
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
    fun execute_duplicateByMediaId_replacePolicy_overwritesFields() = runTest {
        val mediaId = addBook(title = "Original Title", releaseYear = 2000, repository = bookRepository)

        val incomingBooks = listOf(
            BookWithDetails(
                mediaItem = sampleMediaItem(id = mediaId, title = "Replaced Title", releaseYear = 1999),
                details = sampleBookDetails(mediaId = mediaId, isbn = "9789999999999", format = BookFormat.HARDCOVER),
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
    fun execute_duplicateByMediaId_replacePolicy_neverTouchesCreatedAtOrCoverImageHash() = runTest {
        val mediaId = addBook(title = "Original Title", repository = bookRepository)
        val before = bookRepository.getBookWithDetails(mediaId)!!

        val incomingBooks = listOf(
            BookWithDetails(
                mediaItem = sampleMediaItem(
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
    fun execute_duplicateByMediaId_mergePolicy_backfillsNullFieldsOnly_keepsExistingNonNullValues() = runTest {
        // Existing book already has a purchasePrice/isbn on record; releaseYear is unset.
        val mediaId = addBook(
            title = "Existing Title",
            releaseYear = null,
            purchasePrice = 14.99,
            isbn = "9781111111111",
            repository = bookRepository,
        )

        val incomingBooks = listOf(
            BookWithDetails(
                mediaItem = sampleMediaItem(id = mediaId, title = "Incoming Title", releaseYear = 2015, purchasePrice = 1.00),
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
        assertEquals(14.99, merged?.mediaItem?.purchasePrice, "purchasePrice was already set -- merge must not overwrite it")
        assertEquals("9781111111111", merged?.details?.isbn, "isbn was already set -- merge must not overwrite it")
    }

    @Test
    fun execute_duplicateByMergePolicy_addsNewExternalIdentifierProviderWithoutRemovingExisting() = runTest {
        val mediaId = addBook(title = "Book", isbn = "9781111111111", repository = bookRepository)
        db.externalIdentifierDao().insert(sampleExternalIdentifier(mediaId, IdentifierProvider.ISBN, "9781111111111"))

        val incomingBooks = listOf(
            BookWithDetails(
                mediaItem = sampleMediaItem(id = mediaId, title = "Book"),
                details = sampleBookDetails(mediaId = mediaId, isbn = "9781111111111"),
            ),
        )
        val incomingIdentifiers = mapOf(
            mediaId to listOf(
                sampleExternalIdentifier(mediaId, IdentifierProvider.ISBN, "9789999999999"), // must NOT overwrite
                sampleExternalIdentifier(mediaId, IdentifierProvider.OPEN_LIBRARY, "OL999M"), // must be added
            ),
        )
        val libraryCsv = LibraryCsvExporter.export(incomingBooks, incomingIdentifiers)

        val result = useCase.execute(libraryCsv, null, DuplicatePolicy.MERGE)
        assertIs<Resource.Success<ImportSummary>>(result)

        val identifiers = bookRepository.observeAllExternalIdentifiers().first().filter { it.mediaId == mediaId }
        val byProvider = identifiers.associate { it.provider to it.externalId }
        assertEquals("9781111111111", byProvider[IdentifierProvider.ISBN], "existing ISBN mapping must survive merge untouched")
        assertEquals("OL999M", byProvider[IdentifierProvider.OPEN_LIBRARY], "new provider must be added by merge")
    }

    @Test
    fun execute_duplicateByIsbn_matchesEvenWhenMediaIdDiffers() = runTest {
        val existingMediaId = addBook(title = "Existing", isbn = "9783333333333", repository = bookRepository)

        val incomingBooks = listOf(
            BookWithDetails(
                mediaItem = sampleMediaItem(id = "a-completely-different-media-id", title = "Incoming Copy"),
                details = sampleBookDetails(mediaId = "a-completely-different-media-id", isbn = "9783333333333"),
            ),
        )
        val libraryCsv = LibraryCsvExporter.export(incomingBooks, emptyMap())

        val result = useCase.execute(libraryCsv, null, DuplicatePolicy.SKIP)
        assertIs<Resource.Success<ImportSummary>>(result)
        assertEquals(1, result.data.booksSkipped, "should match the existing book by ISBN despite a different media_id")
        assertEquals(0, result.data.booksImported)

        // No new book was inserted under the "different" media_id.
        assertEquals(null, bookRepository.getBookWithDetails("a-completely-different-media-id"))
        assertTrue(bookRepository.getBookWithDetails(existingMediaId) != null)
    }

    @Test
    fun execute_duplicateByTitleAndYear_matchesWhenNoIsbnOnEitherSide() = runTest {
        val existingMediaId = addBook(
            title = "Foundation",
            releaseYear = 1951,
            isbn = null,
            repository = bookRepository,
        )

        val incomingBooks = listOf(
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

    @Test
    fun execute_orphanSession_isSkippedAndReported_otherRowsStillImport() = runTest {
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
    fun execute_invalidBusinessRuleRow_isSkippedWithReport_othersStillImport() = runTest {
        val validRow = BookWithDetails(
            mediaItem = sampleMediaItem(id = "media-good", title = "Valid Book"),
            details = sampleBookDetails(mediaId = "media-good"),
        )
        val invalidRow = BookWithDetails(
            mediaItem = sampleMediaItem(id = "media-bad", title = ""), // blank title -- fails validation
            details = sampleBookDetails(mediaId = "media-bad"),
        )
        val libraryCsv = LibraryCsvExporter.export(listOf(validRow, invalidRow), emptyMap())

        val result = useCase.execute(libraryCsv, null, DuplicatePolicy.SKIP)
        assertIs<Resource.Success<ImportSummary>>(result)
        assertEquals(1, result.data.booksImported)
        assertEquals(1, result.data.rejections.size)
        assertEquals(ImportRowSource.BOOK, result.data.rejections.single().source)
        assertTrue(result.data.rejections.single().reason.contains("Title"))

        assertTrue(bookRepository.getBookWithDetails("media-good") != null)
        assertTrue(bookRepository.getBookWithDetails("media-bad") == null)
    }

    @Test
    fun execute_wrongColumnCountRow_refusesWholeImport_nothingWritten() = runTest {
        val validRow = BookWithDetails(
            mediaItem = sampleMediaItem(id = "media-good", title = "Valid Book"),
            details = sampleBookDetails(mediaId = "media-good"),
        )
        val wellFormedCsv = LibraryCsvExporter.export(listOf(validRow), emptyMap())
        // Truncate the last line to simulate a structurally corrupted/truncated file.
        val corrupted = wellFormedCsv.trimEnd().substringBeforeLast(",") + "\r\n"

        val result = useCase.execute(corrupted, null, DuplicatePolicy.SKIP)
        assertIs<Resource.Error>(result)

        assertTrue(bookRepository.observeAllBooksWithDetails().first().isEmpty(), "a structurally rejected file must write nothing")
    }

    @Test
    fun execute_newerSchemaVersion_refusesImportEntirely() = runTest {
        val validRow = BookWithDetails(
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
    fun execute_unterminatedQuote_refusesImportEntirely() = runTest {
        val header = LibraryCsvExporter.HEADER.joinToString(",")
        val malformed = "$header\r\n1,media-1,BOOK,\"unterminated,,,,,,,,,,\r\n"

        val result = useCase.execute(malformed, null, DuplicatePolicy.SKIP)
        assertIs<Resource.Error>(result)
        assertTrue(bookRepository.observeAllBooksWithDetails().first().isEmpty())
    }
}
