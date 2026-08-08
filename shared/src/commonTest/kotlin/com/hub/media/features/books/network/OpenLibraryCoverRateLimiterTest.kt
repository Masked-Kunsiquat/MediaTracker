package com.hub.media.features.books.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/** A [Clock] whose [now] is externally controlled, for deterministic sliding-window assertions. */
private class MutableFakeClock(startAt: Instant) : Clock {
    var current: Instant = startAt
    override fun now(): Instant = current
}

class OpenLibraryCoverRateLimiterTest {

    @Test
    fun allowsUpToTheConfiguredMaxWithinTheWindow() = runTest {
        val clock = MutableFakeClock(Instant.fromEpochMilliseconds(0))
        val limiter = OpenLibraryCoverRateLimiter(maxRequestsPerWindow = 3, window = 5.minutes, clock = clock)

        repeat(3) {
            assertEquals(RateLimitOutcome.Allowed, limiter.tryAcquire())
        }
        val fourth = limiter.tryAcquire()
        assertIs<RateLimitOutcome.Denied>(fourth)
    }

    @Test
    fun deniedOutcome_reportsRetryAfterBasedOnTheOldestRequestInTheWindow() = runTest {
        val clock = MutableFakeClock(Instant.fromEpochMilliseconds(0))
        val limiter = OpenLibraryCoverRateLimiter(maxRequestsPerWindow = 1, window = 5.minutes, clock = clock)

        limiter.tryAcquire() // consumes the only slot at t=0

        clock.current = Instant.fromEpochMilliseconds(0) + 2.minutes
        val denied = limiter.tryAcquire()

        assertIs<RateLimitOutcome.Denied>(denied)
        // The window frees up 5 minutes after the oldest (only) recorded request, and 2 minutes
        // have already elapsed, so ~3 minutes should remain.
        assertEquals(3.minutes, denied.retryAfter)
    }

    @Test
    fun slidingWindow_expiredRequestsFreeUpSlotsAutomatically() = runTest {
        val clock = MutableFakeClock(Instant.fromEpochMilliseconds(0))
        val limiter = OpenLibraryCoverRateLimiter(maxRequestsPerWindow = 1, window = 5.minutes, clock = clock)

        assertEquals(RateLimitOutcome.Allowed, limiter.tryAcquire())
        assertIs<RateLimitOutcome.Denied>(limiter.tryAcquire())

        // Advance past the window entirely -- the first request should have aged out.
        clock.current = Instant.fromEpochMilliseconds(0) + 5.minutes + 1.seconds
        assertEquals(RateLimitOutcome.Allowed, limiter.tryAcquire())
    }

    @Test
    fun recordServerRefusal_deniesEveryTryAcquireUntilItElapses_evenUnderTheLocalCap() = runTest {
        val clock = MutableFakeClock(Instant.fromEpochMilliseconds(0))
        // A generous local cap that would otherwise still allow requests -- the server refusal
        // must win regardless.
        val limiter = OpenLibraryCoverRateLimiter(maxRequestsPerWindow = 100, window = 5.minutes, clock = clock)

        limiter.recordServerRefusal(2.minutes)

        assertIs<RateLimitOutcome.Denied>(limiter.tryAcquire())

        clock.current = Instant.fromEpochMilliseconds(0) + 2.minutes + 1.seconds
        assertEquals(RateLimitOutcome.Allowed, limiter.tryAcquire())
    }

    @Test
    fun recordServerRefusal_neverShortensAnExistingLaterRefusal() = runTest {
        val clock = MutableFakeClock(Instant.fromEpochMilliseconds(0))
        val limiter = OpenLibraryCoverRateLimiter(maxRequestsPerWindow = 100, window = 5.minutes, clock = clock)

        limiter.recordServerRefusal(5.minutes)
        limiter.recordServerRefusal(1.minutes) // a shorter, later-reported refusal must not win

        clock.current = Instant.fromEpochMilliseconds(0) + 2.minutes
        // Still within the original 5-minute refusal window.
        assertIs<RateLimitOutcome.Denied>(limiter.tryAcquire())
    }
}
