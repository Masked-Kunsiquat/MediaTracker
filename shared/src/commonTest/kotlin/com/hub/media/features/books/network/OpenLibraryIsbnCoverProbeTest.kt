package com.hub.media.features.books.network

import com.hub.media.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Verifies [OpenLibraryIsbnCoverProbe]'s `?default=false` last-resort ISBN cover probe (ROADMAP
 * Task 6 Phase E): a 404 (Open Library's real "no cover for this ISBN" response, thanks to
 * `?default=false` suppressing its usual placeholder-image response) must not surface a URL, while
 * a 2xx does.
 */
class OpenLibraryIsbnCoverProbeTest {

    @Test
    fun coverExists_returns200_probeReturnsTheUrl() = runTest {
        val engine = MockEngine { _ ->
            respond(content = ByteArray(4), status = HttpStatusCode.OK)
        }
        val probe = OpenLibraryIsbnCoverProbe(createHttpClient(engine))

        val result = probe.probeCoverUrl("9780547928227")

        assertEquals(
            "https://covers.openlibrary.org/b/isbn/9780547928227-L.jpg?default=false",
            result,
        )
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun noCover_returns404_probeReturnsNull() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.NotFound) }
        val probe = OpenLibraryIsbnCoverProbe(createHttpClient(engine))

        val result = probe.probeCoverUrl("9780547928227")

        assertNull(result)
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun probeIssuesHeadRequest_notGet() = runTest {
        // A GET against a cover URL would buffer the whole image just to read its status code;
        // this probe only ever needs the status, so it must stay a HEAD.
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Head, request.method)
            respondError(HttpStatusCode.NotFound)
        }
        val probe = OpenLibraryIsbnCoverProbe(createHttpClient(engine))

        probe.probeCoverUrl("9780547928227")

        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun requestUrl_includesDefaultFalseQueryParam() = runTest {
        val engine = MockEngine { request ->
            assertTrue(request.url.toString().contains("default=false"))
            respondError(HttpStatusCode.NotFound)
        }
        val probe = OpenLibraryIsbnCoverProbe(createHttpClient(engine))

        probe.probeCoverUrl("9780547928227")

        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun networkFailure_returnsNullRatherThanThrowing() = runTest {
        val engine = MockEngine { _ -> throw RuntimeException("network down") }
        val probe = OpenLibraryIsbnCoverProbe(createHttpClient(engine))

        val result = probe.probeCoverUrl("9780547928227")

        assertNull(result)
    }
}
