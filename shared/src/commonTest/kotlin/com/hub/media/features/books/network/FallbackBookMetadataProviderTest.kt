package com.hub.media.features.books.network

import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.util.Resource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Verifies [FallbackBookMetadataProvider]'s primary-then-secondary semantics. Each provider is
 * backed by its own [MockEngine] so [MockEngine.requestHistory] can be used to assert exactly
 * how many real HTTP calls each provider made — in particular, that the secondary provider is
 * never touched when the primary succeeds.
 */
class FallbackBookMetadataProviderTest {

    // Edition JSON with no authors, so a successful Open Library lookup makes exactly one
    // request (no secondary /authors/ fetch), keeping request counts easy to reason about.
    private val openLibraryEditionJson = """{"title": "The Hobbit", "number_of_pages": 300}"""

    private val googleBooksJson = """
        {
          "totalItems": 1,
          "items": [
            {"id": "wrOQLV6xB-wC", "volumeInfo": {"title": "The Hobbit (Google)"}}
          ]
        }
    """.trimIndent()

    private fun jsonEngine(json: String) = MockEngine { _ ->
        respond(
            content = json,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private fun notFoundEngine() = MockEngine { _ -> respondError(HttpStatusCode.NotFound) }

    @Test
    fun primarySuccess_secondaryNeverCalled() = runTest {
        val primaryEngine = jsonEngine(openLibraryEditionJson)
        val secondaryEngine = jsonEngine(googleBooksJson)
        val primary = OpenLibraryClient(createHttpClient(primaryEngine))
        val secondary = GoogleBooksClient(createHttpClient(secondaryEngine))
        val fallback = FallbackBookMetadataProvider(primary, secondary)

        val result = fallback.fetchByIsbn("9780547928227")

        assertTrue(result is Resource.Success, "expected Success, got $result")
        assertEquals(IdentifierProvider.OPEN_LIBRARY, (result as Resource.Success).data.provider)
        assertEquals(1, primaryEngine.requestHistory.size, "primary should be called exactly once")
        assertTrue(secondaryEngine.requestHistory.isEmpty(), "secondary must never be called when primary succeeds")
    }

    @Test
    fun primaryFails_secondarySucceeds() = runTest {
        val primaryEngine = notFoundEngine()
        val secondaryEngine = jsonEngine(googleBooksJson)
        val primary = OpenLibraryClient(createHttpClient(primaryEngine))
        val secondary = GoogleBooksClient(createHttpClient(secondaryEngine))
        val fallback = FallbackBookMetadataProvider(primary, secondary)

        val result = fallback.fetchByIsbn("9780547928227")

        assertTrue(result is Resource.Success, "expected Success, got $result")
        val metadata = (result as Resource.Success).data
        assertEquals(IdentifierProvider.GOOGLE_BOOKS, metadata.provider)
        assertEquals("The Hobbit (Google)", metadata.title)
        assertEquals(1, primaryEngine.requestHistory.size, "primary should be attempted once")
        assertEquals(1, secondaryEngine.requestHistory.size, "secondary should be attempted once after primary fails")
    }

    @Test
    fun bothFail_returnsErrorMentioningBothProviders() = runTest {
        val primaryEngine = notFoundEngine()
        val secondaryEngine = notFoundEngine()
        val primary = OpenLibraryClient(createHttpClient(primaryEngine))
        val secondary = GoogleBooksClient(createHttpClient(secondaryEngine))
        val fallback = FallbackBookMetadataProvider(primary, secondary)

        val result = fallback.fetchByIsbn("0000000000")

        assertTrue(result is Resource.Error, "expected Error, got $result")
        val message = (result as Resource.Error).message
        assertTrue(message.contains("Primary"), "message should mention primary failure: $message")
        assertTrue(message.contains("Secondary"), "message should mention secondary failure: $message")
        assertEquals(1, primaryEngine.requestHistory.size)
        assertEquals(1, secondaryEngine.requestHistory.size)
    }
}
