package com.hub.media.core.storage

import com.hub.media.core.database.fileSizeBytes
import com.hub.media.core.database.readFileBytes
import com.hub.media.core.database.writeFileBytes
import com.hub.media.core.util.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Tests for [LogFileStore] and [createLogFileStore] (ROADMAP Task 15 Phase B).
 *
 * Every store built here passes `flushIntervalMillis = 0` to disable the periodic background
 * flush loop -- see [LogFileStore]'s own KDoc on why: a real loop on [kotlinx.coroutines.Dispatchers.Default]
 * would not respect `kotlinx-coroutines-test`'s virtual time and would leak a running coroutine
 * past the end of a test. [flush] is always driven explicitly instead.
 */
class LogFileStoreTest {
    private lateinit var tempDir: String

    /** Deterministic, manually-advanced [Clock] for entries whose timestamp is asserted on. */
    private class MutableClock(
        startMillis: Long = 1_700_000_000_000L,
    ) : Clock {
        var millis: Long = startMillis

        override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
    }

    @BeforeTest
    fun setUp() =
        runTest {
            tempDir = createTestTempDir()
        }

    @AfterTest
    fun tearDown() =
        runTest {
            cleanupTestTempDir(tempDir)
        }

    /**
     * Polls real (non-virtual) wall-clock time until [condition] is true or [maxAttempts] is
     * exhausted. Needed for anything that depends on [LogFileStore]'s `backgroundScope`, which
     * runs on the real [Dispatchers.Default] -- entirely outside this suite's `runTest` virtual
     * scheduler -- so there is no `advanceUntilIdle()`/`runCurrent()` that would wait for it.
     * Mirrors `BookDetailViewModelTest.runCurrentUntilOrTimeOut`'s real-time-yielding idiom.
     */
    // 1_000 attempts x 5ms is ~5 real seconds, matching BookDetailViewModelTest's helper. The
    // original 200 (~1s) was ample on an idle developer machine and demonstrably not on a loaded
    // CI runner, which is where this surfaced -- the same too-tight bound, in a second helper.
    private suspend fun pollUntilOrTimeOut(
        maxAttempts: Int = 1_000,
        condition: suspend () -> Boolean,
    ): Boolean {
        var attempts = 0
        while (attempts < maxAttempts) {
            if (condition()) return true
            withContext(Dispatchers.Default) { delay(5) }
            attempts++
        }
        return condition()
    }

    // --- Flush-before-read (requirement 6) ----------------------------------------------------

    @Test
    fun readAll_calledRightAfterAppendWithNoExplicitFlush_stillReturnsTheBufferedEntry() =
        runTest {
            val store = LogFileStore(directoryPath = tempDir, clock = MutableClock(), flushIntervalMillis = 0)

            store.append(LogLevel.WARN, "T", "buffered, never explicitly flushed")
            val all = store.readAll()

            assertEquals(listOf("buffered, never explicitly flushed"), all.map { it.message })
            store.shutdown()
        }

    @Test
    fun readRecent_calledRightAfterAppendWithNoExplicitFlush_stillReturnsTheBufferedEntry() =
        runTest {
            val store = LogFileStore(directoryPath = tempDir, clock = MutableClock(), flushIntervalMillis = 0)

            store.append(LogLevel.WARN, "T", "buffered, never explicitly flushed")
            val recent = store.readRecent()

            assertEquals(listOf("buffered, never explicitly flushed"), recent.map { it.message })
            store.shutdown()
        }

    // --- readRecent(limit) ordering (requirement 7) -------------------------------------------

    @Test
    fun readRecent_withLimitSmallerThanTotal_returnsTheMostRecentEntriesStillOldestFirst() =
        runTest {
            val store = LogFileStore(directoryPath = tempDir, clock = MutableClock(), flushIntervalMillis = 0)
            repeat(5) { i -> store.append(LogLevel.INFO, "T", "entry-$i") }
            store.flush()

            val recent = store.readRecent(limit = 2)

            // Must be the two NEWEST ("entry-3", "entry-4"), not the two oldest -- and still in
            // ascending (oldest-first) order within that window.
            assertEquals(listOf("entry-3", "entry-4"), recent.map { it.message })
            store.shutdown()
        }

    // --- Append semantics: a real append, not a rewrite (requirement 8) -----------------------

    @Test
    fun flush_calledTwiceWithNewEntriesBetween_appendsWithoutDuplicatingOrDroppingEarlierEntries() =
        runTest {
            val store = LogFileStore(directoryPath = tempDir, clock = MutableClock(), flushIntervalMillis = 0)
            val currentFilePath = "$tempDir/log.txt"

            store.append(LogLevel.INFO, "T", "first")
            store.flush()
            val sizeAfterFirstFlush = fileSizeBytes(currentFilePath)

            store.append(LogLevel.INFO, "T", "second")
            store.flush()
            val sizeAfterSecondFlush = fileSizeBytes(currentFilePath)

            assertTrue(
                sizeAfterSecondFlush > sizeAfterFirstFlush,
                "the file must grow via append -- a read-modify-write bug could leave the size unchanged " +
                    "or even shrink it",
            )
            val all = store.readAll()
            assertEquals(
                listOf("first", "second"),
                all.map { it.message },
                "no duplication and no drop of the entry written by the first flush",
            )
            store.shutdown()
        }

    // --- Buffer overflow policy (requirement 5) -----------------------------------------------

    @Test
    fun append_moreEntriesThanBufferCapacityBeforeAnyFlush_dropsOldestAndRecordsAnOverflowMarker() =
        runTest {
            val store =
                LogFileStore(
                    directoryPath = tempDir,
                    clock = MutableClock(),
                    bufferCapacity = 3,
                    // Keep append() from ever triggering its own async auto-flush mid-test -- this test
                    // wants to control precisely when flush() happens.
                    flushThreshold = 1000,
                    flushIntervalMillis = 0,
                )

            store.append(LogLevel.INFO, "T", "1")
            store.append(LogLevel.INFO, "T", "2")
            store.append(LogLevel.INFO, "T", "3") // buffer full: [1, 2, 3]
            store.append(LogLevel.INFO, "T", "4") // evicts "1" -> [2, 3, 4]
            store.append(LogLevel.INFO, "T", "5") // evicts "2" -> [3, 4, 5]
            store.flush()

            val all = store.readAll()
            val regular = all.filter { it.tag != OVERFLOW_TAG }
            assertEquals(
                listOf("3", "4", "5"),
                regular.map { it.message },
                "drop-oldest policy: only the 3 newest of the 5 appended entries should survive",
            )

            val marker = all.single { it.tag == OVERFLOW_TAG }
            assertEquals(LogLevel.WARN, marker.level)
            assertTrue(
                marker.message.contains("dropped 2 entries"),
                "the marker must record exactly how many entries were evicted -- got: ${marker.message}",
            )
            store.shutdown()
        }

    // --- Threshold-triggered auto-flush (autoFlushGate) ----------------------------------------

    @Test
    fun append_crossingFlushThresholdTwice_autoFlushesBothBatchesWithoutDuplicationOrLoss() =
        runTest {
            // Small threshold so a handful of appends crosses it twice, exercising both the initial
            // trigger AND autoFlushGate re-arming after the first auto-flush's `finally` releases it
            // (see LogFileStore.append's KDoc) -- if the gate ever stayed locked for good, the second
            // batch would never get auto-flushed.
            val threshold = 5
            val store =
                LogFileStore(
                    directoryPath = tempDir,
                    clock = MutableClock(),
                    flushThreshold = threshold,
                    flushIntervalMillis = 0,
                )
            val currentFilePath = "$tempDir/log.txt"
            val firstBatch = (0 until threshold).map { "batch1-$it" }
            val secondBatch = (0 until threshold).map { "batch2-$it" }

            suspend fun entriesOnDiskNow(): List<LogEntry> =
                decodeLogEntries(readFileBytes(currentFilePath) ?: ByteArray(0))

            // Cross the threshold WITHOUT ever calling flush() explicitly -- append() itself must be
            // the thing that schedules the auto-flush.
            firstBatch.forEach { store.append(LogLevel.INFO, "T", it) }

            // Read the raw file directly, NOT via store.readAll()/readRecent() -- both of those call
            // flush() themselves and would make this pass even if the threshold-triggered auto-flush
            // never fired at all.
            val firstBatchLanded = pollUntilOrTimeOut { entriesOnDiskNow().size >= threshold }
            assertTrue(firstBatchLanded, "the first threshold-triggered auto-flush never wrote anything to disk")

            // Cross the threshold a second time. If autoFlushGate failed to re-arm after the first
            // flush completed, this batch would silently never get auto-flushed -- invisible to a
            // trailing readAll() (which flushes regardless) but caught here by reading the file
            // directly before any explicit flush() is ever called.
            secondBatch.forEach { store.append(LogLevel.INFO, "T", it) }

            // This assertion caught a real defect, but only on CI and only on the Android variant --
            // reverting the fix does not reliably fail it locally, because a local flush finishes
            // before the second batch is appended and the gate is free again. Under load the first
            // flush is still in flight, those appends fail tryLock, and with no further appends nothing
            // re-triggers the check: the batch then sits in memory until the periodic flush, which this
            // store has disabled. Treat a failure here as a genuine dropped-crossing bug, not as flake.
            val secondBatchLanded = pollUntilOrTimeOut { entriesOnDiskNow().size >= threshold * 2 }
            assertTrue(
                secondBatchLanded,
                "the second threshold-triggered auto-flush never wrote anything to disk -- " +
                    "autoFlushGate may not have re-armed after the first flush completed",
            )

            val onDiskBeforeAnyExplicitFlush = entriesOnDiskNow()
            assertEquals(
                firstBatch + secondBatch,
                onDiskBeforeAnyExplicitFlush.map { it.message },
                "both auto-flushed batches must be present exactly once, in order, entirely via " +
                    "auto-flush -- before readAll()/flush() is ever called explicitly",
            )

            // readAll() calls flush() internally; this proves that trailing flush is a true no-op
            // (nothing left buffered) rather than a second chance to silently double-write.
            val all = store.readAll()
            assertEquals(firstBatch + secondBatch, all.map { it.message })
            store.shutdown()
        }

    // --- Rollover at the cap (requirement 3) --------------------------------------------------

    @Test
    fun flush_secondBatchExceedsCap_rotatesCurrentToPreviousAndBothFilesReadAscendingBySeq() =
        runTest {
            val store =
                LogFileStore(
                    directoryPath = tempDir,
                    clock = MutableClock(),
                    // Tiny cap: any real encoded entry already exceeds it, so every flush AFTER the first
                    // rotates -- LogFileStore's rollover check only looks at the file's *existing* size,
                    // which starts at 0, so the very first flush never rotates regardless of batch size.
                    maxFileSizeBytes = 10L,
                    flushIntervalMillis = 0,
                )

            store.append(LogLevel.INFO, "T", "first batch")
            store.flush() // no previous file exists yet -> writes straight to current, no rotation.

            store.append(LogLevel.INFO, "T", "second batch")
            store.flush() // current (first batch) + new bytes now exceeds the cap -> rotates:
            // current becomes previous, a fresh current receives the second batch.

            val all = store.readAll()
            assertEquals(
                listOf("first batch", "second batch"),
                all.map { it.message },
                "both the rotated-out previous file and the fresh current file must contribute",
            )
            assertTrue(
                all.zipWithNext().all { (a, b) -> a.seq < b.seq },
                "readAll() must return entries from both files in ascending seq order",
            )
            store.shutdown()
        }

    @Test
    fun flush_thirdBatchTriggersASecondRotation_replacesThePreviousFilesPriorContent() =
        runTest {
            val store =
                LogFileStore(
                    directoryPath = tempDir,
                    clock = MutableClock(),
                    maxFileSizeBytes = 10L,
                    flushIntervalMillis = 0,
                )

            store.append(LogLevel.INFO, "T", "first batch")
            store.flush() // current = first batch

            store.append(LogLevel.INFO, "T", "second batch")
            store.flush() // rotation #1: previous = first batch, current = second batch

            store.append(LogLevel.INFO, "T", "third batch")
            store.flush() // rotation #2: previous = second batch (REPLACING first batch), current = third batch

            val all = store.readAll()
            // "first batch" must be gone: rotation #2 overwrote the one previous-file slot that used
            // to hold it, and there is no third retained file anywhere in this design to keep it in.
            assertEquals(
                listOf("second batch", "third batch"),
                all.map { it.message },
                "the previous file's prior content (first batch) must have been replaced, not merged",
            )
            store.shutdown()
        }

    // --- Sequence continuity across a simulated process restart (requirement 4) ---------------

    @Test
    fun createLogFileStore_emptyDirectory_firstEntryEverGetsSeqOne() =
        runTest {
            // The degenerate case the KDoc calls out explicitly: nothing retained anywhere, so
            // initialSeq is 0 and the very first entry assigned by this store gets seq 1.
            val store = createLogFileStore(tempDir, clock = MutableClock(), flushIntervalMillis = 0)

            store.append(LogLevel.INFO, "T", "first entry ever")
            store.flush()

            val all = store.readAll()
            assertEquals(1L, all.single().seq)
            store.shutdown()
        }

    @Test
    fun createLogFileStore_reopenedOverExistingFiles_continuesSequenceAboveTheHighestOnDisk() =
        runTest {
            val store1 = createLogFileStore(tempDir, clock = MutableClock(), flushIntervalMillis = 0)
            store1.append(LogLevel.INFO, "T", "a")
            store1.append(LogLevel.INFO, "T", "b")
            store1.append(LogLevel.INFO, "T", "c")
            store1.flush()
            store1.shutdown()

            // A brand new store instance over the SAME directory -- simulates a process restart
            // (new LogFileStore, same on-disk files) rather than reusing the first instance's
            // in-memory counter.
            val store2 = createLogFileStore(tempDir, clock = MutableClock(), flushIntervalMillis = 0)
            store2.append(LogLevel.INFO, "T", "d")
            store2.flush()

            val all = store2.readAll()
            assertEquals(listOf("a", "b", "c", "d"), all.map { it.message })
            assertEquals(listOf(1L, 2L, 3L, 4L), all.map { it.seq })
            store2.shutdown()
        }

    @Test
    fun createLogFileStore_freshEmptyCurrentFileButHigherSeqInPreviousFile_counterDoesNotReset() =
        runTest {
            // The ROADMAP's highest-risk correctness item, constructed deliberately rather than
            // waited for: the exact window a process death mid-rotation could leave behind is a
            // fresh, EMPTY current file (log.txt) while the higher sequence numbers from before the
            // rotation still live entirely in the previous file (log-previous.txt). Scanning only
            // log.txt on the next launch would see nothing and restart numbering at 1, silently
            // colliding with sequence numbers already on disk.
            val highSeqEntries =
                listOf(
                    LogEntry(seq = 40L, timestampMillis = 1_000L, level = LogLevel.INFO, tag = "T", message = "old-1"),
                    LogEntry(seq = 41L, timestampMillis = 1_001L, level = LogLevel.INFO, tag = "T", message = "old-2"),
                )
            writeFileBytes("$tempDir/log-previous.txt", encodeLogEntries(highSeqEntries))
            writeFileBytes("$tempDir/log.txt", ByteArray(0)) // fresh, empty current file

            val store = createLogFileStore(tempDir, clock = MutableClock(), flushIntervalMillis = 0)
            store.append(LogLevel.INFO, "T", "new entry after restart")
            store.flush()

            val all = store.readAll()
            val newest = all.maxBy { it.seq }
            assertEquals("new entry after restart", newest.message)
            assertEquals(
                42L,
                newest.seq,
                "must continue above 41 (the previous file's highest), not reset to 1 just because " +
                    "the current file was empty",
            )
            store.shutdown()
        }

    // --- highestRetainedSeq's tail-window scan (new: SEQ_SCAN_TAIL_WINDOW_BYTES) --------------
    //
    // highestRetainedSeq's window is private to LogFileStore.kt (8 KiB per its KDoc), so these
    // tests can't reference the constant directly -- both hardcode 8 * 1024 and, as their own
    // positive control, assert their setup actually clears it. Without that check either test
    // could silently degrade into exercising the ordinary full-file path instead of the new
    // windowed one.

    @Test
    fun createLogFileStore_currentFileLargerThanTailWindow_stillContinuesFromTheTrueHighestSeq() =
        runTest {
            // Enough entries, each padded to a known-ish size, that the file comfortably exceeds the
            // 8 KiB tail window -- so the window is a genuine subset of the file, not the whole thing.
            val entryCount = 400
            val store1 = LogFileStore(directoryPath = tempDir, clock = MutableClock(), flushIntervalMillis = 0)
            repeat(entryCount) { i ->
                store1.append(LogLevel.INFO, "T", "padded log message body for entry number $i ".repeat(2))
            }
            store1.flush()
            val fileSize = fileSizeBytes("$tempDir/log.txt")
            assertTrue(
                fileSize > 8 * 1024,
                "test setup must itself exceed the 8 KiB tail window for this to prove anything -- " +
                    "actual size: $fileSize bytes",
            )
            store1.shutdown()

            // A fresh store instance, simulating a process restart: createLogFileStore's scan must
            // find the file's TRUE highest seq (entryCount) via the tail window, not an under-count --
            // the window deliberately discards its own first (likely truncated) line, so an off-by-one
            // there would silently lose the newest entries in that dropped line.
            val store2 = createLogFileStore(tempDir, clock = MutableClock(), flushIntervalMillis = 0)
            store2.append(LogLevel.INFO, "T", "after restart")
            store2.flush()

            val newest = store2.readAll().maxBy { it.seq }
            assertEquals("after restart", newest.message)
            assertEquals(
                entryCount + 1L,
                newest.seq,
                "must continue from the true highest seq ($entryCount) recovered by the tail-window " +
                    "scan, not an under-count",
            )
            store2.shutdown()
        }

    @Test
    fun createLogFileStore_entireTailWindowIsGarbageButValidRecordsPrecedeIt_fallsBackToFullFileScan() =
        runTest {
            // Valid, well-formed records sitting well before the tail window...
            val validEntries =
                listOf(
                    LogEntry(seq = 5L, timestampMillis = 1_000L, level = LogLevel.INFO, tag = "T", message = "valid-a"),
                    LogEntry(seq = 6L, timestampMillis = 1_001L, level = LogLevel.INFO, tag = "T", message = "valid-b"),
                    LogEntry(seq = 7L, timestampMillis = 1_002L, level = LogLevel.INFO, tag = "T", message = "valid-c"),
                )
            val validBytes = encodeLogEntries(validEntries)
            // ...followed by a block of unparseable bytes (no FIELD_DELIMITER anywhere, so every
            // "line" fails decodeLogEntry's 5-field check) comfortably larger than the 8 KiB window,
            // so the window -- read from the file's tail -- lands entirely inside the garbage and
            // finds nothing decodable anywhere in it.
            val garbageBytes = "GARBAGE_LINE_NOT_LOG_DATA_AT_ALL\n".repeat(400).encodeToByteArray()
            assertTrue(
                garbageBytes.size > 8 * 1024,
                "test setup's trailing garbage must itself exceed the 8 KiB tail window, or the window " +
                    "could still reach a valid record and this wouldn't exercise the fallback at all -- " +
                    "actual size: ${garbageBytes.size} bytes",
            )
            writeFileBytes("$tempDir/log.txt", validBytes + garbageBytes)

            val store = createLogFileStore(tempDir, clock = MutableClock(), flushIntervalMillis = 0)
            store.append(LogLevel.INFO, "T", "after fallback recovery")
            store.flush()

            val all = store.readAll()
            val newest = all.maxBy { it.seq }
            assertEquals("after fallback recovery", newest.message)
            assertEquals(
                8L,
                newest.seq,
                "the tail window yielded nothing decodable (pure garbage), so highestRetainedSeq must " +
                    "fall back to a full-file scan and recover 7 (validEntries' true highest seq) -- " +
                    "not silently drop to 0 and restart numbering from 1",
            )
            // Positive control: the pre-existing valid records must have actually round-tripped
            // through readAll(), not just been assumed present because the seq math above worked out.
            assertEquals(
                validEntries.map { it.message },
                all.filter { it.seq in 5L..7L }.sortedBy { it.seq }.map { it.message },
            )
            store.shutdown()
        }
}
