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
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class OpenLibrarySearchClientTest {

    private fun MockRequestHandleScope.jsonResponse(
        json: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = json,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    /** A response carrying one fully-populated doc plus the top-level keys the real API returns. */
    private val hobbitJson = """
        {
          "numFound": 644,
          "start": 0,
          "numFoundExact": true,
          "docs": [
            {
              "key": "/works/OL27482W",
              "type": "work",
              "title": "The Hobbit",
              "author_name": ["J.R.R. Tolkien"],
              "author_key": ["OL26320A"],
              "first_publish_year": 1937,
              "cover_i": 14627509,
              "cover_edition_key": "OL51711263M",
              "edition_count": 481,
              "number_of_pages_median": 310
            }
          ]
        }
    """.trimIndent()

    @Test
    fun happyPath_mapsEveryFieldADropdownRowNeeds() = runTest {
        val engine = MockEngine { jsonResponse(hobbitJson) }
        val client = OpenLibrarySearchClient(createHttpClient(engine))

        val result = client.searchByTitleOrAuthor("hobbit", limit = 10)

        assertTrue(result is Resource.Success, "expected Success, got $result")
        val hit = (result as Resource.Success).data.single()
        assertEquals("The Hobbit", hit.title)
        assertEquals(listOf("J.R.R. Tolkien"), hit.authors)
        assertEquals(1937, hit.firstPublishYear)
        assertEquals("https://covers.openlibrary.org/b/id/14627509-M.jpg", hit.coverThumbnailUrl)
        assertEquals(481, hit.editionCount)
        assertEquals(310, hit.medianPageCount)
        assertEquals(IdentifierProvider.OPEN_LIBRARY, hit.provider)
        assertEquals("/works/OL27482W", hit.workKey)
        assertEquals("OL51711263M", hit.coverEditionKey)
    }

    @Test
    fun request_asksForAWhitelistOfFieldsAndNeverForIsbn() = runTest {
        // The single most important property of this request. Omitting `fields` makes Open Library
        // return every indexed field including an isbn array of hundreds of entries, which on a
        // per-keystroke type-ahead is the difference between usable and unusable.
        var request: HttpRequestData? = null
        val engine = MockEngine { req ->
            request = req
            jsonResponse(hobbitJson)
        }
        val client = OpenLibrarySearchClient(createHttpClient(engine))

        client.searchByTitleOrAuthor("hobbit", limit = 7)

        val params = requireNotNull(request).url.parameters
        assertEquals("hobbit", params["q"])
        assertEquals("7", params["limit"])
        val fields = requireNotNull(params["fields"]) { "fields must always be sent" }
        assertTrue("isbn" !in fields, "isbn must never be requested: $fields")
        listOf("key", "title", "author_name", "first_publish_year", "cover_i", "cover_edition_key")
            .forEach { assertTrue(it in fields, "expected $it in fields: $fields") }
    }

    @Test
    fun query_isTrimmedAndUrlEncoded() = runTest {
        var request: HttpRequestData? = null
        val engine = MockEngine { req ->
            request = req
            jsonResponse(hobbitJson)
        }
        val client = OpenLibrarySearchClient(createHttpClient(engine))

        // Arbitrary user text reaches this method: spaces and ampersands must survive as data
        // rather than becoming extra query parameters.
        client.searchByTitleOrAuthor("  tolkien & lewis  ", limit = 10)

        assertEquals("tolkien & lewis", requireNotNull(request).url.parameters["q"])
    }

    @Test
    fun blankQuery_shortCircuitsWithoutSpendingARequest() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            jsonResponse(hobbitJson)
        }
        val client = OpenLibrarySearchClient(createHttpClient(engine))

        val result = client.searchByTitleOrAuthor("   ", limit = 10)

        assertTrue(result is Resource.Success, "expected Success, got $result")
        assertEquals(emptyList(), (result as Resource.Success).data)
        assertEquals(0, calls, "a blank query must not spend rate-limit budget")
    }

    @Test
    fun emptyDocs_isASuccessfulSearchThatFoundNothing() = runTest {
        // Not an error: the UI has to be able to say "no matches" rather than "search failed",
        // and a user typing a prefix legitimately matches nothing most of the time.
        val engine = MockEngine { jsonResponse("""{"numFound": 0, "start": 0, "docs": []}""") }
        val client = OpenLibrarySearchClient(createHttpClient(engine))

        val result = client.searchByTitleOrAuthor("zzzzqqqq", limit = 10)

        assertTrue(result is Resource.Success, "expected Success, got $result")
        assertEquals(emptyList(), (result as Resource.Success).data)
    }

    @Test
    fun docWithoutTitle_isDroppedWithoutFailingTheWholeSearch() = runTest {
        val json = """
            {
              "docs": [
                {"key": "/works/OL1W", "title": "Real Book"},
                {"key": "/works/OL2W"},
                {"key": "/works/OL3W", "title": "   "}
              ]
            }
        """.trimIndent()
        val engine = MockEngine { jsonResponse(json) }
        val client = OpenLibrarySearchClient(createHttpClient(engine))

        val result = client.searchByTitleOrAuthor("book", limit = 10)

        assertTrue(result is Resource.Success, "expected Success, got $result")
        val hits = (result as Resource.Success).data
        assertEquals(listOf("Real Book"), hits.map { it.title })
    }

    @Test
    fun sparseDoc_survivesWithNullsRatherThanFailing() = runTest {
        // Open Library says outright that its schema is not guaranteed stable, so a doc carrying
        // only a title must still map.
        val engine = MockEngine { jsonResponse("""{"docs": [{"title": "Bare Minimum"}]}""") }
        val client = OpenLibrarySearchClient(createHttpClient(engine))

        val result = client.searchByTitleOrAuthor("bare", limit = 10)

        val hit = (result as Resource.Success).data.single()
        assertEquals("Bare Minimum", hit.title)
        assertEquals(emptyList(), hit.authors)
        assertNull(hit.firstPublishYear)
        assertNull(hit.coverThumbnailUrl)
        assertNull(hit.workKey)
    }

    @Test
    fun blankAuthorNames_areDropped() = runTest {
        val json = """{"docs": [{"title": "T", "author_name": ["Real Author", "", "  "]}]}"""
        val engine = MockEngine { jsonResponse(json) }
        val client = OpenLibrarySearchClient(createHttpClient(engine))

        val result = client.searchByTitleOrAuthor("t", limit = 10)

        assertEquals(listOf("Real Author"), (result as Resource.Success).data.single().authors)
    }

    @Test
    fun rateLimited_surfacesTheStatusAndLogsIt() = runTest {
        // 429 is a realistic outcome for a type-ahead, not a theoretical one.
        val logger = RecordingLogger()
        val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests) }
        val client = OpenLibrarySearchClient(createHttpClient(engine), logger)

        val result = client.searchByTitleOrAuthor("hobbit", limit = 10)

        assertTrue(result is Resource.Error, "expected Error, got $result")
        assertTrue("429" in (result as Resource.Error).message, "expected the status: ${result.message}")
        val logged = logger.entries.single()
        assertEquals(LogLevel.WARN, logged.level)
        assertTrue("429" in logged.message, "expected the status in the log: ${logged.message}")
    }

    @Test
    fun malformedJson_isAnErrorNotACrash() = runTest {
        val logger = RecordingLogger()
        val engine = MockEngine { jsonResponse("{ this is not json") }
        val client = OpenLibrarySearchClient(createHttpClient(engine), logger)

        val result = client.searchByTitleOrAuthor("hobbit", limit = 10)

        assertTrue(result is Resource.Error, "expected Error, got $result")
        assertEquals(LogLevel.WARN, logger.entries.single().level)
    }

    @Test
    fun networkFailure_isAnErrorNotACrash() = runTest {
        val logger = RecordingLogger()
        val engine = MockEngine { throw RuntimeException("no network") }
        val client = OpenLibrarySearchClient(createHttpClient(engine), logger)

        val result = client.searchByTitleOrAuthor("hobbit", limit = 10)

        assertTrue(result is Resource.Error, "expected Error, got $result")
        assertEquals(LogLevel.WARN, logger.entries.single().level)
    }

    @Test
    fun failure_logsTheExceptionTypeAndNeverTheSearchQuery() = runTest {
        // The identifier rule, at the one call site in the codebase where passing the throwable
        // through would break it. A search query IS a title or an author name, it travels in the
        // query string, and Ktor puts the whole URL in its exception message -- so a plain
        // `logger.warn(TAG, e)` writes what the user is reading into the on-device log file.
        val logger = RecordingLogger()
        val leakyUrl = "https://openlibrary.org/search.json?q=the+bell+jar&fields=key&limit=10"
        val engine = MockEngine { throw HttpRequestTimeoutException(leakyUrl, 15_000L) }
        val client = OpenLibrarySearchClient(createHttpClient(engine), logger)

        val result = client.searchByTitleOrAuthor("the bell jar", limit = 10)

        val logged = logger.entries.single()
        assertEquals(LogLevel.WARN, logged.level)
        assertTrue(
            "HttpRequestTimeoutException" in logged.message,
            "the exception type is the diagnostic that replaces the message: ${logged.message}",
        )
        // Three doors the query could walk through: the message, the attached throwable, and the
        // Resource.Error a caller might log from the other end. All three stay shut.
        assertFalse("bell" in logged.message, "query leaked into the log message: ${logged.message}")
        assertNull(logged.throwable, "the throwable carries the query-bearing URL and must not be attached")
        result as Resource.Error
        assertFalse("bell" in result.message, "query leaked into the error message: ${result.message}")
        assertNull(result.cause, "a caller logging the cause would reintroduce the leak")
    }

    @Test
    fun malformedJsonFailure_alsoWithholdsTheQuery() = runTest {
        val logger = RecordingLogger()
        val engine = MockEngine { jsonResponse("{ this is not json") }
        val client = OpenLibrarySearchClient(createHttpClient(engine), logger)

        val result = client.searchByTitleOrAuthor("the bell jar", limit = 10)

        val logged = logger.entries.single()
        assertFalse("bell" in logged.message, "query leaked: ${logged.message}")
        assertNull(logged.throwable, "the parse exception can quote the payload it choked on")
        assertNull((result as Resource.Error).cause)
    }

    @Test
    fun cancellation_propagatesAndIsNotLoggedAsAProviderFailure() = runTest {
        // The rule this whole phase inherits from Task 15 Phase C, and the one most likely to be
        // broken by a well-meaning `catch (e: Exception)`. On JVM CancellationException *is* an
        // Exception, so without the rethrow this returns Resource.Error and logs a WARN -- at
        // typing speed, since every keystroke cancels the request before it. That would make the
        // log actively worse than having no logging here at all.
        val logger = RecordingLogger()
        val engine = MockEngine { throw CancellationException("superseded by the next keystroke") }
        val client = OpenLibrarySearchClient(createHttpClient(engine), logger)

        assertFailsWith<CancellationException> {
            client.searchByTitleOrAuthor("hobbi", limit = 10)
        }
        assertEquals(emptyList(), logger.entries, "a cancelled search is not a provider failure")
    }

    @Test
    fun limit_isPassedThroughRatherThanHardcoded() = runTest {
        var request: HttpRequestData? = null
        val engine = MockEngine { req ->
            request = req
            jsonResponse(hobbitJson)
        }
        val client = OpenLibrarySearchClient(createHttpClient(engine))

        client.searchByTitleOrAuthor("hobbit", limit = 3)

        assertEquals("3", requireNotNull(request).url.parameters["limit"])
    }
}
