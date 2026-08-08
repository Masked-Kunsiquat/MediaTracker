package com.hub.media.core.storage

import com.hub.media.core.database.readFileBytes
import com.hub.media.core.util.LogLevel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Proves ROADMAP Task 15 Phase A's identifier rule (a `mediaId` is fine to log; a title/author is
 * never fine) is not weakened by Phase B's persistence layer. Mirrors the adoption-site style used
 * by e.g. `OpenLibraryIsbnCoverProbeTest.networkFailure_logMessageContainsNoBookContent_...`, but
 * drives the real [FileLogSink] all the way through a real [LogFileStore] onto a real file, since
 * this class (unlike an adoption call site) never has domain knowledge of what "book content"
 * looks like -- it only ever persists exactly what it was handed.
 */
class FileLogSinkTest {

    private lateinit var tempDir: String

    @BeforeTest
    fun setUp() = runTest {
        tempDir = createTestTempDir()
    }

    @AfterTest
    fun tearDown() = runTest {
        cleanupTestTempDir(tempDir)
    }

    @Test
    fun log_bookLookupFailureFollowingTheIdentifierRule_persistsMediaIdButNeverTitleOrAuthor() = runTest {
        val store = LogFileStore(directoryPath = tempDir, flushIntervalMillis = 0)
        val sink = FileLogSink(store)
        val mediaId = "media-id-abc-123"
        val forbiddenTitle = "The Secret Diary of a Wimpy Kid"
        val forbiddenAuthor = "Jeff Kinney"

        // A real call site following the identifier rule (Logger's KDoc): log what failed and
        // the opaque mediaId, never the title/author -- even though both would be readily
        // available to a real BookRepository failure path at the call site.
        sink.log(LogLevel.WARN, "BookRepository", null) {
            "Failed to update metadata for mediaId=$mediaId"
        }

        store.flush()
        val persisted = store.readAll()
        val entry = persisted.single()

        assertTrue(entry.message.contains(mediaId), "the opaque mediaId is fine to persist")
        assertFalse(entry.message.contains(forbiddenTitle))
        assertFalse(entry.message.contains(forbiddenAuthor))

        // Stronger check: scan the raw on-disk bytes directly, not just the decoded LogEntry --
        // this also catches any hypothetical leakage introduced by the codec's own escaping, not
        // only leakage from what FileLogSink chose to hand it.
        val rawBytes = readFileBytes("$tempDir/log.txt")
        assertTrue(rawBytes != null && rawBytes.isNotEmpty(), "the entry must have actually been written to disk")
        val rawText = rawBytes!!.decodeToString()
        assertFalse(rawText.contains(forbiddenTitle))
        assertFalse(rawText.contains(forbiddenAuthor))

        store.shutdown()
    }

    @Test
    fun log_withThrowable_foldsStackTraceIntoMessage_beforeReachingTheStore() = runTest {
        // FileLogSink's KDoc: this is the one place a Throwable gets folded into plain text (via
        // stackTraceToString()) before ever reaching LogFileStore.append, since LogEntry has no
        // Throwable field at all.
        val store = LogFileStore(directoryPath = tempDir, flushIntervalMillis = 0)
        val sink = FileLogSink(store)
        val cause = IllegalStateException("network unreachable")

        sink.log(LogLevel.ERROR, "T", cause) { "operation failed" }
        store.flush()

        val entry = store.readAll().single()
        assertTrue(entry.message.startsWith("operation failed"))
        assertTrue(entry.message.contains("IllegalStateException"))
        assertTrue(entry.message.contains("network unreachable"))
        store.shutdown()
    }

    @Test
    fun log_neverThrows_evenWhenTheUnderlyingStoresClockThrows() = runTest {
        // "Never a new source of failure": FileLogSink must swallow any failure from the store it
        // wraps rather than let it propagate to whatever business logic was logging a WARN/ERROR
        // about something else entirely. A throwing clock genuinely exercises LogFileStore.append's
        // own try/catch (it calls clock.now() before touching the buffer at all) rather than
        // asserting a no-op path that was never at risk of throwing in the first place.
        val throwingClock = object : Clock {
            override fun now(): Instant = throw RuntimeException("clock exploded")
        }
        val store = LogFileStore(directoryPath = tempDir, clock = throwingClock, flushIntervalMillis = 0)
        val sink = FileLogSink(store)

        // Must not throw.
        sink.log(LogLevel.ERROR, "T", null) { "this must never crash the caller" }
        store.shutdown()
    }
}
