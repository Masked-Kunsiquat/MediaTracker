package com.hub.media.features.books.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleExternalIdentifier
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class BookRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: BookRepository

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        repo = BookRepository(db)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun addBook_happyPath_insertsAllRows() = runTest {
        val result = repo.addBook(
            title = "The Pragmatic Programmer",
            releaseYear = 2019,
            purchasePrice = 49.95,
            format = BookFormat.PHYSICAL,
            totalPages = 352,
            isbn = "9780135957059",
        )

        assertIs<Resource.Success<String>>(result)
        val mediaId = result.data

        // Verify media item exists
        val mediaItem = db.mediaItemDao().getById(mediaId)
        assertEquals("The Pragmatic Programmer", mediaItem?.title)
        assertEquals(MediaType.BOOK, mediaItem?.type)
        assertEquals(2019, mediaItem?.releaseYear)
        assertEquals(49.95, mediaItem?.purchasePrice)

        // Verify book details exist
        val bookDetails = db.bookDetailsDao().getByMediaId(mediaId)
        assertEquals(mediaId, bookDetails?.mediaId)
        assertEquals("9780135957059", bookDetails?.isbn)
        assertEquals(BookFormat.PHYSICAL, bookDetails?.format)
        assertEquals(352, bookDetails?.totalPages)
    }

    @Test
    fun addBook_withExternalIdentifiers_insertsAll() = runTest {
        val result = repo.addBook(
            title = "Clean Code",
            releaseYear = 2008,
            purchasePrice = 44.99,
            format = BookFormat.PHYSICAL,
            totalPages = 464,
            isbn = "9780132350884",
            externalIdentifiers = listOf(
                IdentifierProvider.ISBN to "9780132350884",
                IdentifierProvider.GOOGLE_BOOKS to "google-books-id-123",
            ),
        )

        assertIs<Resource.Success<String>>(result)
        val mediaId = result.data

        val allIdentifiers = db.externalIdentifierDao().observeForMedia(mediaId).first()
        assertEquals(2, allIdentifiers.size)
        assertTrue(allIdentifiers.any { it.provider == IdentifierProvider.ISBN })
        assertTrue(allIdentifiers.any { it.provider == IdentifierProvider.GOOGLE_BOOKS })
    }

    @Test
    fun addBook_duplicateProviderPair_rollsBackWholeTransaction() = runTest {
        // Two identifiers with the SAME provider violate the (mediaId, provider) composite
        // primary key. BookWriteDao inserts with OnConflictStrategy.ABORT inside a
        // @Transaction method, so the second identifier insert throws mid-transaction and
        // Room rolls back the MediaItem, BookDetails, and first-identifier inserts.
        val result = repo.addBook(
            title = "Rollback Book",
            format = BookFormat.PHYSICAL,
            isbn = "9780000000001",
            externalIdentifiers = listOf(
                IdentifierProvider.ISBN to "9780000000001",
                IdentifierProvider.ISBN to "9780000000002", // duplicate provider -> PK violation
            ),
        )

        assertIs<Resource.Error>(result)

        // Full rollback: NO partial rows may remain anywhere.
        val allMediaItems = db.mediaItemDao().observeAll().first()
        assertTrue(allMediaItems.isEmpty(), "MediaItem row must be rolled back")

        val allDetails = db.bookDetailsDao().observeAll().first()
        assertTrue(allDetails.isEmpty(), "BookDetails row must be rolled back")

        val allIdentifiers = db.externalIdentifierDao().observeAll().first()
        assertTrue(allIdentifiers.isEmpty(), "ExternalIdentifier rows must be rolled back")
    }

    @Test
    fun observeBook_emitsBookThenNullAfterDelete() = runTest {
        val result = repo.addBook(title = "Observed Book", format = BookFormat.EBOOK)
        assertIs<Resource.Success<String>>(result)
        val mediaId = result.data

        val observed = repo.observeBook(mediaId).first()
        assertEquals("Observed Book", observed?.title)

        repo.deleteBook(mediaId)
        val afterDelete = repo.observeBook(mediaId).first { it == null }
        assertEquals(null, afterDelete)
    }

    @Test
    fun observeAllBooks_emitsAfterInsert() = runTest {
        val initialBooks = db.mediaItemDao().observeByType(MediaType.BOOK).first()
        assertTrue(initialBooks.isEmpty())

        repo.addBook(
            title = "Refactoring",
            releaseYear = 2018,
            purchasePrice = 0.0,
            format = BookFormat.EBOOK,
        )

        val booksAfterInsert = db.mediaItemDao().observeByType(MediaType.BOOK).first { it.isNotEmpty() }
        assertEquals(1, booksAfterInsert.size)
        assertEquals("Refactoring", booksAfterInsert[0].title)
    }

    @Test
    fun deleteBook_cascadesAllData() = runTest {
        val mediaId = newId()
        val mediaItem = sampleMediaItem(id = mediaId, type = MediaType.BOOK)
        val bookDetails = sampleBookDetails(mediaId)
        val externalId = sampleExternalIdentifier(mediaId)

        db.mediaItemDao().insert(mediaItem)
        db.bookDetailsDao().insert(bookDetails)
        db.externalIdentifierDao().insert(externalId)

        val result = repo.deleteBook(mediaId)
        assertIs<Resource.Success<Unit>>(result)

        // Verify cascade delete removed all rows
        assertEquals(null, db.mediaItemDao().getById(mediaId))
        assertEquals(null, db.bookDetailsDao().getByMediaId(mediaId))
        val remainingIds = db.externalIdentifierDao().observeForMedia(mediaId).first()
        assertTrue(remainingIds.isEmpty())
    }

    @Test
    fun observeBookDetail_emitsMediaItemAndDetailsForInsertedBook() = runTest {
        val result = repo.addBook(
            title = "Project Hail Mary",
            releaseYear = 2021,
            purchasePrice = 27.99,
            format = BookFormat.PHYSICAL,
            totalPages = 496,
            isbn = "9780593135204",
        )
        assertIs<Resource.Success<String>>(result)
        val mediaId = result.data

        val detail = repo.observeBookDetail(mediaId).first { it != null }
        assertEquals("Project Hail Mary", detail?.mediaItem?.title)
        assertEquals(2021, detail?.mediaItem?.releaseYear)
        assertEquals(mediaId, detail?.details?.mediaId)
        assertEquals("9780593135204", detail?.details?.isbn)
        assertEquals(496, detail?.details?.totalPages)
    }

    @Test
    fun observeBookDetail_emitsNullAfterDelete() = runTest {
        val result = repo.addBook(title = "Deleted Detail Book", format = BookFormat.EBOOK)
        assertIs<Resource.Success<String>>(result)
        val mediaId = result.data

        repo.observeBookDetail(mediaId).first { it != null }

        repo.deleteBook(mediaId)

        val afterDelete = repo.observeBookDetail(mediaId).first { it == null }
        assertEquals(null, afterDelete)
    }

    @Test
    fun observeBookDetail_mediaItemWithNoBookDetailsRow_emitsDetailsNull() = runTest {
        // Bypass repo.addBook's atomic transaction (which always inserts both rows together) by
        // inserting only a MediaItemEntity via the DAO directly. This is the data-integrity edge
        // case called out in observeBookDetail's KDoc: BookWithDetails.details is independently
        // nullable for exactly this "row exists but its detail relation doesn't" scenario.
        val mediaId = newId()
        db.mediaItemDao().insert(sampleMediaItem(id = mediaId, type = MediaType.BOOK, title = "Orphan Media Item"))

        val detail = repo.observeBookDetail(mediaId).first { it != null }
        assertEquals("Orphan Media Item", detail?.mediaItem?.title)
        assertEquals(null, detail?.details)
    }

    @Test
    fun updateBookMetadata_happyPath_updatesBothTablesAndObserveBookDetailEmitsNewValues() = runTest {
        val addResult = repo.addBook(
            title = "Original Title",
            releaseYear = 2019,
            purchasePrice = 49.95,
            format = BookFormat.PHYSICAL,
            totalPages = 384,
            isbn = "9780593135204",
        )
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data

        val result = repo.updateBookMetadata(
            mediaId = mediaId,
            title = "Corrected Title",
            releaseYear = 2020,
            purchasePrice = 12.5,
            totalPages = 366,
            format = BookFormat.HARDCOVER,
            status = ReadingStatus.READING,
            trackingMode = TrackingMode.PERCENT,
        )
        assertIs<Resource.Success<Unit>>(result)

        val mediaItem = db.mediaItemDao().getById(mediaId)
        assertEquals("Corrected Title", mediaItem?.title)
        assertEquals(2020, mediaItem?.releaseYear)
        assertEquals(12.5, mediaItem?.purchasePrice)

        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertEquals(366, details?.totalPages)
        assertEquals(BookFormat.HARDCOVER, details?.format)
        assertEquals(ReadingStatus.READING, details?.status)
        assertEquals(TrackingMode.PERCENT, details?.trackingMode)
        // ISBN is not editable in this phase -- must be untouched.
        assertEquals("9780593135204", details?.isbn)

        val detail = repo.observeBookDetail(mediaId).first {
            it?.mediaItem?.title == "Corrected Title"
        }
        assertEquals(2020, detail?.mediaItem?.releaseYear)
        assertEquals(366, detail?.details?.totalPages)
        assertEquals(BookFormat.HARDCOVER, detail?.details?.format)
    }

    @Test
    fun updateBookMetadata_nullTotalPages_isValidAndPersists() = runTest {
        val addResult = repo.addBook(title = "Some Book", format = BookFormat.PHYSICAL, totalPages = 200)
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data

        val result = repo.updateBookMetadata(
            mediaId = mediaId,
            title = "Some Book",
            totalPages = null,
            format = BookFormat.EBOOK,
            status = ReadingStatus.TO_READ,
            trackingMode = TrackingMode.PERCENT,
        )
        assertIs<Resource.Success<Unit>>(result)

        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertEquals(null, details?.totalPages)
        assertEquals(BookFormat.EBOOK, details?.format)
    }

    @Test
    fun updateBookMetadata_blankTitle_rejectedAndPersistsNothing() = runTest {
        val addResult = repo.addBook(
            title = "Untouched Title",
            totalPages = 100,
            format = BookFormat.PHYSICAL,
        )
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data

        val result = repo.updateBookMetadata(
            mediaId = mediaId,
            title = "   ",
            totalPages = 999,
            format = BookFormat.EBOOK,
            status = ReadingStatus.TO_READ,
            trackingMode = TrackingMode.PAGES,
        )
        assertIs<Resource.Error>(result)

        val mediaItem = db.mediaItemDao().getById(mediaId)
        assertEquals("Untouched Title", mediaItem?.title)
        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertEquals(100, details?.totalPages)
        assertEquals(BookFormat.PHYSICAL, details?.format)
    }

    @Test
    fun updateBookMetadata_negativePurchasePrice_rejectedAndPersistsNothing() = runTest {
        val addResult = repo.addBook(title = "Book", purchasePrice = 10.0, format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data

        val result = repo.updateBookMetadata(
            mediaId = mediaId,
            title = "Book",
            purchasePrice = -0.01,
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.TO_READ,
            trackingMode = TrackingMode.PERCENT,
        )
        assertIs<Resource.Error>(result)

        val mediaItem = db.mediaItemDao().getById(mediaId)
        assertEquals(10.0, mediaItem?.purchasePrice)
    }

    @Test
    fun updateBookMetadata_nonFinitePurchasePrice_rejectedAndPersistsNothing() = runTest {
        // Double.NaN satisfies `purchasePrice < 0.0 == false` (IEEE 754), so a plain negativity
        // check alone would silently let it through -- see BookMetadataValidation.validatePurchasePrice's
        // KDoc. This is directly reachable from the manual edit form too: EditBookScreen only gates
        // its Save button on `parsedPurchasePrice >= 0.0`, which typing "Infinity" also satisfies.
        val addResult = repo.addBook(title = "Book", purchasePrice = 10.0, format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data

        val result = repo.updateBookMetadata(
            mediaId = mediaId,
            title = "Book",
            purchasePrice = Double.NaN,
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.TO_READ,
            trackingMode = TrackingMode.PERCENT,
        )
        assertIs<Resource.Error>(result)

        val mediaItem = db.mediaItemDao().getById(mediaId)
        assertEquals(10.0, mediaItem?.purchasePrice)
    }

    @Test
    fun updateBookMetadata_zeroTotalPages_rejectedAndPersistsNothing() = runTest {
        val addResult = repo.addBook(title = "Book", totalPages = 250, format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data

        val result = repo.updateBookMetadata(
            mediaId = mediaId,
            title = "Book",
            totalPages = 0,
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.TO_READ,
            trackingMode = TrackingMode.PAGES,
        )
        assertIs<Resource.Error>(result)

        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertEquals(250, details?.totalPages)
    }

    @Test
    fun updateBookMetadata_negativeTotalPages_rejectedAndPersistsNothing() = runTest {
        val addResult = repo.addBook(title = "Book", totalPages = 250, format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data

        val result = repo.updateBookMetadata(
            mediaId = mediaId,
            title = "Book",
            totalPages = -5,
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.TO_READ,
            trackingMode = TrackingMode.PAGES,
        )
        assertIs<Resource.Error>(result)

        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertEquals(250, details?.totalPages)
    }

    @Test
    fun updateBookMetadata_releaseYearTooLow_rejectedAndPersistsNothing() = runTest {
        val addResult = repo.addBook(title = "Book", releaseYear = 2000, format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data

        val result = repo.updateBookMetadata(
            mediaId = mediaId,
            title = "Book",
            releaseYear = 1000,
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.TO_READ,
            trackingMode = TrackingMode.PERCENT,
        )
        assertIs<Resource.Error>(result)

        val mediaItem = db.mediaItemDao().getById(mediaId)
        assertEquals(2000, mediaItem?.releaseYear)
    }

    @Test
    fun updateBookMetadata_releaseYearTooHigh_rejectedAndPersistsNothing() = runTest {
        val addResult = repo.addBook(title = "Book", releaseYear = 2000, format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data

        val result = repo.updateBookMetadata(
            mediaId = mediaId,
            title = "Book",
            releaseYear = 9999,
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.TO_READ,
            trackingMode = TrackingMode.PERCENT,
        )
        assertIs<Resource.Error>(result)

        val mediaItem = db.mediaItemDao().getById(mediaId)
        assertEquals(2000, mediaItem?.releaseYear)
    }

    @Test
    fun updateBookMetadata_unknownMediaId_returnsError() = runTest {
        val result = repo.updateBookMetadata(
            mediaId = newId(),
            title = "Any Title",
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.TO_READ,
            trackingMode = TrackingMode.PERCENT,
        )
        assertIs<Resource.Error>(result)
        assertTrue(result.message.contains("not found"))
    }

    @Test
    fun updateBookMetadata_noExistingBookDetailsRow_insertsNewRowAndUpdatesMediaItem() = runTest {
        // Data-integrity edge case (see observeBookDetail_mediaItemWithNoBookDetailsRow_emitsDetailsNull
        // above): a MediaItemEntity with no BookDetailsEntity row, bypassing addBook's atomic insert.
        val mediaId = newId()
        db.mediaItemDao().insert(sampleMediaItem(id = mediaId, type = MediaType.BOOK, title = "Orphan Item"))
        assertEquals(null, db.bookDetailsDao().getByMediaId(mediaId))

        val result = repo.updateBookMetadata(
            mediaId = mediaId,
            title = "Repaired Title",
            totalPages = 150,
            format = BookFormat.PAPERBACK,
            status = ReadingStatus.READING,
            trackingMode = TrackingMode.PAGES,
        )
        assertIs<Resource.Success<Unit>>(result)

        val mediaItem = db.mediaItemDao().getById(mediaId)
        assertEquals("Repaired Title", mediaItem?.title)

        // The missing row is self-healed: created with the given format/totalPages/trackingMode
        // and a null isbn.
        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertEquals(mediaId, details?.mediaId)
        assertEquals(null, details?.isbn)
        assertEquals(150, details?.totalPages)
        assertEquals(BookFormat.PAPERBACK, details?.format)
        assertEquals(ReadingStatus.READING, details?.status)
        assertEquals(TrackingMode.PAGES, details?.trackingMode)
    }

    @Test
    fun updateBookMetadata_transitionToFinished_stampsFinishedAt() = runTest {
        val addResult = repo.addBook(title = "Finish Me", format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data

        val result = repo.updateBookMetadata(
            mediaId = mediaId,
            title = "Finish Me",
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.FINISHED,
            trackingMode = TrackingMode.PERCENT,
        )
        assertIs<Resource.Success<Unit>>(result)

        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertEquals(ReadingStatus.FINISHED, details?.status)
        assertTrue(details?.finishedAt != null, "finishedAt must be stamped on the FINISHED transition")
    }

    @Test
    fun updateBookMetadata_reSavingAlreadyFinished_preservesOriginalFinishedAt() = runTest {
        val addResult = repo.addBook(title = "Already Finished", format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data
        repo.updateBookMetadata(
            mediaId = mediaId,
            title = "Already Finished",
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.FINISHED,
            trackingMode = TrackingMode.PERCENT,
        )
        val firstFinishedAt = db.bookDetailsDao().getByMediaId(mediaId)?.finishedAt

        val result = repo.updateBookMetadata(
            mediaId = mediaId,
            title = "Already Finished (edited)",
            format = BookFormat.HARDCOVER,
            status = ReadingStatus.FINISHED,
            trackingMode = TrackingMode.PERCENT,
        )
        assertIs<Resource.Success<Unit>>(result)

        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertEquals(firstFinishedAt, details?.finishedAt, "re-saving while FINISHED must not bump finishedAt")
    }

    @Test
    fun updateBookMetadata_transitionAwayFromFinished_clearsFinishedAt() = runTest {
        val addResult = repo.addBook(title = "Reopened", format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data
        repo.updateBookMetadata(
            mediaId = mediaId,
            title = "Reopened",
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.FINISHED,
            trackingMode = TrackingMode.PERCENT,
        )

        val result = repo.updateBookMetadata(
            mediaId = mediaId,
            title = "Reopened",
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.READING,
            trackingMode = TrackingMode.PERCENT,
        )
        assertIs<Resource.Success<Unit>>(result)

        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertEquals(ReadingStatus.READING, details?.status)
        assertEquals(null, details?.finishedAt, "moving away from FINISHED must clear finishedAt")
    }

    @Test
    fun updateReadingStatus_happyPath_updatesStatusOnly() = runTest {
        val addResult = repo.addBook(title = "Quick Status Book", format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data

        val result = repo.updateReadingStatus(mediaId, ReadingStatus.READING)
        assertIs<Resource.Success<Unit>>(result)

        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertEquals(ReadingStatus.READING, details?.status)
        assertEquals(null, details?.finishedAt)
    }

    @Test
    fun updateBookMetadata_transitionToFinished_usesInjectedClockNotSystemClock() = runTest {
        // Finding #5: BookRepository must derive the FINISHED-transition timestamp from its
        // injected Clock rather than calling Clock.System.now() directly, so this must be
        // provable with a fake Clock pinned to a value far from the real wall clock.
        val fixedInstant = Instant.fromEpochMilliseconds(1_000_000_000_000) // 2001-09-09, not "now"
        val fixedClock = object : Clock {
            override fun now(): Instant = fixedInstant
        }
        val repoWithFixedClock = BookRepository(db, fixedClock)

        val addResult = repoWithFixedClock.addBook(title = "Clocked Book", format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data

        val result = repoWithFixedClock.updateBookMetadata(
            mediaId = mediaId,
            title = "Clocked Book",
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.FINISHED,
            trackingMode = TrackingMode.PERCENT,
        )
        assertIs<Resource.Success<Unit>>(result)

        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertEquals(fixedInstant, details?.finishedAt, "finishedAt must come from the injected Clock")
    }

    @Test
    fun updateReadingStatus_toFinished_stampsFinishedAt() = runTest {
        val addResult = repo.addBook(title = "Quick Finish Book", format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data

        val result = repo.updateReadingStatus(mediaId, ReadingStatus.FINISHED)
        assertIs<Resource.Success<Unit>>(result)

        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertEquals(ReadingStatus.FINISHED, details?.status)
        assertTrue(details?.finishedAt != null)
    }

    @Test
    fun updateReadingStatus_unknownMediaId_returnsError() = runTest {
        val result = repo.updateReadingStatus(newId(), ReadingStatus.READING)
        assertIs<Resource.Error>(result)
    }

    // Partial-failure atomicity: addBook_duplicateProviderPair_rollsBackWholeTransaction (above)
    // forces a mid-transaction failure via a genuine uniqueness-constraint violation (a duplicate
    // (mediaId, provider) composite key among externalIdentifiers). updateBookMetadata's two writes
    // have no analogous independently-triggerable constraint: the targeted media_items UPDATE
    // either affects the one already-existing row for mediaId or (if it doesn't exist) short-
    // circuits before the book_details write is attempted at all, and the book_details write
    // either updates that same already-valid row or self-heal-inserts a new one whose FK (mediaId)
    // is that same already-existing MediaItemEntity -- there is no reachable input that makes one
    // write satisfy its constraints while the other doesn't, so a forced-rollback test analogous to
    // the addBook one isn't constructible without a mocking framework (which this project's
    // kotlin.test-only test stack doesn't use). Atomicity here is enforced structurally by
    // BookWriteDao.updateBookMetadataAtomically's @Transaction default-body pattern (same mechanism
    // insertBookAtomically uses, which *is* covered by a forced-rollback test).

    @Test
    fun addBook_duplicateIsbn_doesNotCrash() = runTest {
        // First book
        val result1 = repo.addBook(
            title = "Book One",
            format = BookFormat.PHYSICAL,
            isbn = "9780000000000",
        )
        assertIs<Resource.Success<String>>(result1)

        // Second book with same ISBN should also succeed (ISBN is not unique in media_items)
        val result2 = repo.addBook(
            title = "Book Two",
            format = BookFormat.EBOOK,
            isbn = "9780000000000",
        )
        assertIs<Resource.Success<String>>(result2)

        // Both books exist
        val allBooks = db.mediaItemDao().observeByType(MediaType.BOOK).first()
        assertEquals(2, allBooks.size)
    }

    // ==========================================================================================
    // TrackingMode (ROADMAP Task 7 Phase A): schema v4's explicit pages-vs-percent field,
    // replacing the old totalPages != null inference. See BookDetailsEntity.trackingMode's and
    // TrackingMode's KDoc for the full rationale.
    // ==========================================================================================

    @Test
    fun addBook_knownTotalPages_defaultsTrackingModeToPages() = runTest {
        val result = repo.addBook(title = "Paged Book", format = BookFormat.PHYSICAL, totalPages = 300)
        assertIs<Resource.Success<String>>(result)

        val details = db.bookDetailsDao().getByMediaId(result.data)
        assertEquals(TrackingMode.PAGES, details?.trackingMode)
    }

    @Test
    fun addBook_unknownTotalPages_defaultsTrackingModeToPercent() = runTest {
        val result = repo.addBook(title = "Percent Book", format = BookFormat.EBOOK)
        assertIs<Resource.Success<String>>(result)

        val details = db.bookDetailsDao().getByMediaId(result.data)
        assertEquals(null, details?.totalPages)
        assertEquals(TrackingMode.PERCENT, details?.trackingMode)
    }

    @Test
    fun addBook_explicitTrackingModeOverridesTotalPagesDerivedDefault() = runTest {
        // A book can legitimately have both a known page count AND be tracked by percent (e.g. an
        // ebook whose page count is known from the print edition but whose reader only reports
        // percent) -- trackingMode is fully independent of totalPages once set explicitly.
        val result = repo.addBook(
            title = "Explicit Percent Despite Pages",
            format = BookFormat.EBOOK,
            totalPages = 300,
            trackingMode = TrackingMode.PERCENT,
        )
        assertIs<Resource.Success<String>>(result)

        val details = db.bookDetailsDao().getByMediaId(result.data)
        assertEquals(300, details?.totalPages)
        assertEquals(TrackingMode.PERCENT, details?.trackingMode)
    }

    @Test
    fun updateBookMetadata_trackingModePages_persistsAndReadsBack() = runTest {
        val addResult = repo.addBook(title = "Mode Switch Book", format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data
        // Sanity: no totalPages given, so the default derivation landed on PERCENT.
        assertEquals(TrackingMode.PERCENT, db.bookDetailsDao().getByMediaId(mediaId)?.trackingMode)

        val result = repo.updateBookMetadata(
            mediaId = mediaId,
            title = "Mode Switch Book",
            totalPages = 250,
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.TO_READ,
            trackingMode = TrackingMode.PAGES,
        )
        assertIs<Resource.Success<Unit>>(result)

        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertEquals(TrackingMode.PAGES, details?.trackingMode)

        val detail = repo.observeBookDetail(mediaId).first { it?.details?.trackingMode == TrackingMode.PAGES }
        assertEquals(TrackingMode.PAGES, detail?.details?.trackingMode)
    }

    @Test
    fun updateBookMetadata_trackingModePercent_persistsIndependentlyOfTotalPages() = runTest {
        // The whole point of this phase: trackingMode no longer flips as a side effect of editing
        // totalPages. A book with a known page count can still be explicitly set to PERCENT.
        val addResult = repo.addBook(title = "Independent Fields Book", format = BookFormat.PHYSICAL, totalPages = 400)
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data
        assertEquals(TrackingMode.PAGES, db.bookDetailsDao().getByMediaId(mediaId)?.trackingMode)

        val result = repo.updateBookMetadata(
            mediaId = mediaId,
            title = "Independent Fields Book",
            totalPages = 400,
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.TO_READ,
            trackingMode = TrackingMode.PERCENT,
        )
        assertIs<Resource.Success<Unit>>(result)

        val details = db.bookDetailsDao().getByMediaId(mediaId)
        assertEquals(400, details?.totalPages, "totalPages must be untouched by a trackingMode-only intent")
        assertEquals(TrackingMode.PERCENT, details?.trackingMode)
    }

    // ---- applyBackfilledMetadata / getAllBooksWithDetails (ROADMAP Task 14 Phase A) -----------

    @Test
    fun applyBackfilledMetadata_writesCoverAndAuthorsAtomically() = runTest {
        val addResult = repo.addBook(title = "Backfill Target", format = BookFormat.PHYSICAL, isbn = "9780000000001")
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data

        val result = repo.applyBackfilledMetadata(mediaId, coverImageHash = "abc123.jpg", authors = "Ada Lovelace")
        assertIs<Resource.Success<Unit>>(result)

        assertEquals("abc123.jpg", db.mediaItemDao().getById(mediaId)?.coverImageHash)
        assertEquals("Ada Lovelace", db.bookDetailsDao().getByMediaId(mediaId)?.authors)
    }

    @Test
    fun applyBackfilledMetadata_coverOnly_leavesAuthorsUntouched() = runTest {
        val addResult = repo.addBook(title = "Cover Only Book", format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data
        // Pre-existing author on record must survive a cover-only backfill write.
        db.bookDetailsDao().update(db.bookDetailsDao().getByMediaId(mediaId)!!.copy(authors = "Existing Author"))

        val result = repo.applyBackfilledMetadata(mediaId, coverImageHash = "newcover.jpg", authors = null)
        assertIs<Resource.Success<Unit>>(result)

        assertEquals("newcover.jpg", db.mediaItemDao().getById(mediaId)?.coverImageHash)
        assertEquals("Existing Author", db.bookDetailsDao().getByMediaId(mediaId)?.authors)
    }

    @Test
    fun applyBackfilledMetadata_authorsOnly_leavesCoverUntouched() = runTest {
        val addResult = repo.addBook(
            title = "Authors Only Book",
            format = BookFormat.PHYSICAL,
            coverImageHash = "existing-cover.jpg",
        )
        assertIs<Resource.Success<String>>(addResult)
        val mediaId = addResult.data

        val result = repo.applyBackfilledMetadata(mediaId, coverImageHash = null, authors = "New Author")
        assertIs<Resource.Success<Unit>>(result)

        assertEquals("existing-cover.jpg", db.mediaItemDao().getById(mediaId)?.coverImageHash)
        assertEquals("New Author", db.bookDetailsDao().getByMediaId(mediaId)?.authors)
    }

    @Test
    fun applyBackfilledMetadata_bothNull_isNoOpSuccess() = runTest {
        val addResult = repo.addBook(title = "Nothing To Write", format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(addResult)

        val result = repo.applyBackfilledMetadata(addResult.data, coverImageHash = null, authors = null)
        assertIs<Resource.Success<Unit>>(result)
    }

    @Test
    fun applyBackfilledMetadata_unknownMediaId_returnsError() = runTest {
        val result = repo.applyBackfilledMetadata(newId(), coverImageHash = "x.jpg", authors = null)
        assertIs<Resource.Error>(result)
    }

    @Test
    fun getAllBooksWithDetails_returnsEveryBookJoinedWithDetails() = runTest {
        val first = repo.addBook(title = "Book One", format = BookFormat.PHYSICAL, isbn = "9780000000001")
        val second = repo.addBook(title = "Book Two", format = BookFormat.EBOOK, isbn = "9780000000002")
        assertIs<Resource.Success<String>>(first)
        assertIs<Resource.Success<String>>(second)

        val all = repo.getAllBooksWithDetails()

        assertEquals(2, all.size)
        val ids = all.map { it.mediaItem.id }.toSet()
        assertTrue(ids.contains(first.data))
        assertTrue(ids.contains(second.data))
        assertTrue(all.all { it.details != null })
    }

    @Test
    fun getAllBooksWithDetails_returnsResultsOrderedByTitle() = runTest {
        // Insert books in non-alphabetical order to ensure ordering is actually enforced
        val third = repo.addBook(title = "Zebra", format = BookFormat.PHYSICAL, isbn = "9780000000003")
        val first = repo.addBook(title = "Alpha", format = BookFormat.EBOOK, isbn = "9780000000001")
        val second = repo.addBook(title = "Beta", format = BookFormat.PHYSICAL, isbn = "9780000000002")
        assertIs<Resource.Success<String>>(first)
        assertIs<Resource.Success<String>>(second)
        assertIs<Resource.Success<String>>(third)

        val all = repo.getAllBooksWithDetails()

        assertEquals(3, all.size)
        val titles = all.map { it.mediaItem.title }
        assertEquals(listOf("Alpha", "Beta", "Zebra"), titles)
    }

    @Test
    fun getAllBooksWithDetails_includesBookWithNullDetailsWhenNoBookDetailsRow() = runTest {
        val mediaId = newId()
        // Insert a BOOK media item directly, bypassing repo.addBook so no book_details row is created
        db.mediaItemDao().insert(
            sampleMediaItem(
                id = mediaId,
                title = "Book Without Details",
                type = MediaType.BOOK,
            )
        )

        val all = repo.getAllBooksWithDetails()

        assertEquals(1, all.size)
        val bookWithDetails = all.single()
        assertEquals(mediaId, bookWithDetails.mediaItem.id)
        assertEquals("Book Without Details", bookWithDetails.mediaItem.title)
        assertEquals(null, bookWithDetails.details)
    }
}
