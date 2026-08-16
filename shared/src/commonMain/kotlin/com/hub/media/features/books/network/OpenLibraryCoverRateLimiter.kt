package com.hub.media.features.books.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Open Library's stated cover-lookup quota: 100 requests per IP, per rolling 5-minute window. */
public const val OPEN_LIBRARY_COVER_QUOTA_MAX_REQUESTS: Int = 100
public val OPEN_LIBRARY_COVER_QUOTA_WINDOW: Duration = 5.minutes

/**
 * Outcome of [OpenLibraryCoverRateLimiter.tryAcquire]: either a slot was consumed and the caller
 * may proceed, or the budget is currently exhausted and the caller should treat the request as
 * refused without ever reaching the network.
 */
public sealed class RateLimitOutcome {
    /** A slot was consumed; the caller may issue its request now. */
    public data object Allowed : RateLimitOutcome()

    /** No slots are available right now. [retryAfter] estimates when one will free up. */
    public data class Denied(
        public val retryAfter: Duration,
    ) : RateLimitOutcome()
}

/**
 * Shared token-bucket-style limiter for [OpenLibraryIsbnCoverProbe]'s `?default=false` ISBN cover
 * probe (ROADMAP Task 14 Phase A).
 *
 * ### Why this exists as its own class rather than state inside the probe
 * Open Library's ISBN-keyed cover quota (100 requests / IP / 5 minutes — see
 * [OPEN_LIBRARY_COVER_QUOTA_MAX_REQUESTS]/[OPEN_LIBRARY_COVER_QUOTA_WINDOW]) is consumed by *every*
 * caller of the probe from the same device, not per call site: the interactive per-book "re-fetch
 * cover" affordance ([com.hub.media.features.books.domain.RefetchCoverUseCase]), the "add book by
 * ISBN" flow ([com.hub.media.features.books.domain.AddBookByIsbnUseCase], which also falls through
 * to this same last-resort probe), and the bulk backfill
 * ([com.hub.media.features.books.domain.BulkBackfillUseCase]) must all draw on the *same* budget —
 * a bulk-only limiter would let a user tapping "re-fetch cover" mid-backfill silently push the
 * combined total over Open Library's limit while the backfill takes the blame. Every one of those
 * call sites is expected to be handed the exact same [OpenLibraryCoverRateLimiter] instance (see
 * `AppContainer`'s wiring) rather than constructing its own.
 *
 * ### Two layers of protection
 * 1. **Local sliding window** ([tryAcquire]): tracks the timestamps of this device's own recent
 *    probe requests and self-throttles to stay under [maxRequestsPerWindow] within [window] —
 *    proactive, and free (no network round-trip) once exhausted.
 * 2. **Server-asserted backoff** ([recordServerRefusal]): if Open Library itself returns a 429 (or
 *    5xx, treated the same way — see [OpenLibraryIsbnCoverProbe]'s KDoc), the caller reports that
 *    refusal here so [tryAcquire] denies *every* further request until the server-stated
 *    `Retry-After` elapses, even if this device's own local count hadn't yet reached
 *    [maxRequestsPerWindow] (a fresh process, clock drift, or an undercount from a crashed prior
 *    run could otherwise let local tracking under-estimate how close to the real limit this IP
 *    already is).
 *
 * @param maxRequestsPerWindow Local self-imposed cap, matching Open Library's documented quota.
 * @param window Rolling window [maxRequestsPerWindow] is measured over.
 * @param clock Source of "now"; overridable for deterministic tests (matches
 *   [com.hub.media.features.books.data.BookRepository]'s injected-[Clock] convention).
 */
public class OpenLibraryCoverRateLimiter(
    private val maxRequestsPerWindow: Int = OPEN_LIBRARY_COVER_QUOTA_MAX_REQUESTS,
    private val window: Duration = OPEN_LIBRARY_COVER_QUOTA_WINDOW,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private val requestTimestamps = ArrayDeque<Instant>()
    private var blockedUntil: Instant? = null

    /**
     * Attempts to consume one slot of quota. Returns [RateLimitOutcome.Allowed] (and records the
     * request) if under budget, or [RateLimitOutcome.Denied] with an estimated wait if not — in
     * which case the caller must NOT issue the underlying HTTP request at all.
     */
    public suspend fun tryAcquire(): RateLimitOutcome =
        mutex.withLock {
            val now = clock.now()

            blockedUntil?.let { until ->
                if (now < until) return@withLock RateLimitOutcome.Denied(until - now)
                blockedUntil = null
            }

            while (requestTimestamps.isNotEmpty() && now - requestTimestamps.first() >= window) {
                requestTimestamps.removeFirst()
            }

            return@withLock if (requestTimestamps.size < maxRequestsPerWindow) {
                requestTimestamps.addLast(now)
                RateLimitOutcome.Allowed
            } else {
                val retryAfter = window - (now - requestTimestamps.first())
                RateLimitOutcome.Denied(retryAfter)
            }
        }

    /**
     * Records that the server itself refused a request with a rate-limit signal (429, or a 5xx
     * treated as a transient refusal — see [OpenLibraryIsbnCoverProbe]). Every [tryAcquire] call
     * for the next [retryAfter] is denied regardless of the local sliding window's own count, so a
     * confirmed server-side refusal always wins over local (possibly stale/under-counted) tracking.
     * Never shortens an existing, later refusal already in effect.
     */
    public suspend fun recordServerRefusal(retryAfter: Duration) =
        mutex.withLock {
            val candidate = clock.now() + retryAfter
            val current = blockedUntil
            if (current == null || candidate > current) {
                blockedUntil = candidate
            }
        }
}
