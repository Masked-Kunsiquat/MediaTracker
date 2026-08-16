package com.hub.media.features.books.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.sampleBookDetails
import com.hub.media.core.database.sampleMediaItem
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.storage.cleanupTestTempDir
import com.hub.media.core.storage.createTestTempDir
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.network.BookMetadata
import com.hub.media.features.books.network.BookMetadataProvider
import com.hub.media.features.books.network.CoverImageDownloader
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [RefetchCoverUseCase] (ROADMAP Task 6 Phase E's per-book re-fetch-cover affordance),
 * against a real in-memory [AppDatabase] / temp-dir [LocalImageStorageManager] (same style as
 * [AddBookByIsbnUseCaseTest]), but with a hand-rolled [BookMetadataProvider] fake rather than the
 * real Open Library/Google Books chain -- this file is about [RefetchCoverUseCase]'s own
 * lookup -> download -> save -> update-hash orchestration and error handling, not the provider
 * fallback chain itself (see [FallbackBookMetadataProviderTest] for that).
 */
class RefetchCoverUseCaseTest {
    private lateinit var db: AppDatabase
    private lateinit var tempDir: String
    private lateinit var repository: BookRepository
    private lateinit var mediaId: String

    @BeforeTest
    fun setUp() =
        runTest {
            db = testAppDatabase()
            tempDir = createTestTempDir()
            repository = BookRepository(db)
            mediaId = newId()
        }

    @AfterTest
    fun tearDown() =
        runTest {
            db.close()
            cleanupTestTempDir(tempDir)
        }

    private val coverImageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3, 4)

    private suspend fun insertBook(
        isbn: String? = "9780547928227",
        coverImageHash: String? = null,
    ) {
        db.mediaItemDao().insert(
            sampleMediaItem(id = mediaId, type = MediaType.BOOK, title = "The Hobbit", coverImageHash = coverImageHash),
        )
        db.bookDetailsDao().insert(sampleBookDetails(mediaId = mediaId, isbn = isbn))
    }

    /** A [BookMetadataProvider] fake that always succeeds with a fixed [coverUrl] (nullable). */
    private fun fixedCoverProvider(coverUrl: String?): BookMetadataProvider =
        object : BookMetadataProvider {
            override suspend fun fetchByIsbn(isbn: String): Resource<BookMetadata> =
                Resource.Success(
                    BookMetadata(
                        title = "The Hobbit",
                        coverImageUrl = coverUrl,
                        provider = IdentifierProvider.OPEN_LIBRARY,
                    ),
                )
        }

    private fun failingProvider(message: String = "provider failed"): BookMetadataProvider =
        object : BookMetadataProvider {
            override suspend fun fetchByIsbn(isbn: String): Resource<BookMetadata> = Resource.Error(message)
        }

    private fun useCase(
        metadataProvider: BookMetadataProvider,
        engine: MockEngine,
    ): RefetchCoverUseCase =
        RefetchCoverUseCase(
            metadataProvider = metadataProvider,
            coverDownloader = CoverImageDownloader(createHttpClient(engine)),
            imageStorage = LocalImageStorageManager(tempDir),
            bookRepository = repository,
        )

    @Test
    fun happyPath_downloadsCoverAndUpdatesCoverImageHash() =
        runTest {
            insertBook()
            val engine =
                MockEngine {
                    respond(
                        content = coverImageBytes,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
                    )
                }

            val result =
                useCase(fixedCoverProvider("https://covers.example.com/cover.jpg"), engine)
                    .execute(mediaId)

            assertIs<Resource.Success<String>>(result)
            val hash = result.data
            assertTrue(hash.endsWith(".jpg"))

            val mediaItem = db.mediaItemDao().getById(mediaId)
            assertEquals(hash, mediaItem?.coverImageHash)
            val coverFile = File(tempDir, hash)
            assertTrue(coverFile.exists(), "expected the new cover file to be written to disk")
            assertEquals(coverImageBytes.toList(), coverFile.readBytes().toList())
        }

    @Test
    fun noIsbnOnRecord_returnsErrorWithoutAnyNetworkRequest() =
        runTest {
            insertBook(isbn = null)
            val engine = MockEngine { respondError(HttpStatusCode.NotFound) }

            val result =
                useCase(fixedCoverProvider("https://covers.example.com/cover.jpg"), engine)
                    .execute(mediaId)

            assertIs<Resource.Error>(result)
            assertTrue(
                engine.requestHistory.isEmpty(),
                "no ISBN on record must short-circuit before any network call",
            )
        }

    @Test
    fun blankIsbnOnRecord_returnsErrorWithoutAnyNetworkRequest() =
        runTest {
            insertBook(isbn = "   ")
            val engine = MockEngine { respondError(HttpStatusCode.NotFound) }

            val result =
                useCase(fixedCoverProvider("https://covers.example.com/cover.jpg"), engine)
                    .execute(mediaId)

            assertIs<Resource.Error>(result)
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun providerHasNoCover_returnsErrorAndLeavesExistingCoverIntact() =
        runTest {
            insertBook(coverImageHash = "existing-hash.jpg")
            val engine = MockEngine { respondError(HttpStatusCode.NotFound) }

            val result = useCase(fixedCoverProvider(null), engine).execute(mediaId)

            assertIs<Resource.Error>(result)
            val mediaItem = db.mediaItemDao().getById(mediaId)
            assertEquals(
                "existing-hash.jpg",
                mediaItem?.coverImageHash,
                "a coverless provider result must not touch the book's existing cover",
            )
            assertTrue(engine.requestHistory.isEmpty(), "no cover URL means no download should be attempted")
        }

    @Test
    fun metadataLookupFails_returnsErrorAndLeavesExistingCoverIntact() =
        runTest {
            insertBook(coverImageHash = "existing-hash.jpg")
            val engine = MockEngine { respondError(HttpStatusCode.NotFound) }

            val result = useCase(failingProvider(), engine).execute(mediaId)

            assertIs<Resource.Error>(result)
            val mediaItem = db.mediaItemDao().getById(mediaId)
            assertEquals("existing-hash.jpg", mediaItem?.coverImageHash)
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun coverDownloadFails_returnsErrorAndLeavesExistingCoverIntact() =
        runTest {
            insertBook(coverImageHash = "existing-hash.jpg")
            val engine = MockEngine { respondError(HttpStatusCode.NotFound) }

            val result =
                useCase(fixedCoverProvider("https://covers.example.com/cover.jpg"), engine)
                    .execute(mediaId)

            assertIs<Resource.Error>(result)
            val mediaItem = db.mediaItemDao().getById(mediaId)
            assertEquals(
                "existing-hash.jpg",
                mediaItem?.coverImageHash,
                "a failed download must not clear or otherwise alter the book's existing cover",
            )
            val writtenFiles = File(tempDir).listFiles()?.filter { it.isFile } ?: emptyList()
            assertTrue(writtenFiles.isEmpty(), "no cover file should have been written on download failure")
        }

    @Test
    fun unknownMediaId_returnsErrorWithoutAnyNetworkRequest() =
        runTest {
            val engine = MockEngine { respondError(HttpStatusCode.NotFound) }

            val result =
                useCase(fixedCoverProvider("https://covers.example.com/cover.jpg"), engine)
                    .execute(newId())

            assertIs<Resource.Error>(result)
            assertTrue(engine.requestHistory.isEmpty())
        }
}
