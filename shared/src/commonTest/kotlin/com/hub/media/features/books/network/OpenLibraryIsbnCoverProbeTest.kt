package com.hub.media.features.books.network

import com.hub.media.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Verifies [OpenLibraryIsbnCoverProbe]'s `?default=false` last-resort ISBN cover probe (ROADMAP
 * Task 6 Phase E / Task 14 Phase A). Assertions were updated from the original bare-`String?`
 * return to [CoverProbeResult] (ROADMAP Task 14 Phase A's required behavior change: a 429/5xx can no
 * longer be indistinguishable from a confirmed 404 -- see [CoverProbeResult]'s KDoc) --
 * [coverExists_returns200_probeReturnsTheUrl] and [noCover_returns404_probeReturnsNull] are the same
 * two pre-existing cases, just asserting the new sealed type; every other test in this file is new.
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
            CoverProbeResult.Found("https://covers.openlibrary.org/b/isbn/9780547928227-L.jpg?default=false"),
            result,
        )
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun noCover_returns404_probeReturnsNotFound() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.NotFound) }
        val probe = OpenLibraryIsbnCoverProbe(createHttpClient(engine))

        val result = probe.probeCoverUrl("9780547928227")

        assertEquals(CoverProbeResult.NotFound, result)
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
    fun networkFailure_returnsNotFoundRatherThanThrowing() = runTest {
        val engine = MockEngine { _ -> throw RuntimeException("network down") }
        val probe = OpenLibraryIsbnCoverProbe(createHttpClient(engine))

        val result = probe.probeCoverUrl("9780547928227")

        assertEquals(CoverProbeResult.NotFound, result)
    }

    /**
     * ROADMAP Task 14 Phase A's core behavior change: a 429 must NOT be reported the same as a 404
     * -- the pre-Task-14 probe mapped every non-2xx to `null` (now [CoverProbeResult.NotFound]),
     * which would have permanently marked a book coverless over what is actually a temporary quota
     * refusal.
     */
    @Test
    fun rateLimited429_returnsRateLimitedNotNotFound() = runTest {
        val engine = MockEngine { _ ->
            respondError(HttpStatusCode.TooManyRequests, headers = headersOf(HttpHeaders.RetryAfter, "120"))
        }
        val probe = OpenLibraryIsbnCoverProbe(createHttpClient(engine))

        val result = probe.probeCoverUrl("9780547928227")

        assertIs<CoverProbeResult.RateLimited>(result)
        assertEquals(120.seconds, result.retryAfter)
    }

    @Test
    fun rateLimited429_withoutRetryAfterHeader_fallsBackToDefaultWindow() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.TooManyRequests) }
        val probe = OpenLibraryIsbnCoverProbe(createHttpClient(engine))

        val result = probe.probeCoverUrl("9780547928227")

        assertIs<CoverProbeResult.RateLimited>(result)
        assertEquals(OPEN_LIBRARY_COVER_QUOTA_WINDOW, result.retryAfter)
    }

    @Test
    fun serverError5xx_treatedAsRateLimitedNotNotFound() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.ServiceUnavailable) }
        val probe = OpenLibraryIsbnCoverProbe(createHttpClient(engine))

        val result = probe.probeCoverUrl("9780547928227")

        assertIs<CoverProbeResult.RateLimited>(result)
    }

    @Test
    fun a429_recordsServerRefusalOnTheSharedLimiter_soASubsequentCallIsDeniedWithoutANetworkRequest() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            respondError(HttpStatusCode.TooManyRequests, headers = headersOf(HttpHeaders.RetryAfter, "300"))
        }
        val fixedNow = Instant.fromEpochMilliseconds(0)
        val clock = object : Clock {
            override fun now(): Instant = fixedNow
        }
        val limiter = OpenLibraryCoverRateLimiter(clock = clock)
        val probe = OpenLibraryIsbnCoverProbe(createHttpClient(engine), limiter)

        val first = probe.probeCoverUrl("9780547928227")
        assertIs<CoverProbeResult.RateLimited>(first)
        assertEquals(1, callCount, "the first call must reach the network to learn about the 429")

        // A second probe, for a different ISBN, right after: the shared limiter's recorded server
        // refusal must deny it locally -- no second HTTP request should ever be issued.
        val second = probe.probeCoverUrl("9780140449136")
        assertIs<CoverProbeResult.RateLimited>(second)
        assertEquals(1, callCount, "a subsequent call while server-refused must not reach the network at all")
    }

    @Test
    fun localQuotaExhausted_deniesWithoutIssuingTheHttpRequest() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            respond(content = ByteArray(4), status = HttpStatusCode.OK)
        }
        val fixedNow = Instant.fromEpochMilliseconds(0)
        val clock = object : Clock {
            override fun now(): Instant = fixedNow
        }
        // A tiny window (1 request) makes exhaustion trivial to trigger deterministically.
        val limiter = OpenLibraryCoverRateLimiter(maxRequestsPerWindow = 1, window = 5.minutes, clock = clock)
        val probe = OpenLibraryIsbnCoverProbe(createHttpClient(engine), limiter)

        val first = probe.probeCoverUrl("9780547928227")
        assertIs<CoverProbeResult.Found>(first)
        assertEquals(1, callCount)

        val second = probe.probeCoverUrl("9780140449136")
        assertIs<CoverProbeResult.RateLimited>(second)
        assertEquals(1, callCount, "the local quota was already exhausted, so no HTTP request should be issued")
    }

    /**
     * The whole point of extracting [OpenLibraryCoverRateLimiter] as an injected, shareable
     * dependency (ROADMAP Task 14 Phase A hard requirement: "one shared rate limiter... not a
     * bulk-only one"): two independent [OpenLibraryIsbnCoverProbe] instances -- standing in for the
     * interactive per-book re-fetch and the bulk backfill, which are separate call sites in
     * production -- constructed with the *same* limiter instance must draw on one combined budget.
     */
    @Test
    fun sharedRateLimiter_throttlesAcrossTwoIndependentProbeInstances() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            respond(content = ByteArray(4), status = HttpStatusCode.OK)
        }
        val fixedNow = Instant.fromEpochMilliseconds(0)
        val clock = object : Clock {
            override fun now(): Instant = fixedNow
        }
        val sharedLimiter = OpenLibraryCoverRateLimiter(maxRequestsPerWindow = 1, window = 5.minutes, clock = clock)
        val httpClient = createHttpClient(engine)
        val interactiveCallerProbe = OpenLibraryIsbnCoverProbe(httpClient, sharedLimiter)
        val bulkCallerProbe = OpenLibraryIsbnCoverProbe(httpClient, sharedLimiter)

        val fromInteractiveCaller = interactiveCallerProbe.probeCoverUrl("9780547928227")
        assertIs<CoverProbeResult.Found>(fromInteractiveCaller)

        // A different probe *instance*, sharing only the limiter -- this is what a bulk-only
        // limiter would have gotten wrong: it would have let this second call through even though
        // the combined per-IP budget was already spent by the first (interactive) caller.
        val fromBulkCaller = bulkCallerProbe.probeCoverUrl("9780140449136")
        assertIs<CoverProbeResult.RateLimited>(fromBulkCaller)
        assertEquals(1, callCount, "the second instance must be denied by the shared budget, not get its own")
    }
}
