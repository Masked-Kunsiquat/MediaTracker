package com.hub.media.core.util

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Direct tests for [AppLogger]'s own contract (ROADMAP Task 15 Phase A).
 *
 * The adoption-site tests elsewhere inject a [RecordingLogger] *directly* into the class under test,
 * deliberately bypassing [AppLogger] so no two of them can interfere via the shared singleton — which
 * means [AppLogger]'s two pieces of actual logic (the level threshold and the swappable delegate)
 * were previously never exercised. That is what this class covers.
 *
 * [AppLogger] is an `object`, so every test here mutates process-wide state. Each one therefore
 * restores the production defaults in [restoreDefaults] rather than leaving a [RecordingLogger] and a
 * lowered threshold installed for whatever runs next.
 */
class AppLoggerTest {
    @AfterTest
    fun restoreDefaults() {
        // Matches AppLogger's initial state: WARN, writing to the real platform sink.
        AppLogger.configure(minLevel = LogLevel.WARN, delegate = platformLogger())
    }

    @Test
    fun log_belowThreshold_isDroppedWithoutEvaluatingItsMessageLambda() {
        val recorder = RecordingLogger()
        AppLogger.configure(minLevel = LogLevel.WARN, delegate = recorder)
        var lambdaEvaluations = 0

        AppLogger.debug(tag = "T") {
            lambdaEvaluations++
            "debug"
        }
        AppLogger.info(tag = "T") {
            lambdaEvaluations++
            "info"
        }

        assertTrue(recorder.entries.isEmpty(), "calls below the threshold must not reach the delegate")
        // The stronger half of the claim: a suppressed call must cost nothing to *build*, not merely
        // be discarded after formatting. Counting lambda invocations is the only way to observe that.
        assertEquals(0, lambdaEvaluations, "a filtered-out call must never evaluate its message lambda")
    }

    @Test
    fun log_atOrAboveThreshold_reachesTheDelegate() {
        val recorder = RecordingLogger()
        AppLogger.configure(minLevel = LogLevel.WARN, delegate = recorder)

        AppLogger.warn(tag = "T") { "warned" }
        AppLogger.error(tag = "T") { "errored" }

        assertEquals(listOf(LogLevel.WARN, LogLevel.ERROR), recorder.entries.map { it.level })
    }

    @Test
    fun log_acceptedCall_preservesTagMessageAndThrowable() {
        val recorder = RecordingLogger()
        AppLogger.configure(minLevel = LogLevel.DEBUG, delegate = recorder)
        val cause = IllegalStateException("boom")

        AppLogger.error(tag = "SomeTag", throwable = cause) { "something failed" }

        val entry = recorder.entries.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("SomeTag", entry.tag)
        assertEquals("something failed", entry.message)
        assertSame(cause, entry.throwable, "the attached cause must be passed through unchanged")
    }

    @Test
    fun log_withoutAThrowable_passesNullThrough() {
        val recorder = RecordingLogger()
        AppLogger.configure(minLevel = LogLevel.DEBUG, delegate = recorder)

        AppLogger.warn(tag = "T") { "no cause here" }

        assertNull(recorder.entries.single().throwable)
    }

    @Test
    fun configure_loweringTheThreshold_admitsPreviouslySuppressedLevels() {
        val recorder = RecordingLogger()
        AppLogger.configure(minLevel = LogLevel.WARN, delegate = recorder)
        AppLogger.debug(tag = "T") { "suppressed" }

        AppLogger.configure(minLevel = LogLevel.DEBUG, delegate = recorder)
        AppLogger.debug(tag = "T") { "admitted" }

        assertEquals(listOf("admitted"), recorder.entries.map { it.message })
    }

    @Test
    fun configure_replacingTheDelegate_routesOnlyToTheNewOne() {
        val first = RecordingLogger()
        val second = RecordingLogger()
        AppLogger.configure(minLevel = LogLevel.DEBUG, delegate = first)
        AppLogger.error(tag = "T") { "before swap" }

        AppLogger.configure(minLevel = LogLevel.DEBUG, delegate = second)
        AppLogger.error(tag = "T") { "after swap" }

        assertEquals(
            listOf("before swap"),
            first.entries.map { it.message },
            "the replaced delegate must stop receiving calls",
        )
        assertEquals(listOf("after swap"), second.entries.map { it.message })
    }
}
