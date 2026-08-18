package com.hub.media.features.books.network

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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [OpenLibrarySearchClient.resolveEditionToIsbn] (ROADMAP Task 9 Phase B2).
 *
 * A search result carries an edition key but no ISBN; the resolver bridges from the work-level
 * search hit to an ISBN that can be passed to [AddBookByIsbnUseCase]. Tests verify happy path
 * (both ISBN-13 and ISBN-10), null ISBN (edition exists but has no ISBN), and error cases
 * (network failure, parse failure, non-2xx status).
 */
class OpenLibraryEditionResolverTest {
    private fun MockRequestHandleScope.jsonResponse(
        json: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = json,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    /**
     * A typical Open Library edition response with both ISBN-13 and ISBN-10.
     * Real Open Library responses often have one or the other, or both.
     */
    private val editionWithBothIsbnsJson =
        """
        {
          "title": "The Hobbit",
          "isbn_13": ["9780547928227"],
          "isbn_10": ["0547928228"],
          "number_of_pages": 310,
          "key": "/books/OL51711263M"
        }
        """.trimIndent()

    /** Edition with only ISBN-13 (the preferential case). */
    private val editionWithIsbn13Json =
        """
        {
          "title": "The Hobbit",
          "isbn_13": ["9780547928227"],
          "key": "/books/OL51711263M"
        }
        """.trimIndent()

    /** Edition with only ISBN-10 (fallback). */
    private val editionWithIsbn10Json =
        """
        {
          "title": "The Hobbit",
          "isbn_10": ["0547928228"],
          "key": "/books/OL51711263M"
        }
        """.trimIndent()

    /** Edition found but carries no ISBN in the index. */
    private val editionWithoutIsbnJson =
        """
        {
          "title": "A Rare Book",
          "key": "/books/OL12345678M"
        }
        """.trimIndent()

    @Test
    fun happyPath_isbn13_returnedFirst() =
        runTest {
            val engine = MockEngine { jsonResponse(editionWithBothIsbnsJson) }
            val client = OpenLibrarySearchClient(createHttpClient(engine))

            val result = client.resolveEditionToIsbn("OL51711263M")

            assertTrue(result is Resource.Success, "expected Success, got $result")
            assertEquals("9780547928227", (result as Resource.Success).data)
        }

    @Test
    fun isbn13Only_returned() =
        runTest {
            val engine = MockEngine { jsonResponse(editionWithIsbn13Json) }
            val client = OpenLibrarySearchClient(createHttpClient(engine))

            val result = client.resolveEditionToIsbn("OL51711263M")

            assertTrue(result is Resource.Success)
            assertEquals("9780547928227", (result as Resource.Success).data)
        }

    @Test
    fun isbn10Only_returned() =
        runTest {
            val engine = MockEngine { jsonResponse(editionWithIsbn10Json) }
            val client = OpenLibrarySearchClient(createHttpClient(engine))

            val result = client.resolveEditionToIsbn("OL51711263M")

            assertTrue(result is Resource.Success)
            assertEquals("0547928228", (result as Resource.Success).data)
        }

    @Test
    fun editionFound_butNoIsbn_returnsSuccessWithNull() =
        runTest {
            // Not every edition has an ISBN in the index. This is not an error condition,
            // merely the case where this edition cannot be added via ISBN lookup.
            val engine = MockEngine { jsonResponse(editionWithoutIsbnJson) }
            val client = OpenLibrarySearchClient(createHttpClient(engine))

            val result = client.resolveEditionToIsbn("OL12345678M")

            assertTrue(result is Resource.Success, "expected Success, got $result")
            assertEquals(null, (result as Resource.Success).data)
        }

    @Test
    fun emptyEditionKey_returnsError() =
        runTest {
            val engine = MockEngine { jsonResponse(editionWithIsbn13Json) }
            val client = OpenLibrarySearchClient(createHttpClient(engine))

            val result = client.resolveEditionToIsbn("")

            assertIs<Resource.Error>(result)
            assertEquals("Edition key cannot be empty", result.message)
        }

    @Test
    fun whitespaceOnlyEditionKey_isAccepted() =
        runTest {
            // After trimming, it's empty
            val engine = MockEngine { jsonResponse(editionWithIsbn13Json) }
            val client = OpenLibrarySearchClient(createHttpClient(engine))

            val result = client.resolveEditionToIsbn("   ")

            assertIs<Resource.Error>(result)
            assertEquals("Edition key cannot be empty", result.message)
        }

    @Test
    fun non2xxStatus_returnsError() =
        runTest {
            val engine = MockEngine { jsonResponse(editionWithIsbn13Json, HttpStatusCode.NotFound) }
            val client = OpenLibrarySearchClient(createHttpClient(engine))

            val result = client.resolveEditionToIsbn("OL99999999M")

            assertIs<Resource.Error>(result)
            assertTrue(result.message.contains("404"))
        }

    @Test
    fun malformedJson_returnsError() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "{ invalid json ]",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = OpenLibrarySearchClient(createHttpClient(engine))

            val result = client.resolveEditionToIsbn("OL51711263M")

            assertIs<Resource.Error>(result)
            assertTrue(
                result.message.contains("malformed"),
                "error message should mention malformed JSON, got: ${result.message}",
            )
        }

    @Test
    fun networkTimeout_returnsError() =
        runTest {
            val engine = MockEngine { respondError(HttpStatusCode.RequestTimeout) }
            val client = OpenLibrarySearchClient(createHttpClient(engine))

            val result = client.resolveEditionToIsbn("OL51711263M")

            assertIs<Resource.Error>(result)
        }

    @Test
    fun rateLimited_returnsError() =
        runTest {
            val engine = MockEngine { jsonResponse(editionWithIsbn13Json, HttpStatusCode.TooManyRequests) }
            val client = OpenLibrarySearchClient(createHttpClient(engine))

            val result = client.resolveEditionToIsbn("OL51711263M")

            assertIs<Resource.Error>(result)
            assertTrue(result.message.contains("429"))
        }

    @Test
    fun editionKeyIsTrimmed() =
        runTest {
            val engine = MockEngine { jsonResponse(editionWithIsbn13Json) }
            val client = OpenLibrarySearchClient(createHttpClient(engine))

            val result = client.resolveEditionToIsbn("  OL51711263M  ")

            assertTrue(result is Resource.Success)
            assertEquals("9780547928227", (result as Resource.Success).data)
        }

    @Test
    fun isbnListWithMultipleEntries_takesFirst() =
        runTest {
            // Some editions might have multiple ISBNs listed. Take the first one.
            val multiIsbnJson =
                """
                {
                  "title": "The Hobbit",
                  "isbn_13": ["9780547928227", "9780547928234", "9780547928241"],
                  "key": "/books/OL51711263M"
                }
                """.trimIndent()

            val engine = MockEngine { jsonResponse(multiIsbnJson) }
            val client = OpenLibrarySearchClient(createHttpClient(engine))

            val result = client.resolveEditionToIsbn("OL51711263M")

            assertTrue(result is Resource.Success)
            assertEquals("9780547928227", (result as Resource.Success).data)
        }

    @Test
    fun logging_recordsFailures() =
        runTest {
            val logger = RecordingLogger()
            val engine = MockEngine { jsonResponse(editionWithIsbn13Json, HttpStatusCode.NotFound) }
            val client = OpenLibrarySearchClient(createHttpClient(engine), logger)

            client.resolveEditionToIsbn("OL99999999M")

            val warns = logger.entries.filter { it.level == LogLevel.WARN }
            assertTrue(
                warns.isNotEmpty(),
                "expected at least one WARN log, got ${logger.entries}",
            )
            assertTrue(
                warns.any { it.message.contains("404") },
                "expected a warn about status 404, got ${warns.map { it.message }}",
            )
        }
}
