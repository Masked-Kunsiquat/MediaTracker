package com.hub.media.core.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Spaces outgoing requests so a bulk pass stays inside a provider's documented per-second rate.
 *
 * ### Why this is not [com.hub.media.features.books.network.OpenLibraryCoverRateLimiter]
 * That class governs a *quota* -- a fixed number of requests per rolling window -- and answers
 * fail-fast: `tryAcquire` returns `Denied` and the caller must not issue the request at all. That is
 * the right shape for a budget you can genuinely exhaust, where continuing means being refused by
 * the server anyway.
 *
 * A *rate* is a different thing and wants the opposite answer. Open Library allows 3 requests per
 * second for identified traffic; you cannot "run out" of that, you can only go too fast. So the
 * useful response to arriving early is to wait, not to give up: [acquire] suspends until the next
 * slot is due and then returns. A crawl paced this way takes longer and still completes, where one
 * that refused would leave books unresolved and report itself as finished -- silent partial results
 * being this codebase's recurring failure mode, not an acceptable cost of politeness.
 *
 * Both exist because both situations exist, and a caller subject to a quota *and* a rate needs both.
 *
 * ### Not a general-purpose scheduler
 * Fairness is FIFO only to the extent [Mutex] is: callers are serialised, and each computes its own
 * due time from the last grant. That is sufficient for the sequential crawls this exists for and is
 * not a claim about behaviour under heavy concurrent contention.
 *
 * @param minInterval Smallest gap between two grants. Derive it from a documented rate rather than
 *   picking a number -- see [openLibraryIdentifiedPacer].
 * @param clock Source of "now"; overridable for deterministic tests, matching
 *   [com.hub.media.features.books.network.OpenLibraryCoverRateLimiter]'s convention.
 * @param sleep How to wait out a gap. Injectable for the same reason [clock] is: a test that also
 *   advances [clock] from here gets deterministic pacing with no real elapsed time.
 */
public class RequestPacer(
    private val minInterval: Duration,
    private val clock: Clock = Clock.System,
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var lastGrantedAt: Instant? = null

    /**
     * Suspends until this caller may issue its request, then records the grant and returns.
     *
     * The first call never waits -- pacing is about the gap *between* requests, and making a lone
     * interactive-feeling lookup pay a full interval up front would be latency for nothing.
     */
    public suspend fun acquire() {
        val waitFor =
            mutex.withLock {
                val now = clock.now()
                val last = lastGrantedAt
                // The grant is recorded at the time it becomes due, not at the time the sleep
                // finishes. Recording after waking would let scheduler jitter accumulate: every
                // late wake-up would push the next slot later still, so a long crawl would drift
                // steadily slower than the rate it is supposed to be holding.
                val dueAt = if (last == null) now else maxOf(now, last + minInterval)
                lastGrantedAt = dueAt
                dueAt - now
            }

        if (waitFor > Duration.ZERO) {
            sleep(waitFor)
        }
    }
}

/**
 * Open Library's documented ceiling for traffic that identifies itself with a `User-Agent`, which
 * this app always does ([USER_AGENT], installed for every request in [createHttpClient]).
 *
 * Unidentified traffic is held to 1 request per second instead. The app's compliance with the
 * higher number is therefore contingent on the header, which is why the two are documented
 * together: drop the `UserAgent` plugin and this pacer becomes three times too fast without
 * anything failing to compile.
 *
 * See <https://openlibrary.org/developers/api>.
 */
public const val OPEN_LIBRARY_IDENTIFIED_REQUESTS_PER_SECOND: Int = 3

/**
 * A [RequestPacer] sized to [OPEN_LIBRARY_IDENTIFIED_REQUESTS_PER_SECOND].
 *
 * Intended for bulk passes over openlibrary.org only. The interactive single-book paths deliberately
 * go unpaced: one lookup at a time is nowhere near the ceiling, and spending user-visible latency to
 * protect a background crawl is the wrong trade. That does mean a lookup issued *during* a backfill
 * is not counted against the crawl's pacing -- accepted knowingly, because the crawl is already the
 * overwhelming majority of the traffic and the alternative slows down the only request a person is
 * actually waiting on.
 */
public fun openLibraryIdentifiedPacer(
    clock: Clock = Clock.System,
    sleep: suspend (Duration) -> Unit = { delay(it) },
): RequestPacer =
    RequestPacer(
        minInterval = 1.seconds / OPEN_LIBRARY_IDENTIFIED_REQUESTS_PER_SECOND,
        clock = clock,
        sleep = sleep,
    )
