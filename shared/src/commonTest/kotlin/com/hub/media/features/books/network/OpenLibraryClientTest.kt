package com.hub.media.features.books.network

import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.util.Resource
import com.hub.media.core.util.LogLevel
import com.hub.media.core.util.RecordingLogger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class OpenLibraryClientTest {

    private fun MockRequestHandleScope.jsonResponse(
        json: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = json,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    @Test
    fun happyPath_parsesFullMetadataIncludingAuthorSubFetch() = runTest {
        val editionJson = """
            {
              "title": "The Hobbit",
              "authors": [{"key": "/authors/OL26320A"}],
              "number_of_pages": 300,
              "publish_date": "2012",
              "covers": [6498519],
              "key": "/books/OL25946828M"
            }
        """.trimIndent()
        val authorJson = """{"name": "J.R.R. Tolkien", "key": "/authors/OL26320A"}"""

        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.contains("/isbn/") -> jsonResponse(editionJson)
                request.url.encodedPath.contains("/authors/") -> jsonResponse(authorJson)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val client = OpenLibraryClient(createHttpClient(engine))

        val result = client.fetchByIsbn("9780547928227")

        assertTrue(result is Resource.Success, "expected Success, got $result")
        val metadata = (result as Resource.Success).data
        assertEquals("The Hobbit", metadata.title)
        assertEquals(listOf("J.R.R. Tolkien"), metadata.authors)
        assertEquals(300, metadata.pageCount)
        assertEquals(2012, metadata.releaseYear)
        assertEquals("https://covers.openlibrary.org/b/id/6498519-L.jpg", metadata.coverImageUrl)
        assertEquals(IdentifierProvider.OPEN_LIBRARY, metadata.provider)
        assertEquals("/books/OL25946828M", metadata.externalId)
        assertEquals("9780547928227", metadata.isbn)
    }

    @Test
    fun notFound_returnsError() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
        val client = OpenLibraryClient(createHttpClient(engine))

        val result = client.fetchByIsbn("0000000000")

        assertTrue(result is Resource.Error, "expected Error, got $result")
    }

    @Test
    fun malformedJson_returnsError() = runTest {
        val engine = MockEngine { jsonResponse("{ this is not valid json ") }
        val client = OpenLibraryClient(createHttpClient(engine))

        val result = client.fetchByIsbn("9780547928227")

        assertTrue(result is Resource.Error, "expected Error, got $result")
    }

    @Test
    fun missingOptionalFields_returnsSuccessWithNulls() = runTest {
        val editionJson = """{"title": "Mystery Book"}"""
        val engine = MockEngine { jsonResponse(editionJson) }
        val client = OpenLibraryClient(createHttpClient(engine))

        val result = client.fetchByIsbn("1234567890")

        assertTrue(result is Resource.Success, "expected Success, got $result")
        val metadata = (result as Resource.Success).data
        assertEquals("Mystery Book", metadata.title)
        assertNull(metadata.pageCount)
        assertNull(metadata.releaseYear)
        assertNull(metadata.coverImageUrl)
        assertTrue(metadata.authors.isEmpty())
    }

    @Test
    fun authorSubFetchFailure_returnsSuccessWithEmptyAuthors() = runTest {
        val editionJson = """
            {
              "title": "The Hobbit",
              "authors": [{"key": "/authors/OL26320A"}]
            }
        """.trimIndent()
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.contains("/isbn/") -> jsonResponse(editionJson)
                request.url.encodedPath.contains("/authors/") -> respondError(HttpStatusCode.InternalServerError)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val recorder = RecordingLogger()
        val client = OpenLibraryClient(createHttpClient(engine), logger = recorder)

        val result = client.fetchByIsbn("9780547928227")

        assertTrue(result is Resource.Success, "expected Success, got $result")
        val metadata = (result as Resource.Success).data
        assertTrue(metadata.authors.isEmpty())

        // ROADMAP Task 15 Phase C: dropping the author is still the right behaviour -- one
        // unreachable author must not fail the whole lookup -- but it used to happen with nothing
        // recorded anywhere, which made "why has this book got no author?" unanswerable. The name
        // itself stays out of the log; the catalogue key and the status code do not.
        val warning = recorder.entries.single { it.level == LogLevel.WARN }
        assertEquals("OpenLibraryClient", warning.tag)
        assertTrue(warning.message.contains("/authors/OL26320A"), "the key is what makes it diagnosable")
        assertTrue(warning.message.contains("500"), "the status is what says why")
    }

    @Test
    fun missingTitle_returnsError() = runTest {
        val engine = MockEngine { jsonResponse("""{"number_of_pages": 100}""") }
        val client = OpenLibraryClient(createHttpClient(engine))

        val result = client.fetchByIsbn("1234567890")

        assertTrue(result is Resource.Error, "expected Error, got $result")
    }
}
