package com.hub.media.core.storage

import com.hub.media.core.database.appendFileBytes
import com.hub.media.core.database.fileSizeBytes
import com.hub.media.core.database.readFileBytes
import com.hub.media.core.database.renameFile
import com.hub.media.core.util.LogLevel
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Per-file cap: worst case two retained files (see [LogFileStore]) is ~2x this, "a few MB" total
 * per ROADMAP Task 15 Phase B. */
internal const val MAX_LOG_FILE_SIZE_BYTES: Long = 1_000_000L

/** Bounded in-memory buffer capacity (entries) before the drop-oldest overflow policy kicks in --
 * see [LogBuffer]'s KDoc. */
internal const val DEFAULT_BUFFER_CAPACITY: Int = 500

/** Buffered-entry count at which [LogFileStore.append] fires an asynchronous auto-flush. */
internal const val DEFAULT_FLUSH_THRESHOLD: Int = 50

/** How often the periodic background flush loop runs. `0` disables it entirely (tests). */
internal const val DEFAULT_FLUSH_INTERVAL_MILLIS: Long = 10_000L

/** Default cap for [LogFileStore.readRecent] when the caller doesn't specify one. */
internal const val DEFAULT_RECENT_ENTRY_LIMIT: Int = 500

private const val CURRENT_FILE_NAME = "log.txt"
private const val PREVIOUS_FILE_NAME = "log-previous.txt"

/**
 * Capped, buffered, file-backed log store (ROADMAP Task 15 Phase B). Backs [FileLogSink], the
 * [com.hub.media.core.util.Logger] adoption sites across the app actually write through; also
 * read directly by the (not-yet-built) Phase B2 in-app viewer and by whatever export path Task 15
 * Phase B/C's backup-exclusion workstream builds.
 *
 * ### Not a Room table
 * Deliberately a flat pair of files in app-private storage, not a database table -- a log table
 * would bloat the very database that gets `.sqlite`-backed-up and CSV-exported (ROADMAP Task 15
 * Phase B), the opposite of what this feature needs (log data must be *excluded* from both).
 *
 * ### Capped file + single rollover
 * Two files live in [directoryPath]: a current file (`log.txt`) and one previous file
 * (`log-previous.txt`). [flush] appends newly-buffered entries to the current file; if that would
 * push it past [maxFileSizeBytes], the current file is atomically renamed over the previous file
 * first (via [renameFile], replacing whatever was there), and the append then starts a fresh
 * current file. Worst case, both files are near [maxFileSizeBytes] at once -- see that constant's
 * KDoc for the total.
 *
 * ### A real append, not a read-modify-write
 * Each flush appends only its own batch, via [appendFileBytes], and never reads or rewrites the
 * bytes already on disk -- the rollover check uses [fileSizeBytes] precisely so the existing
 * contents never have to be loaded just to measure them. This is what keeps a flush O(batch size)
 * instead of O(file size): the read-modify-write alternative would have moved ~1 MB in and ~1 MB
 * out on *every* flush, which would have quietly defeated the ROADMAP's own reason for buffering
 * at all ("a bulk backfill over hundreds of books must not hit disk synchronously per log entry")
 * by trading many small writes for far more total bytes.
 *
 * It also bounds what a crash can damage. Because a flush only ever adds to the tail, a process
 * death mid-write can corrupt at most the records being written at that moment -- exactly the
 * bounded failure mode [LogEntryCodec.kt]'s malformed-line tolerance is built for. A whole-file
 * rewrite would instead have put every previously-written record at risk on every single flush.
 *
 * ### Buffered writes and the overflow policy
 * [append] is synchronous and never touches disk directly -- entries land in an in-memory
 * [LogBuffer] first, matching the ROADMAP's "a bulk backfill over hundreds of books must not hit
 * disk synchronously per log entry" rationale. That buffer is flushed to disk on three triggers:
 * a size threshold ([flushThreshold], checked after every [append]), periodically
 * ([flushIntervalMillis], a background loop owned by this instance -- see [shutdown]), and on
 * demand via the public [flush]. See [LogBuffer]'s KDoc for the bounded buffer's drop-oldest
 * overflow policy and how a drop leaves a visible trace in the persisted file rather than being
 * silent.
 *
 * A flush that fails outright (e.g. the disk is full) drops that batch of buffered entries with no
 * retry/re-buffering -- a best-effort persistence guarantee, the same one every other sink in this
 * facility offers (ROADMAP Task 15 Phase A: "a logging call must never itself become a new source
 * of failure for its caller"). There is fundamentally no stronger guarantee available once the
 * underlying write itself is failing.
 *
 * ### Sequence numbering
 * Never separately persisted -- see [createLogFileStore]'s KDoc and [LogBuffer]'s KDoc for the
 * full contract and the correctness hazard a separately-persisted counter would introduce.
 *
 * @param directoryPath The app-private directory both retained files live in (e.g.
 *   `logStorageDirectory(context)` on Android). Created lazily on first write.
 * @param initialSeq The sequence number the first entry assigned by this instance continues from
 *   (that entry gets `initialSeq + 1`) -- always obtained via [createLogFileStore]'s disk scan in
 *   production; tests may pass an arbitrary value directly to exercise continuity without needing
 *   real files on disk first.
 * @param clock Wall-clock source for [LogEntry.timestampMillis] and the overflow marker's
 *   timestamp. Defaults to [Clock.System]; tests inject a fixed clock for determinism.
 * @param maxFileSizeBytes Per-file rollover cap -- see [MAX_LOG_FILE_SIZE_BYTES]. Overridable so
 *   tests can exercise rollover without writing megabytes of data.
 * @param bufferCapacity In-memory buffer capacity in entries -- see [DEFAULT_BUFFER_CAPACITY].
 * @param flushThreshold Buffered-entry count that triggers an async auto-flush -- see
 *   [DEFAULT_FLUSH_THRESHOLD].
 * @param flushIntervalMillis Periodic auto-flush interval. `0` disables the periodic loop
 *   entirely -- tests always pass `0` and drive [flush] explicitly, since a real background loop
 *   on [Dispatchers.Default] would not respect `kotlinx-coroutines-test`'s virtual time and would
 *   otherwise leak a running coroutine past the end of a test.
 */
public class LogFileStore(
    private val directoryPath: String,
    initialSeq: Long = 0L,
    private val clock: Clock = Clock.System,
    private val maxFileSizeBytes: Long = MAX_LOG_FILE_SIZE_BYTES,
    bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
    private val flushThreshold: Int = DEFAULT_FLUSH_THRESHOLD,
    flushIntervalMillis: Long = DEFAULT_FLUSH_INTERVAL_MILLIS,
) {
    private val currentPath = "$directoryPath/$CURRENT_FILE_NAME"
    private val previousPath = "$directoryPath/$PREVIOUS_FILE_NAME"
    private val buffer = LogBuffer(bufferCapacity, initialSeq)

    // Serializes concurrent flush() callers (periodic loop, threshold-triggered auto-flush, and an
    // explicit caller) against each other so two flushes never race to read-modify-write the same
    // current file. Does NOT serialize against append() -- LogBuffer's own internal lock already
    // makes append()/drainSnapshot() safe to interleave.
    private val flushMutex = Mutex()

    // Held for the lifetime of an in-flight threshold-triggered flush, so only one is ever queued
    // at a time -- see append(). Deliberately separate from flushMutex: that one serializes the
    // flush work itself (and is also taken by the periodic loop and by explicit readRecent/readAll
    // callers), whereas this one only gates whether another *auto* flush is worth scheduling.
    private val autoFlushGate = Mutex()

    // Owned by this instance, not injected (unlike e.g. ReadingTimer's caller-supplied scope):
    // the background flush loop is a pure implementation detail with a suspend flush() escape
    // hatch for deterministic tests, not something a caller needs to observe or drive directly.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var periodicFlushJob: Job? = null

    init {
        if (flushIntervalMillis > 0) {
            periodicFlushJob = backgroundScope.launch {
                while (isActive) {
                    delay(flushIntervalMillis)
                    flush()
                }
            }
        }
    }

    /**
     * Buffers a new entry (see this class's KDoc "Buffered writes and the overflow policy").
     * Synchronous and never suspends/throws -- called directly from [FileLogSink.log], which must
     * never become a new source of failure for its caller.
     */
    internal fun append(level: LogLevel, tag: String, message: String) {
        try {
            buffer.append(clock.now().toEpochMilliseconds(), level, tag, message)
            // At most one threshold-triggered flush may be in flight at a time. Without this gate,
            // the condition below stays true for every append between crossing the threshold and
            // the flush actually draining the buffer, so a burst would spawn one coroutine per
            // append -- hundreds of them during exactly the bulk backfill this buffering exists to
            // keep off the disk, each one taking flushMutex only to find nothing left to write.
            // Mutex.tryLock() is non-suspending and atomic, so this stays safe to call from
            // append()'s synchronous, never-blocking contract; the gate is released in the
            // coroutine's finally, so a failing flush re-arms it rather than wedging auto-flush
            // off for the rest of the process.
            if (buffer.size() >= flushThreshold && autoFlushGate.tryLock()) {
                backgroundScope.launch {
                    try {
                        flush()
                    } finally {
                        autoFlushGate.unlock()
                    }
                }
            }
        } catch (_: Throwable) {
            // See this function's KDoc -- buffering a log entry must never crash the caller.
        }
    }

    /**
     * Drains the in-memory buffer and writes it to the current file, rotating first if that would
     * exceed [maxFileSizeBytes] (see this class's KDoc "Capped file + single rollover"). Safe to
     * call concurrently -- see [flushMutex]. A no-op if nothing is buffered. Failures are swallowed
     * (see this class's KDoc on the best-effort persistence guarantee) rather than thrown, so both
     * the periodic/threshold-triggered background callers and an explicit caller from [readRecent]/
     * [readAll] can always call this without a try/catch of their own.
     */
    public suspend fun flush() {
        flushMutex.withLock {
            try {
                val entries = buffer.drainSnapshot(clock.now().toEpochMilliseconds())
                if (entries.isEmpty()) return@withLock
                appendEntriesToDisk(entries)
            } catch (_: Throwable) {
                // Best-effort persistence -- see this function's KDoc.
            }
        }
    }

    private suspend fun appendEntriesToDisk(entries: List<LogEntry>) {
        val newBytes = encodeLogEntries(entries)
        val existingSize = fileSizeBytes(currentPath)
        if (existingSize > 0 && existingSize + newBytes.size > maxFileSizeBytes) {
            // Atomic rename+replace (see renameFile's KDoc): the old current file becomes the new
            // previous file in one step, after which appending below starts a fresh current file.
            //
            // The return value is deliberately checked, not ignored. renameFile is documented to
            // return false rather than throw (an atomic move the platform provider rejects, a
            // transient handle on the file, a full disk). If a failed rotation were treated as if
            // it had succeeded, the append below would land on the *still-present* current file
            // anyway -- which is harmless -- but any logic that instead assumed a fresh empty file
            // would silently discard everything already in it. Skipping the rotation on failure
            // keeps the only cost bounded and self-correcting: the current file temporarily
            // exceeds maxFileSizeBytes, and the next flush simply retries the rotation. This
            // mirrors selfHealDatabaseIfNeeded, which gates its own main-file rename on both
            // sidecar renames having genuinely succeeded for the same class of reason.
            renameFile(currentPath, previousPath)
        }
        // A real append, never a read-modify-write: see appendFileBytes' KDoc for why this matters
        // both for cost (O(batch), not O(file)) and for how much a crash mid-write can damage.
        appendFileBytes(currentPath, newBytes)
    }

    /**
     * Reads up to [limit] most-recent entries, oldest first -- the bounded view the Phase B2
     * viewer needs (ROADMAP: "the viewer needs no reactive Flow -- a suspend 'read current
     * entries' call is enough"). Flushes any buffered entries first, so a call right after a burst
     * of logging never shows stale data.
     */
    public suspend fun readRecent(limit: Int = DEFAULT_RECENT_ENTRY_LIMIT): List<LogEntry> {
        flush()
        val all = readAllFromDisk()
        return if (all.size <= limit) all else all.takeLast(limit)
    }

    /**
     * Reads every retained entry (both files), oldest first -- the full view the export path
     * needs. Flushes any buffered entries first, same as [readRecent].
     */
    public suspend fun readAll(): List<LogEntry> {
        flush()
        return readAllFromDisk()
    }

    private suspend fun readAllFromDisk(): List<LogEntry> {
        val previous = readFileBytes(previousPath)?.let { decodeLogEntries(it) } ?: emptyList()
        val current = readFileBytes(currentPath)?.let { decodeLogEntries(it) } ?: emptyList()
        // previous + current is already oldest-first in the overwhelmingly common case (rotation
        // only ever moves the *older* generation into "previous"), but sorting by seq explicitly
        // is cheap at this bounded (~2MB) scale and removes any doubt.
        return (previous + current).sortedBy { it.seq }
    }

    /**
     * Cancels the periodic background flush loop. Best-effort, not a guaranteed final flush (this
     * function is intentionally non-suspend so it can be called from a non-suspend teardown path
     * like `MediaTrackerApplication.onTerminate`, which itself is documented as emulator/testing-
     * only -- see [com.hub.media.ui.AppContainer.close]'s call site). Whatever is buffered but not
     * yet flushed at the moment this is called may be lost; the periodic/threshold triggers above
     * already keep that window small in normal operation.
     */
    public fun shutdown() {
        backgroundScope.cancel()
    }
}

/**
 * Highest [LogEntry.seq] retained in the log file at [path], or `0` if it is absent, empty, or
 * contains no well-formed record at all.
 *
 * ### Why this scans backwards instead of decoding the file
 * Entries are only ever appended, and sequence numbers are minted in append order (see
 * [LogBuffer]'s KDoc), so within a single file `seq` is strictly ascending in line order. The
 * highest one is therefore always on the *last* well-formed line -- there is no need to decode
 * the other ~10,000 records in a full 1 MB file, allocating a [LogEntry] and unescaping two
 * strings for each, purely to take a maximum that is knowable from the end.
 *
 * This matters because the one production caller ([createLogFileStore]) runs inside
 * `MediaTrackerApplication.onCreate`'s [kotlinx.coroutines.runBlocking], i.e. synchronously on
 * process startup, on every launch. Walking backwards keeps that to a file read plus a handful of
 * line decodes rather than a full parse of both retained files.
 *
 * The walk continues past a damaged trailing line rather than giving up on it (a crash mid-append
 * can leave a partial final record -- see [LogEntryCodec.kt]'s malformed-line tolerance), so the
 * answer is the highest sequence number that is genuinely *recoverable*, which is exactly the one
 * the counter must not restart below. Only a file with no well-formed record anywhere degrades to
 * the full-scan cost, and that file is by definition tiny or garbage.
 */
private suspend fun highestRetainedSeq(path: String): Long {
    val bytes = readFileBytes(path) ?: return 0L
    val lines = bytes.decodeToString().split("\n")
    for (i in lines.indices.reversed()) {
        val line = lines[i]
        if (line.isEmpty()) continue
        val entry = decodeLogEntry(line) ?: continue
        return entry.seq
    }
    return 0L
}

/**
 * Builds a [LogFileStore] rooted at [directoryPath], initializing its sequence counter from the
 * highest [LogEntry.seq] found across **both** retained files (current and previous) -- the
 * ROADMAP's highest-risk correctness item for this phase.
 *
 * ### Why an in-memory counter derived from disk, not a separately persisted one
 * A counter persisted independently (e.g. a new `app_settings` key) could drift from the store
 * itself: write the counter, crash before the corresponding entry lands on disk (or vice versa),
 * and the next process could start assigning sequence numbers *below* entries already on disk --
 * silently corrupting every later boundary comparison the Phase B2 viewer's snapshot/refresh
 * divider depends on (see ROADMAP Task 15 Phase B's "Boundary marking" bullet). Deriving the
 * counter from the store's own retained files instead makes the store the single source of truth,
 * so that drift is impossible by construction.
 *
 * ### Why both files, not just the current one
 * A rotation that just started a fresh, empty current file must not reset the counter while higher
 * sequence numbers still exist in the previous file -- scanning only `log.txt` would do exactly
 * that the instant after any rotation. [maxOf] across both files' contents is what makes this
 * correct regardless of where in the rotation cycle the last process happened to stop.
 *
 * ### The degenerate case
 * No retained entries at all (first-ever launch, or both files empty/unreadable) is safe: both
 * scans contribute `0`, so [initialSeq] is `0` and the first entry this store assigns gets seq `1`
 * -- there being nothing yet for the viewer's boundary comparison to be measured against.
 * Malformed/truncated lines encountered during the scan are tolerated exactly like every other
 * read path (see [decodeLogEntries]): they are skipped, not treated as a reason to fail startup.
 *
 * @param directoryPath See [LogFileStore]'s `directoryPath` parameter.
 * @param clock Forwarded to [LogFileStore] -- see its KDoc.
 */
public suspend fun createLogFileStore(
    directoryPath: String,
    clock: Clock = Clock.System,
    maxFileSizeBytes: Long = MAX_LOG_FILE_SIZE_BYTES,
    bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
    flushThreshold: Int = DEFAULT_FLUSH_THRESHOLD,
    flushIntervalMillis: Long = DEFAULT_FLUSH_INTERVAL_MILLIS,
): LogFileStore {
    val currentPath = "$directoryPath/$CURRENT_FILE_NAME"
    val previousPath = "$directoryPath/$PREVIOUS_FILE_NAME"
    val currentMax = highestRetainedSeq(currentPath)
    val previousMax = highestRetainedSeq(previousPath)
    return LogFileStore(
        directoryPath = directoryPath,
        initialSeq = maxOf(currentMax, previousMax),
        clock = clock,
        maxFileSizeBytes = maxFileSizeBytes,
        bufferCapacity = bufferCapacity,
        flushThreshold = flushThreshold,
        flushIntervalMillis = flushIntervalMillis,
    )
}
