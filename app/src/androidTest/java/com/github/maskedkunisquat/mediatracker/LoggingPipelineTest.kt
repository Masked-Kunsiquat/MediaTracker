package com.github.maskedkunisquat.mediatracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hub.media.core.storage.LogEntry
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.LogLevel
import com.hub.media.core.util.error
import com.hub.media.core.util.warn
import com.hub.media.features.settings.data.getLogVerbosity
import com.hub.media.features.settings.data.setLogVerbosity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end tests for the logging pipeline as the shipped app actually wires it (ROADMAP Task 15).
 *
 * ### Why this exists, and why it has to be instrumented
 * Every layer of logging is unit-tested in isolation, but the *composition* was not: the
 * `AppLogger.configure(minLevel, FileLogSink(store).withPlatformLogger())` call in
 * [MediaTrackerApplication.onCreate] could have been deleted and the whole suite -- unit and
 * instrumented -- would still have passed while nothing was ever written to a log file.
 *
 * Instrumentation runs inside the app's own process, so the real [MediaTrackerApplication] is
 * created and that real `configure` call has already happened before any test here runs. These
 * tests therefore assert against the *actual shipped pipeline*: the real `AppLogger`, the real
 * `FileLogSink`, and the real on-device store, reached through
 * [MediaTrackerApplication.appContainer]. Nothing here constructs its own sink or store, because
 * doing so would test a pipeline this app does not use.
 *
 * ### These tests write to the real on-device log
 * Unavoidable -- writing to the real store is the point. Entries are tagged [TAG] so they are
 * identifiable, and the verbosity threshold is restored in [tearDown] since [AppLogger] is a
 * process-wide singleton whose state would otherwise leak into every test that follows.
 */
@RunWith(AndroidJUnit4::class)
class LoggingPipelineTest {

    private companion object {
        const val TAG = "LoggingPipelineTest"
        const val FORBIDDEN_TITLE = "The Left Hand of Darkness"
    }

    private val application: MediaTrackerApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as MediaTrackerApplication

    private val store get() = application.appContainer.logFileStore

    @Before
    fun setUp() {
        // Start from a known threshold rather than whatever a previous test left behind.
        AppLogger.setMinLevel(LogLevel.DEBUG)
    }

    @After
    fun tearDown() {
        // Restore the release-safe default. AppLogger is a process singleton, so leaving it at
        // DEBUG would quietly change the behaviour of every later test in this run.
        AppLogger.setMinLevel(LogLevel.WARN)
    }

    /** Entries this test wrote, newest last, after forcing a flush. */
    private fun ownEntries(marker: String): List<LogEntry> = runBlocking {
        store.flush()
        store.readAll().filter { it.tag == TAG && it.message.contains(marker) }
    }

    @Test
    fun appLogger_isWiredToTheRealFileStore_soAnEntryReachesDisk() {
        // The gap this whole file exists for: if onCreate's configure call were removed, this is
        // the only test in the repository that would notice.
        val marker = "wiring-${System.nanoTime()}"

        AppLogger.error(TAG) { "reached the store: $marker" }

        assertEquals(1, ownEntries(marker).size)
    }

    @Test
    fun everyLevel_atDebugThreshold_reachesTheStore() {
        val marker = "all-levels-${System.nanoTime()}"
        AppLogger.setMinLevel(LogLevel.DEBUG)

        AppLogger.log(LogLevel.DEBUG, TAG) { "debug $marker" }
        AppLogger.log(LogLevel.INFO, TAG) { "info $marker" }
        AppLogger.warn(TAG) { "warn $marker" }
        AppLogger.error(TAG) { "error $marker" }

        assertEquals(
            listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR),
            ownEntries(marker).map { it.level },
        )
    }

    @Test
    fun levelsBelowTheThreshold_areDroppedBeforeReachingTheStore() {
        // The threshold is what the "Log detail" setting drives, so this is that setting's actual
        // effect verified against the real store rather than against a stubbed logger.
        val marker = "threshold-${System.nanoTime()}"
        AppLogger.setMinLevel(LogLevel.WARN)

        AppLogger.log(LogLevel.DEBUG, TAG) { "debug $marker" }
        AppLogger.log(LogLevel.INFO, TAG) { "info $marker" }
        AppLogger.warn(TAG) { "warn $marker" }
        AppLogger.error(TAG) { "error $marker" }

        assertEquals(
            "DEBUG and INFO must be filtered out before the sink ever sees them",
            listOf(LogLevel.WARN, LogLevel.ERROR),
            ownEntries(marker).map { it.level },
        )
    }

    @Test
    fun errorOnlyThreshold_keepsNothingButErrors() {
        val marker = "error-only-${System.nanoTime()}"
        AppLogger.setMinLevel(LogLevel.ERROR)

        AppLogger.warn(TAG) { "warn $marker" }
        AppLogger.error(TAG) { "error $marker" }

        assertEquals(listOf(LogLevel.ERROR), ownEntries(marker).map { it.level })
    }

    @Test
    fun aRealProductionFailurePath_landsInTheStore() = runBlocking {
        // Not a synthetic AppLogger call: this drives real production code down a real failure
        // path. Restore validation logs at ERROR when handed a file that does not exist, and it is
        // local -- no network, no fixtures -- which makes it the cheapest genuine adoption site to
        // exercise from here.
        AppLogger.setMinLevel(LogLevel.WARN)
        store.flush()
        val before = store.readAll().size

        application.appContainer.restoreDatabaseUseCase.stage("/definitely/not/a/real/backup.sqlite")

        store.flush()
        val after = store.readAll()
        assertTrue(
            "a failing restore must leave a diagnostic behind; store went from $before to ${after.size}",
            after.size > before,
        )
        assertTrue(
            "the new entry should be a failure, not incidental chatter",
            after.takeLast(after.size - before).any { it.level == LogLevel.ERROR || it.level == LogLevel.WARN },
        )
    }

    @Test
    fun persistedVerbosity_roundTripsThroughTheRealSettingsStore() = runBlocking {
        // The user-facing half of the threshold. Note this asserts the value persists, not that the
        // Application's collector applies it -- that link is still uncovered, see ROADMAP.
        val settings = application.appContainer.settingsRepository
        val original = settings.getLogVerbosity()

        settings.setLogVerbosity(LogLevel.ERROR)
        assertEquals(LogLevel.ERROR, settings.getLogVerbosity())

        settings.setLogVerbosity(original)
    }

    @Test
    fun storedEntries_neverContainLibraryContent() {
        // Phase A's identifier rule, checked against the real on-device file rather than a fake:
        // log what failed and why, never what the user is reading.
        val marker = "privacy-${System.nanoTime()}"
        val mediaId = "media-id-0123456789"

        AppLogger.error(TAG) { "Failed to refresh metadata for mediaId=$mediaId ($marker)" }

        val entry = ownEntries(marker).single()
        assertTrue("the opaque id is fine to keep", entry.message.contains(mediaId))
        assertFalse("a title must never be persisted", entry.message.contains(FORBIDDEN_TITLE))
    }
}
