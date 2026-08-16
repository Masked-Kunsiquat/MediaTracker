package com.hub.media.features.books.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Virtual-time tests for [ReadingTimer].
 *
 * [testClock] is deliberately tied to [TestScope.currentTime] — the same virtual clock `delay()`
 * advances against — rather than a fixed or manually-stepped fake. See [ReadingTimer]'s KDoc for
 * why wall-clock timestamps and tick-driven duration accumulation are independent time sources by
 * design; tying the fake clock to the coroutine scheduler's virtual time here lets these tests
 * assert on both `timestampStart`/`timestampEnd` *and* `durationSeconds`/`elapsedSeconds` from a
 * single `advanceTimeBy`/`runCurrent` driver, with no real wall clock involved anywhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingTimerTest {
    private fun TestScope.testClock(): Clock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(currentTime)
        }

    @Test
    fun elapsedSeconds_ticksOncePerSecondWhileRunning() =
        runTest {
            val timer = ReadingTimer(clock = testClock(), scope = backgroundScope)

            timer.start()
            assertEquals(0L, timer.elapsedSeconds.value)

            advanceTimeBy(3_500)
            runCurrent()
            assertEquals(3L, timer.elapsedSeconds.value)

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(4L, timer.elapsedSeconds.value)

            timer.stop()
        }

    @Test
    fun pause_freezesElapsedAndExcludesPausedTimeFromDuration() =
        runTest {
            val timer = ReadingTimer(clock = testClock(), scope = backgroundScope)

            timer.start()
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(5L, timer.elapsedSeconds.value)

            timer.pause()
            advanceTimeBy(10_000) // Paused time must not accumulate, no matter how long it lasts.
            runCurrent()
            assertEquals(5L, timer.elapsedSeconds.value)

            timer.resume()
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(8L, timer.elapsedSeconds.value)

            val result = timer.stop()
            assertEquals(8L, result.durationSeconds)
            // Wall-clock span is 18s (5 + 10 paused + 3) but only the 8 active seconds count.
            assertEquals(18_000L, (result.timestampEnd - result.timestampStart).inWholeMilliseconds)
        }

    @Test
    fun resume_continuesTickingFromWherePauseLeftOff() =
        runTest {
            val timer = ReadingTimer(clock = testClock(), scope = backgroundScope)

            timer.start()
            advanceTimeBy(2_000)
            runCurrent()
            timer.pause()
            timer.resume()

            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(4L, timer.elapsedSeconds.value)
        }

    @Test
    fun stop_returnsCorrectStartEndAndDuration() =
        runTest {
            val timer = ReadingTimer(clock = testClock(), scope = backgroundScope)

            timer.start()
            val expectedStart = Instant.fromEpochMilliseconds(currentTime)

            advanceTimeBy(2_000)
            runCurrent()

            val result = timer.stop()
            assertEquals(expectedStart, result.timestampStart)
            assertEquals(Instant.fromEpochMilliseconds(currentTime), result.timestampEnd)
            assertEquals(2L, result.durationSeconds)
        }

    @Test
    fun stop_immediatelyAfterStart_zeroSecondSessionIsValid() =
        runTest {
            val timer = ReadingTimer(clock = testClock(), scope = backgroundScope)

            timer.start()
            val result = timer.stop()

            assertEquals(0L, result.durationSeconds)
            assertEquals(result.timestampStart, result.timestampEnd)
        }

    @Test
    fun start_whenAlreadyRunning_throws() =
        runTest {
            val timer = ReadingTimer(clock = testClock(), scope = backgroundScope)
            timer.start()

            assertFailsWith<IllegalStateException> { timer.start() }
        }

    @Test
    fun start_whenPaused_throws() =
        runTest {
            val timer = ReadingTimer(clock = testClock(), scope = backgroundScope)
            timer.start()
            timer.pause()

            assertFailsWith<IllegalStateException> { timer.start() }
        }

    @Test
    fun stop_whenIdle_throws() =
        runTest {
            val timer = ReadingTimer(clock = testClock(), scope = backgroundScope)

            assertFailsWith<IllegalStateException> { timer.stop() }
        }

    @Test
    fun pause_whenIdle_throws() =
        runTest {
            val timer = ReadingTimer(clock = testClock(), scope = backgroundScope)

            assertFailsWith<IllegalStateException> { timer.pause() }
        }

    @Test
    fun pause_whenAlreadyPaused_throws() =
        runTest {
            val timer = ReadingTimer(clock = testClock(), scope = backgroundScope)
            timer.start()
            timer.pause()

            assertFailsWith<IllegalStateException> { timer.pause() }
        }

    @Test
    fun resume_whenRunning_throws() =
        runTest {
            val timer = ReadingTimer(clock = testClock(), scope = backgroundScope)
            timer.start()

            assertFailsWith<IllegalStateException> { timer.resume() }
        }

    @Test
    fun resume_whenIdle_throws() =
        runTest {
            val timer = ReadingTimer(clock = testClock(), scope = backgroundScope)

            assertFailsWith<IllegalStateException> { timer.resume() }
        }

    @Test
    fun state_reflectsLifecycle() =
        runTest {
            val timer = ReadingTimer(clock = testClock(), scope = backgroundScope)

            assertIs<ReadingTimerState.Idle>(timer.state.value)
            timer.start()
            assertIs<ReadingTimerState.Running>(timer.state.value)
            timer.pause()
            assertIs<ReadingTimerState.Paused>(timer.state.value)
            timer.resume()
            assertIs<ReadingTimerState.Running>(timer.state.value)
            timer.stop()
            assertIs<ReadingTimerState.Idle>(timer.state.value)
        }

    @Test
    fun timerCanBeReusedAfterStop() =
        runTest {
            val timer = ReadingTimer(clock = testClock(), scope = backgroundScope)

            timer.start()
            advanceTimeBy(2_000)
            runCurrent()
            timer.stop()

            timer.start()
            advanceTimeBy(4_000)
            runCurrent()
            val result = timer.stop()

            assertEquals(4L, result.durationSeconds)
            assertEquals(0L, timer.elapsedSeconds.value)
            assertIs<ReadingTimerState.Idle>(timer.state.value)
        }
}
