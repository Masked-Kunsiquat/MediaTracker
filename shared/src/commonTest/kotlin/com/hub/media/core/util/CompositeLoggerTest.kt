package com.hub.media.core.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [CompositeLogger] (ROADMAP Task 15 Phase B): fan-out to every delegate, and the
 * "a logging call must never itself become a new source of failure" rule from Phase A -- a
 * throwing delegate must neither block its siblings from receiving the call nor propagate to
 * [CompositeLogger]'s own caller.
 */
class CompositeLoggerTest {
    @Test
    fun log_withMultipleDelegates_forwardsTheCallToEveryDelegate() {
        val first = RecordingLogger()
        val second = RecordingLogger()
        val composite = CompositeLogger(listOf(first, second))

        composite.log(LogLevel.WARN, "T", null) { "hello" }

        assertEquals("hello", first.entries.single().message)
        assertEquals("hello", second.entries.single().message)
    }

    @Test
    fun log_withMultipleDelegates_evaluatesTheMessageLambdaExactlyOnce() {
        var evaluations = 0
        val composite = CompositeLogger(listOf(RecordingLogger(), RecordingLogger(), RecordingLogger()))

        composite.log(LogLevel.INFO, "T", null) {
            evaluations++
            "msg"
        }

        assertEquals(1, evaluations, "the lambda must be evaluated once up front, not per delegate")
    }

    @Test
    fun log_firstDelegateThrows_secondDelegateStillReceivesTheCallAndNoExceptionPropagates() {
        val throwing =
            object : Logger {
                override fun log(
                    level: LogLevel,
                    tag: String,
                    throwable: Throwable?,
                    message: () -> String,
                ): Unit = throw RuntimeException("delegate exploded")
            }
        val healthy = RecordingLogger()
        val composite = CompositeLogger(listOf(throwing, healthy))

        // Must not throw.
        composite.log(LogLevel.ERROR, "T", null) { "must still arrive" }

        assertEquals("must still arrive", healthy.entries.single().message)
    }

    @Test
    fun log_secondDelegateThrows_firstDelegateAlreadyReceivedTheCallAndNoExceptionPropagates() {
        val healthy = RecordingLogger()
        val throwing =
            object : Logger {
                override fun log(
                    level: LogLevel,
                    tag: String,
                    throwable: Throwable?,
                    message: () -> String,
                ): Unit = throw RuntimeException("delegate exploded")
            }
        val composite = CompositeLogger(listOf(healthy, throwing))

        // Must not throw.
        composite.log(LogLevel.ERROR, "T", null) { "must still arrive" }

        assertEquals("must still arrive", healthy.entries.single().message)
    }

    @Test
    fun withPlatformLogger_composesThisLoggerWithThePlatformSink_bothReceiveTheCall() {
        val recorder = RecordingLogger()

        // withPlatformLogger() composes [platformLogger()] (the real android.util.Log/stdout
        // sink) with `this` -- can't assert on the platform sink's output directly, but this
        // proves the recorder passed in as `this` is still reached through the composition, and
        // that composing with the real platform sink doesn't throw.
        val composed = recorder.withPlatformLogger()
        composed.log(LogLevel.WARN, "T", null) { "composed call" }

        assertEquals("composed call", recorder.entries.single().message)
    }
}
