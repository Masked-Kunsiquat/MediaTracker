package com.hub.media.core.network

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A [Clock] whose [now] is externally controlled -- matches `OpenLibraryCoverRateLimiterTest`. */
private class MutableFakeClock(
    startAt: Instant,
) : Clock {
    var current: Instant = startAt

    override fun now(): Instant = current
}

/**
 * A harness that makes pacing observable without waiting in real time: every sleep the pacer asks
 * for is recorded *and* applied to the same fake clock, so the pacer sees time pass exactly as it
 * would in production and the test sees the gaps it asked for.
 */
private class PacerHarness(
    interval: Duration,
) {
    val clock = MutableFakeClock(Instant.fromEpochMilliseconds(0))
    val sleeps = mutableListOf<Duration>()
    val pacer =
        RequestPacer(
            minInterval = interval,
            clock = clock,
            sleep = { requested ->
                sleeps += requested
                clock.current += requested
            },
        )

    /** Simulates work between requests, the way a real caller spends time on the response. */
    fun elapse(duration: Duration) {
        clock.current += duration
    }
}

class RequestPacerTest {
    @Test
    fun theFirstRequestNeverWaits() =
        runTest {
            val h = PacerHarness(300.milliseconds)

            h.pacer.acquire()

            assertEquals(emptyList(), h.sleeps, "a lone lookup must not pay an interval up front")
        }

    @Test
    fun aBackToBackRequestWaitsTheWholeInterval() =
        runTest {
            val h = PacerHarness(300.milliseconds)

            h.pacer.acquire()
            h.pacer.acquire()

            assertEquals(listOf(300.milliseconds), h.sleeps)
        }

    @Test
    fun aRequestThatArrivesLateWaitsNotAtAll() =
        runTest {
            val h = PacerHarness(300.milliseconds)

            h.pacer.acquire()
            // The caller spent longer on the previous response than the interval, so the rate is
            // already being honoured and adding delay on top would just be slower for nothing.
            h.elapse(1.seconds)
            h.pacer.acquire()

            assertEquals(emptyList(), h.sleeps)
        }

    @Test
    fun aPartialGapIsToppedUpRatherThanPaidTwice() =
        runTest {
            val h = PacerHarness(300.milliseconds)

            h.pacer.acquire()
            h.elapse(100.milliseconds)
            h.pacer.acquire()

            assertEquals(listOf(200.milliseconds), h.sleeps, "only the remainder of the gap is owed")
        }

    /**
     * The regression guard for the drift bug the implementation comment describes: if a grant were
     * recorded when the sleep *finished* rather than when it came due, every late wake-up would push
     * the next slot later still and a long crawl would run steadily under its own budget.
     */
    @Test
    fun lateWakeUpsDoNotAccumulateIntoDrift() =
        runTest {
            val clock = MutableFakeClock(Instant.fromEpochMilliseconds(0))
            val pacer =
                RequestPacer(
                    minInterval = 300.milliseconds,
                    clock = clock,
                    // Every sleep overshoots by 50ms, the way a real scheduler does.
                    sleep = { clock.current += it + 50.milliseconds },
                )

            val start = clock.current
            repeat(10) { pacer.acquire() }

            // Nine gaps of 300ms is the floor; the overshoot is real elapsed time and cannot be
            // undone, but it must not compound -- each grant is due 300ms after the previous
            // grant, not 350ms after the previous wake-up.
            val elapsed = clock.current - start
            assertTrue(
                elapsed <= 300.milliseconds * 9 + 50.milliseconds,
                "ten paced requests took $elapsed; drift is compounding",
            )
        }

    @Test
    fun tenRequestsHoldTheDocumentedOpenLibraryRate() =
        runTest {
            val clock = MutableFakeClock(Instant.fromEpochMilliseconds(0))
            val pacer =
                openLibraryIdentifiedPacer(clock = clock, sleep = { clock.current += it })

            val start = clock.current
            repeat(10) { pacer.acquire() }
            val elapsed = clock.current - start

            // Nine gaps of one interval. Derived from the documented constant rather than written
            // as a literal, so changing the rate moves this with it instead of breaking it -- and
            // expressed as nine times the interval rather than as "three seconds", because
            // 1s / 3 is not exactly representable and nine of them are three nanoseconds short.
            val interval = 1.seconds / OPEN_LIBRARY_IDENTIFIED_REQUESTS_PER_SECOND
            assertEquals(interval * 9, elapsed)
        }
}
