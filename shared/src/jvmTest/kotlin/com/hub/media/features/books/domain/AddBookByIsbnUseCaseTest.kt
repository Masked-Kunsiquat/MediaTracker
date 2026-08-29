package com.hub.media.features.books.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.storage.cleanupTestTempDir
import com.hub.media.core.storage.createTestTempDir
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for [AddBookByIsbnUseCase], wired via [createDefaultAddBookByIsbnUseCase] so
 * the real Open Library -> Google Books fallback chain and cover downloader run against a single
 * [MockEngine], and a real (in-memory) [AppDatabase] / temp-dir [LocalImageStorageManager] verify
 * the on-disk and on-database side effects. This is why the test lives in a Room-touching
 * package and is excluded from the android unit-test variant (see shared/build.gradle.kts) —
 * :shared:jvmTest is the authoritative gate, same as the DAO/repository tests.
 */
class AddBookByIsbnUseCaseTest {
    private lateinit var db: AppDatabase
    private lateinit var tempDir: String
    private lateinit var repository: BookRepository

    @BeforeTest
    fun setUp() =
        runTest {
            db = testAppDatabase()
            tempDir = createTestTempDir()
            repository = BookRepository(db)
        }

    @AfterTest
    fun tearDown() =
        runTest {
            db.close()
            cleanupTestTempDir(tempDir)
        }

    private val coverImageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3, 4)

    private fun useCase(engine: MockEngine): AddBookByIsbnUseCase =
        createDefaultAddBookByIsbnUseCase(
            httpClient = createHttpClient(engine),
            imageStorage = LocalImageStorageManager(tempDir),
            bookRepository = repository,
        )

    @Test
    fun happyPath_insertsMediaItemDetailsIdentifiersAndCoverFile() =
        runTest {
            val editionJson =
                """
                {
                  "title": "The Hobbit",
                  "number_of_pages": 300,
                  "publish_date": "2012",
                  "covers": [6498519],
                  "key": "/books/OL25946828M"
                }
                """.trimIndent()
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.contains("/isbn/") ->
                            respond(
                                content = editionJson,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        request.url.host == "covers.openlibrary.org" ->
                            respond(
                                content = coverImageBytes,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
                            )
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }

            // Hyphens/spaces in the input must be normalized away before lookup + storage.
            val result = useCase(engine).execute("978-0-547-92822-7")

            assertIs<Resource.Success<String>>(result)
            val mediaId = result.data

            val mediaItem = db.mediaItemDao().getById(mediaId)
            assertEquals("The Hobbit", mediaItem?.title)
            assertEquals(2012, mediaItem?.releaseYear)
            assertTrue(mediaItem?.coverImageHash?.endsWith(".jpg") == true)

            val bookDetails = db.bookDetailsDao().getByMediaId(mediaId)
            assertEquals("9780547928227", bookDetails?.isbn)
            assertEquals(300, bookDetails?.totalPages)
            // ROADMAP Task 7 Phase A: a known page count defaults ingestion's trackingMode to PAGES.
            assertEquals(TrackingMode.PAGES, bookDetails?.trackingMode)

            val identifiers = db.externalIdentifierDao().observeForMedia(mediaId).first()
            assertEquals(2, identifiers.size)
            assertTrue(
                identifiers.any {
                    it.provider == IdentifierProvider.OPEN_LIBRARY &&
                        it.externalId == "/books/OL25946828M"
                },
            )
            assertTrue(identifiers.any { it.provider == IdentifierProvider.ISBN && it.externalId == "9780547928227" })

            val coverFile = File(tempDir, mediaItem!!.coverImageHash!!)
            assertTrue(coverFile.exists(), "expected cover file to be written to disk")
            assertEquals(coverImageBytes.toList(), coverFile.readBytes().toList())
        }

    @Test
    fun workKey_isPersistedAlongsideTheEditionKey() =
        runTest {
            // The edition key and the work key are different facts about different things -- one
            // printing versus the book it is a printing of -- and the (mediaId, provider) primary key
            // gives each its own row. Nothing reads the work key yet; it is captured now because
            // backfilling it later means another rate-limited crawl over the whole library.
            val editionJson =
                """
                {
                  "works": [{"key": "/works/OL27482W"}],
                  "title": "The Hobbit",
                  "key": "/books/OL33891995M"
                }
                """.trimIndent()
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.contains("/isbn/") ->
                            respond(
                                content = editionJson,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }

            val result = useCase(engine).execute("9780547928227")

            assertIs<Resource.Success<String>>(result)
            val identifiers = db.externalIdentifierDao().observeForMedia(result.data).first()
            assertEquals(3, identifiers.size)
            assertTrue(
                identifiers.any {
                    it.provider == IdentifierProvider.OPEN_LIBRARY_WORK && it.externalId == "/works/OL27482W"
                },
                "expected the work key: $identifiers",
            )
            assertTrue(
                identifiers.any {
                    it.provider == IdentifierProvider.OPEN_LIBRARY && it.externalId == "/books/OL33891995M"
                },
                "the edition key must survive alongside it: $identifiers",
            )
        }

    @Test
    fun metadataFetchFails_bothProvidersNotFound_returnsErrorWithNoRows() =
        runTest {
            val engine = MockEngine { respondError(HttpStatusCode.NotFound) }

            val result = useCase(engine).execute("9780547928227")

            assertIs<Resource.Error>(result)
            assertTrue(
                db
                    .mediaItemDao()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
            assertTrue(
                db
                    .bookDetailsDao()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
            assertTrue(
                db
                    .externalIdentifierDao()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun coverDownloadFails_bookStillSavedWithNullCoverHashAndNoFile() =
        runTest {
            val editionJson =
                """
                {
                  "title": "The Hobbit",
                  "covers": [6498519],
                  "key": "/books/OL25946828M"
                }
                """.trimIndent()
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.contains("/isbn/") ->
                            respond(
                                content = editionJson,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        request.url.host == "covers.openlibrary.org" -> respondError(HttpStatusCode.NotFound)
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }

            val result = useCase(engine).execute("9780547928227")

            assertIs<Resource.Success<String>>(result)
            val mediaItem = db.mediaItemDao().getById(result.data)
            assertEquals("The Hobbit", mediaItem?.title)
            assertNull(mediaItem?.coverImageHash)

            val dir = File(tempDir)
            val writtenFiles = dir.listFiles()?.filter { it.isFile } ?: emptyList()
            assertTrue(writtenFiles.isEmpty(), "no cover file should have been written on download failure")
        }

    /**
     * ROADMAP Task 6 Phase E updated this test's premise: the default provider chain
     * ([createDefaultBookMetadataProvider]) now also consults [com.hub.media.features.books.network.OpenLibraryIsbnCoverProbe]
     * as a last-resort step whenever *both* Open Library's own record and the Google Books probe
     * are coverless -- which this test's scenario (no cover anywhere) is exactly. That probe
     * legitimately DOES hit `covers.openlibrary.org` now (previously it never would have, since
     * the probe didn't exist), so the old assertion ("zero requests to that host") no longer holds
     * -- and correctly so, that's the new intended behavior. What must still hold is the *outcome*:
     * with no real cover anywhere (this test's engine 404s the probe's URL, matched precisely by
     * host rather than by an "/isbn/" path substring that used to accidentally also match the
     * probe's `/b/isbn/...` path), `coverImageHash` stays null and no cover file is written.
     */
    @Test
    fun noCoverUrlInMetadata_succeedsWithoutDownloadingAnyCover() =
        runTest {
            val editionJson = """{"title": "Mystery Book"}"""
            val engine =
                MockEngine { request ->
                    when {
                        // Open Library metadata lookup (GET openlibrary.org/isbn/{isbn}.json) -- the only
                        // request that should ever succeed in this "no cover anywhere" scenario.
                        request.url.host == "openlibrary.org" && request.url.encodedPath.contains("/isbn/") ->
                            respond(
                                content = editionJson,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        // Everything else -- Google Books' secondary lookup, and the covers.openlibrary.org
                        // last-resort ISBN cover probe -- has no cover to offer.
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }

            val result = useCase(engine).execute("9780547928227")

            assertIs<Resource.Success<String>>(result)
            val mediaItem = db.mediaItemDao().getById(result.data)
            assertNull(mediaItem?.coverImageHash, "no provider (including the last-resort probe) had a cover")
            val writtenFiles = File(tempDir).listFiles()?.filter { it.isFile } ?: emptyList()
            assertTrue(writtenFiles.isEmpty(), "no cover file should have been written when nothing has a cover")

            // ROADMAP Task 7 Phase A: "Mystery Book"'s metadata carries no page count, so ingestion's
            // trackingMode defaults to PERCENT.
            val bookDetails = db.bookDetailsDao().getByMediaId(result.data)
            assertNull(bookDetails?.totalPages)
            assertEquals(TrackingMode.PERCENT, bookDetails?.trackingMode)
        }

    @Test
    fun invalidBlankIsbn_returnsErrorWithoutAnyNetworkRequest() =
        runTest {
            val engine = MockEngine { respondError(HttpStatusCode.NotFound) }

            val result = useCase(engine).execute("   ")

            assertIs<Resource.Error>(result)
            assertTrue(engine.requestHistory.isEmpty(), "blank ISBN must short-circuit before any network call")
            assertTrue(
                db
                    .mediaItemDao()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun invalidIsbn_wrongLength_returnsErrorWithoutAnyNetworkRequest() =
        runTest {
            val engine = MockEngine { respondError(HttpStatusCode.NotFound) }

            val result = useCase(engine).execute("12345")

            assertIs<Resource.Error>(result)
            assertTrue(engine.requestHistory.isEmpty())
        }
}
