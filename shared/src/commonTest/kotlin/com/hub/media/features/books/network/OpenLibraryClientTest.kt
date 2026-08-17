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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun happyPath_parsesFullMetadataIncludingAuthorSubFetch() =
        runTest {
            val editionJson =
                """
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

            val engine =
                MockEngine { request ->
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
    fun notFound_returnsError() =
        runTest {
            val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
            val client = OpenLibraryClient(createHttpClient(engine))

            val result = client.fetchByIsbn("0000000000")

            assertTrue(result is Resource.Error, "expected Error, got $result")
        }

    @Test
    fun malformedJson_returnsError() =
        runTest {
            val engine = MockEngine { jsonResponse("{ this is not valid json ") }
            val client = OpenLibraryClient(createHttpClient(engine))

            val result = client.fetchByIsbn("9780547928227")

            assertTrue(result is Resource.Error, "expected Error, got $result")
        }

    @Test
    fun missingOptionalFields_returnsSuccessWithNulls() =
        runTest {
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
    fun authorSubFetchFailure_returnsSuccessWithEmptyAuthors() =
        runTest {
            val editionJson =
                """
                {
                  "title": "The Hobbit",
                  "authors": [{"key": "/authors/OL26320A"}]
                }
                """.trimIndent()
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.contains("/isbn/") -> jsonResponse(editionJson)
                        request.url.encodedPath.contains(
                            "/authors/",
                        ) -> respondError(HttpStatusCode.InternalServerError)
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

    /**
     * The real Mariner 75th Anniversary Hobbit payload, trimmed. Its edition record has **no**
     * `authors` key at all — this is the exact response that shipped a bookless-author into the
     * library on device, and the reason work-level lookup exists.
     */
    private val editionWithoutAuthorsJson =
        """
        {
          "works": [{"key": "/works/OL27482W"}],
          "title": "The Hobbit",
          "publish_date": "2012",
          "key": "/books/OL33891995M",
          "covers": [12003329],
          "number_of_pages": 300
        }
        """.trimIndent()

    /** A work's author refs nest one level deeper than an edition's — see OpenLibraryWorkDto. */
    private val workJson =
        """
        {
          "authors": [
            {"author": {"key": "/authors/OL26320A"}, "type": {"key": "/type/author_role"}}
          ]
        }
        """.trimIndent()

    @Test
    fun editionWithoutAuthors_fallsBackToTheWorkRecord() =
        runTest {
            // The device-found bug. Open Library hangs authorship off the work, and many edition
            // records omit `authors` entirely; reading only the edition dropped the author silently,
            // with nothing logged, because an absence is not a failure.
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.contains("/isbn/") -> jsonResponse(editionWithoutAuthorsJson)
                        request.url.encodedPath.contains("/works/") -> jsonResponse(workJson)
                        request.url.encodedPath.contains("/authors/") ->
                            jsonResponse("""{"name": "J.R.R. Tolkien"}""")
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }
            val client = OpenLibraryClient(createHttpClient(engine))

            val result = client.fetchByIsbn("9780547928227")

            val metadata = (result as Resource.Success).data
            assertEquals(listOf("J.R.R. Tolkien"), metadata.authors)
        }

    @Test
    fun workFallback_isTraced_soASilentRecoveryIsStillVisible() =
        runTest {
            // The bug this fix addresses was invisible because nothing failed -- an edition without
            // authors is not an error, so there was nothing for Task 15's adoption to report and the
            // book just arrived blank. Tracing the *successful* fallback is what makes the recovery
            // observable at all; without it the only way to tell the fix is working is to inspect the
            // database.
            val recorder = RecordingLogger()
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.contains("/isbn/") -> jsonResponse(editionWithoutAuthorsJson)
                        request.url.encodedPath.contains("/works/") -> jsonResponse(workJson)
                        request.url.encodedPath.contains("/authors/") ->
                            jsonResponse("""{"name": "J.R.R. Tolkien"}""")
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }
            val client = OpenLibraryClient(createHttpClient(engine), logger = recorder)

            client.fetchByIsbn("9780547928227")

            val info = recorder.entries.single { it.level == LogLevel.INFO }
            assertTrue(
                info.message.contains("/works/OL27482W"),
                "the key is what makes it diagnosable: ${info.message}",
            )
            assertFalse(
                info.message.contains("Tolkien"),
                "the log is user-viewable and must never carry an author name: ${info.message}",
            )
        }

    @Test
    fun editionWithItsOwnAuthors_tracesNoFallback() =
        runTest {
            // Negative control: the test above passes just as well if the client logs that line
            // unconditionally, which would report a fallback that never happened.
            val editionJson =
                """
                {
                  "works": [{"key": "/works/OL27482W"}],
                  "title": "The Hobbit",
                  "authors": [{"key": "/authors/OL26320A"}]
                }
                """.trimIndent()
            val recorder = RecordingLogger()
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.contains("/isbn/") -> jsonResponse(editionJson)
                        request.url.encodedPath.contains("/authors/") ->
                            jsonResponse("""{"name": "J.R.R. Tolkien"}""")
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }

            OpenLibraryClient(createHttpClient(engine), logger = recorder).fetchByIsbn("9780547928227")

            assertTrue(
                recorder.entries.none { it.level == LogLevel.INFO },
                "no fallback happened, so nothing should claim one did: ${recorder.entries}",
            )
        }

    @Test
    fun workKey_isCapturedEvenWhenTheEditionHasItsOwnAuthors() =
        runTest {
            // Captured at ingestion regardless of whether it was needed for authors, because it cannot
            // be recovered later without another rate-limited crawl over the whole library.
            val editionJson =
                """
                {
                  "works": [{"key": "/works/OL27482W"}],
                  "title": "The Hobbit",
                  "authors": [{"key": "/authors/OL26320A"}],
                  "key": "/books/OL33891995M"
                }
                """.trimIndent()
            var workRequests = 0
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.contains("/isbn/") -> jsonResponse(editionJson)
                        request.url.encodedPath.contains("/works/") -> {
                            workRequests++
                            jsonResponse(workJson)
                        }
                        request.url.encodedPath.contains("/authors/") ->
                            jsonResponse("""{"name": "J.R.R. Tolkien"}""")
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }
            val client = OpenLibraryClient(createHttpClient(engine))

            val metadata = (client.fetchByIsbn("9780547928227") as Resource.Success).data

            assertEquals("/works/OL27482W", metadata.workKey)
            assertEquals("/books/OL33891995M", metadata.externalId, "externalId stays the edition key")
            assertEquals(listOf("J.R.R. Tolkien"), metadata.authors)
            assertEquals(0, workRequests, "the edition already had authors; the work fetch is a fallback")
        }

    @Test
    fun editionAuthors_winOverTheWorks() =
        runTest {
            // The edition is the more specific record, and preferring it preserves the behaviour every
            // already-ingested book was added with.
            val editionJson =
                """
                {
                  "works": [{"key": "/works/OL27482W"}],
                  "title": "The Hobbit",
                  "authors": [{"key": "/authors/EDITION"}]
                }
                """.trimIndent()
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.contains("/isbn/") -> jsonResponse(editionJson)
                        request.url.encodedPath.contains("/works/") -> jsonResponse(workJson)
                        request.url.encodedPath.contains("/authors/EDITION") ->
                            jsonResponse("""{"name": "Edition Author"}""")
                        request.url.encodedPath.contains("/authors/") ->
                            jsonResponse("""{"name": "Work Author"}""")
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }
            val client = OpenLibraryClient(createHttpClient(engine))

            val metadata = (client.fetchByIsbn("9780547928227") as Resource.Success).data

            assertEquals(listOf("Edition Author"), metadata.authors)
        }

    @Test
    fun workLookupFailure_stillReturnsTheBookAndSaysWhyTheAuthorIsMissing() =
        runTest {
            val recorder = RecordingLogger()
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.contains("/isbn/") -> jsonResponse(editionWithoutAuthorsJson)
                        request.url.encodedPath.contains("/works/") -> respondError(HttpStatusCode.InternalServerError)
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }
            val client = OpenLibraryClient(createHttpClient(engine), logger = recorder)

            val result = client.fetchByIsbn("9780547928227")

            val metadata = (result as Resource.Success).data
            assertTrue(metadata.authors.isEmpty(), "a missing author must not fail the book")
            assertEquals("/works/OL27482W", metadata.workKey, "the key is known even if the fetch failed")
            val warning = recorder.entries.single { it.level == LogLevel.WARN }
            assertTrue(warning.message.contains("/works/OL27482W"), "the key makes it diagnosable")
            assertTrue(warning.message.contains("500"), "the status says why")
        }

    @Test
    fun editionWithNoWorkAtAll_returnsSuccessWithNoAuthorAndNoWorkKey() =
        runTest {
            val engine = MockEngine { jsonResponse("""{"title": "Orphan Edition"}""") }
            val client = OpenLibraryClient(createHttpClient(engine))

            val metadata = (client.fetchByIsbn("1234567890") as Resource.Success).data

            assertEquals("Orphan Edition", metadata.title)
            assertTrue(metadata.authors.isEmpty())
            assertNull(metadata.workKey)
        }

    @Test
    fun missingTitle_returnsError() =
        runTest {
            val engine = MockEngine { jsonResponse("""{"number_of_pages": 100}""") }
            val client = OpenLibraryClient(createHttpClient(engine))

            val result = client.fetchByIsbn("1234567890")

            assertTrue(result is Resource.Error, "expected Error, got $result")
        }

    @Test
    fun cancellation_duringLookup_propagatesInsteadOfBeingLoggedAsFailure() =
        runTest {
            val engine = MockEngine { throw CancellationException("scope cancelled") }
            val recorder = RecordingLogger()
            val client = OpenLibraryClient(createHttpClient(engine), logger = recorder)

            // ROADMAP Task 15 Phase C: on JVM, CancellationException *is* an Exception, so a bare
            // catch (e: Exception) here would both convert a cancelled screen into a bogus
            // Resource.Error and log it as a provider failure it never was.
            assertFailsWith<CancellationException> {
                client.fetchByIsbn("9780547928227")
            }
            assertTrue(
                recorder.entries.none { it.level == LogLevel.WARN },
                "cancellation must not be logged as a lookup failure",
            )
        }

    @Test
    fun nonSuccessStatus_surfacesTheStatusAndLogsIt() =
        runTest {
            // The primary provider on the add-book flow. A status failure here used to leave no
            // trace, so a real outage produced a user-visible error and an empty log -- backwards,
            // since the log is the artefact you reach for when the screen has already failed.
            val logger = RecordingLogger()
            val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests) }
            val client = OpenLibraryClient(createHttpClient(engine), logger)

            val result = client.fetchByIsbn("9780261102217")

            assertTrue(result is Resource.Error, "expected Error, got $result")
            assertTrue("429" in (result as Resource.Error).message, "expected the status: ${result.message}")
            val logged = logger.entries.single()
            assertEquals(LogLevel.WARN, logged.level)
            assertTrue("429" in logged.message, "expected the status in the log: ${logged.message}")
        }
}
