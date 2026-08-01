package com.hub.media.features.books.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleExternalIdentifier
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
    fun updateBook_persistsChanges() = runTest {
        val mediaId = newId()
        val mediaItem = sampleMediaItem(
            id = mediaId,
            type = MediaType.BOOK,
            title = "Old Title",
            purchasePrice = 10.0,
        )
        db.mediaItemDao().insert(mediaItem)

        val result = repo.updateBook(
            id = mediaId,
            title = "New Title",
            releaseYear = 2024,
            purchasePrice = 25.0,
        )

        assertIs<Resource.Success<Unit>>(result)
        val updated = db.mediaItemDao().getById(mediaId)
        assertEquals("New Title", updated?.title)
        assertEquals(2024, updated?.releaseYear)
        assertEquals(25.0, updated?.purchasePrice)
    }

    @Test
    fun updateBook_unknownId_returnsError() = runTest {
        val result = repo.updateBook(
            id = newId(),
            title = "Any Title",
        )

        assertIs<Resource.Error>(result)
        assertTrue(result.message.contains("not found"))
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
}
