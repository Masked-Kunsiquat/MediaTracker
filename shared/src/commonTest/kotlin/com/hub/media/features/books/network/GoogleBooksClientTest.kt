package com.hub.media.features.books.network

import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.util.LogLevel
import com.hub.media.core.util.RecordingLogger
import com.hub.media.core.util.Resource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleBooksClientTest {
    private fun MockRequestHandleScope.jsonResponse(
        json: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = json,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    @Test
    fun happyPath_parsesFullMetadataAndUpgradesCoverUrlToHttps() =
        runTest {
            val responseJson =
                """
                {
                  "kind": "books#volumes",
                  "totalItems": 1,
                  "items": [
                    {
                      "id": "wrOQLV6xB-wC",
                      "volumeInfo": {
                        "title": "The Hobbit",
                        "authors": ["J.R.R. Tolkien"],
                        "publishedDate": "1997",
                        "pageCount": 310,
                        "imageLinks": {
                          "thumbnail": "http://books.google.com/books/content?id=wrOQLV6xB-wC&img=1"
                        }
                      }
                    }
                  ]
                }
                """.trimIndent()
            val engine = MockEngine { jsonResponse(responseJson) }
            val client = GoogleBooksClient(createHttpClient(engine))

            val result = client.fetchByIsbn("9780618968633")

            assertTrue(result is Resource.Success, "expected Success, got $result")
            val metadata = (result as Resource.Success).data
            assertEquals("The Hobbit", metadata.title)
            assertEquals(listOf("J.R.R. Tolkien"), metadata.authors)
            assertEquals(1997, metadata.releaseYear)
            assertEquals(310, metadata.pageCount)
            assertEquals(
                "https://books.google.com/books/content?id=wrOQLV6xB-wC&img=1",
                metadata.coverImageUrl,
            )
            assertEquals(IdentifierProvider.GOOGLE_BOOKS, metadata.provider)
            assertEquals("wrOQLV6xB-wC", metadata.externalId)
            assertEquals("9780618968633", metadata.isbn)
        }

    @Test
    fun zeroItems_returnsError() =
        runTest {
            val responseJson = """{"kind": "books#volumes", "totalItems": 0}"""
            val engine = MockEngine { jsonResponse(responseJson) }
            val client = GoogleBooksClient(createHttpClient(engine))

            val result = client.fetchByIsbn("0000000000")

            assertTrue(result is Resource.Error, "expected Error, got $result")
        }

    @Test
    fun missingVolumeInfoFields_returnsSuccessWithNulls() =
        runTest {
            val responseJson =
                """
                {
                  "totalItems": 1,
                  "items": [
                    {
                      "id": "abc123",
                      "volumeInfo": {
                        "title": "Mystery Book"
                      }
                    }
                  ]
                }
                """.trimIndent()
            val engine = MockEngine { jsonResponse(responseJson) }
            val client = GoogleBooksClient(createHttpClient(engine))

            val result = client.fetchByIsbn("1234567890")

            assertTrue(result is Resource.Success, "expected Success, got $result")
            val metadata = (result as Resource.Success).data
            assertEquals("Mystery Book", metadata.title)
            assertTrue(metadata.authors.isEmpty())
            assertNull(metadata.releaseYear)
            assertNull(metadata.pageCount)
            assertNull(metadata.coverImageUrl)
        }

    @Test
    fun malformedJson_returnsError() =
        runTest {
            val engine = MockEngine { jsonResponse("{ not valid json ") }
            val client = GoogleBooksClient(createHttpClient(engine))

            val result = client.fetchByIsbn("9780618968633")

            assertTrue(result is Resource.Error, "expected Error, got $result")
        }

    /**
     * ROADMAP Task 6 Phase E: [GoogleBooksImageLinksDto] previously only declared [thumbnail],
     * discarding the larger [small]/[medium]/[large]/[extraLarge] links Google sometimes provides.
     * These tests cover the fixed size-selection order (largest-present wins, falling back down
     * the chain when a preferred field is absent, and `null` only when every field is absent).
     */
    @Test
    fun imageLinks_allSizesPresent_selectsExtraLarge() =
        runTest {
            val responseJson =
                """
                {
                  "totalItems": 1,
                  "items": [
                    {
                      "id": "abc123",
                      "volumeInfo": {
                        "title": "The Hobbit",
                        "imageLinks": {
                          "smallThumbnail": "http://books.google.com/small_thumb.jpg",
                          "thumbnail": "http://books.google.com/thumb.jpg",
                          "small": "http://books.google.com/small.jpg",
                          "medium": "http://books.google.com/medium.jpg",
                          "large": "http://books.google.com/large.jpg",
                          "extraLarge": "http://books.google.com/extra_large.jpg"
                        }
                      }
                    }
                  ]
                }
                """.trimIndent()
            val engine = MockEngine { jsonResponse(responseJson) }
            val client = GoogleBooksClient(createHttpClient(engine))

            val result = client.fetchByIsbn("9780618968633")

            assertTrue(result is Resource.Success, "expected Success, got $result")
            assertEquals(
                "https://books.google.com/extra_large.jpg",
                (result as Resource.Success).data.coverImageUrl,
            )
        }

    @Test
    fun imageLinks_onlyMediumAndSmallerPresent_fallsBackToMedium() =
        runTest {
            val responseJson =
                """
                {
                  "totalItems": 1,
                  "items": [
                    {
                      "id": "abc123",
                      "volumeInfo": {
                        "title": "The Hobbit",
                        "imageLinks": {
                          "smallThumbnail": "http://books.google.com/small_thumb.jpg",
                          "thumbnail": "http://books.google.com/thumb.jpg",
                          "small": "http://books.google.com/small.jpg",
                          "medium": "http://books.google.com/medium.jpg"
                        }
                      }
                    }
                  ]
                }
                """.trimIndent()
            val engine = MockEngine { jsonResponse(responseJson) }
            val client = GoogleBooksClient(createHttpClient(engine))

            val result = client.fetchByIsbn("9780618968633")

            assertTrue(result is Resource.Success, "expected Success, got $result")
            assertEquals(
                "https://books.google.com/medium.jpg",
                (result as Resource.Success).data.coverImageUrl,
            )
        }

    @Test
    fun imageLinks_onlyThumbnailPresent_fallsBackToThumbnail() =
        runTest {
            // Same shape as the pre-existing happyPath test, but named to make explicit this is the
            // bottom-of-the-chain fallback case now that larger sizes are also selectable.
            val responseJson =
                """
                {
                  "totalItems": 1,
                  "items": [
                    {
                      "id": "abc123",
                      "volumeInfo": {
                        "title": "The Hobbit",
                        "imageLinks": {
                          "thumbnail": "http://books.google.com/thumb.jpg"
                        }
                      }
                    }
                  ]
                }
                """.trimIndent()
            val engine = MockEngine { jsonResponse(responseJson) }
            val client = GoogleBooksClient(createHttpClient(engine))

            val result = client.fetchByIsbn("9780618968633")

            assertTrue(result is Resource.Success, "expected Success, got $result")
            assertEquals(
                "https://books.google.com/thumb.jpg",
                (result as Resource.Success).data.coverImageUrl,
            )
        }

    @Test
    fun imageLinks_onlySmallThumbnailPresent_fallsBackToSmallThumbnail() =
        runTest {
            val responseJson =
                """
                {
                  "totalItems": 1,
                  "items": [
                    {
                      "id": "abc123",
                      "volumeInfo": {
                        "title": "The Hobbit",
                        "imageLinks": {
                          "smallThumbnail": "http://books.google.com/small_thumb.jpg"
                        }
                      }
                    }
                  ]
                }
                """.trimIndent()
            val engine = MockEngine { jsonResponse(responseJson) }
            val client = GoogleBooksClient(createHttpClient(engine))

            val result = client.fetchByIsbn("9780618968633")

            assertTrue(result is Resource.Success, "expected Success, got $result")
            assertEquals(
                "https://books.google.com/small_thumb.jpg",
                (result as Resource.Success).data.coverImageUrl,
            )
        }

    @Test
    fun imageLinks_allSizesAbsent_coverImageUrlIsNull() =
        runTest {
            val responseJson =
                """
                {
                  "totalItems": 1,
                  "items": [
                    {
                      "id": "abc123",
                      "volumeInfo": {
                        "title": "The Hobbit",
                        "imageLinks": {}
                      }
                    }
                  ]
                }
                """.trimIndent()
            val engine = MockEngine { jsonResponse(responseJson) }
            val client = GoogleBooksClient(createHttpClient(engine))

            val result = client.fetchByIsbn("9780618968633")

            assertTrue(result is Resource.Success, "expected Success, got $result")
            assertNull((result as Resource.Success).data.coverImageUrl)
        }

    @Test
    fun nonSuccessStatus_surfacesTheStatusAndLogsIt() =
        runTest {
            // Observed for real, not hypothesised: with Open Library down, the fallback answered 429
            // and this class logged nothing, so two provider failures left a single log entry. The
            // status path was the only failure mode here that did not log.
            val logger = RecordingLogger()
            val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests) }
            val client = GoogleBooksClient(createHttpClient(engine), logger)

            val result = client.fetchByIsbn("9780261102217")

            assertTrue(result is Resource.Error, "expected Error, got $result")
            assertTrue("429" in (result as Resource.Error).message, "expected the status: ${result.message}")
            val logged = logger.entries.single()
            assertEquals(LogLevel.WARN, logged.level)
            assertTrue("429" in logged.message, "expected the status in the log: ${logged.message}")
        }
}
