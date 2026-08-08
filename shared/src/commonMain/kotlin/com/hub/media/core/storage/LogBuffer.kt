package com.hub.media.core.storage

import com.hub.media.core.util.LogLevel

/**
 * Thread-safe bounded ring buffer + monotonic sequence counter backing [LogFileStore] (ROADMAP
 * Task 15 Phase B). `expect`/`actual` (not a plain `commonMain` class) purely for the lock:
 * Kotlin's `synchronized` block is a JVM-only stdlib function, so this duplicates the near-
 * identical Android/JVM bodies the same way [com.hub.media.core.database]'s `DatabaseFileOps.kt`
 * actuals and [LocalImageStorageManager]'s `sha256Hex`/`writeImageIfNotExists` actuals already do
 * -- rather than adding a dependency (e.g. `kotlinx.atomicfu`, ruled out by AGENTS.md section 5)
 * or standing up an intermediate JVM-shared source set for two platforms that are both already
 * JVM-flavored.
 *
 * ### Overflow policy: drop-oldest, with a trace left in the stream
 * [append] never suspends/blocks (it is called synchronously from [FileLogSink.log], which itself
 * is called from arbitrary business-logic call sites that must never be slowed down by logging --
 * see [FileLogSink]'s KDoc) and never grows the buffer past `capacity`. Once full, the single
 * oldest buffered (not-yet-flushed) entry is evicted to admit the new one. This deliberately
 * favors *recency* over completeness -- the newest activity is what is most useful when the buffer
 * is actively overflowing (e.g. a bulk backfill producing log volume faster than the periodic
 * flush loop drains it, the exact scenario the ROADMAP's "must not hit disk synchronously per log
 * entry" buffering rationale describes) -- rather than e.g. rejecting the newest entry, which would
 * hide precisely the activity happening *right now*.
 *
 * Silent data loss is avoided not by refusing to ever drop an entry (a hard cap has to give
 * somewhere once the producer permanently outpaces the drain), but by recording *that* a drop
 * happened: [drainSnapshot] appends a single synthetic [LogLevel.WARN] marker entry (tagged
 * [OVERFLOW_TAG]) counting every eviction since the previous drain, so the persisted file always
 * shows a visible seam where entries are missing rather than a silent gap a reader could mistake
 * for "nothing happened in between."
 *
 * ### Sequence numbering
 * Every entry's [LogEntry.seq] is minted here, under the same lock as the buffer mutation itself,
 * from a counter seeded once at construction (`initialSeq` -- see [createLogFileStore]'s scan of
 * both retained on-disk files for the highest previously-assigned sequence). This is the one piece
 * of mutable state [LogFileStore] never touches directly, precisely so "assign the next sequence
 * number" and "admit/evict a buffer slot" are atomic with respect to each other and with respect
 * to concurrent callers -- see [LogFileStore]'s KDoc for why a separately-persisted counter was
 * rejected in favor of deriving it from the store itself.
 *
 * A dropped entry still consumed a sequence number before it was evicted, so the persisted stream
 * can have gaps at the numbers a dropped entry once held -- this is fine and expected: the only
 * invariants the rest of this facility relies on are that [LogEntry.seq] is unique and
 * non-decreasing in file order, never that it is gap-free.
 */
internal expect class LogBuffer(capacity: Int, initialSeq: Long) {
    /**
     * Assigns the next sequence number to a new entry (built from [timestampMillis]/[level]/[tag]/
     * [message]) and admits it, evicting the oldest buffered entry first if already at capacity
     * (see this class's KDoc "Overflow policy").
     */
    fun append(timestampMillis: Long, level: LogLevel, tag: String, message: String)

    /**
     * Atomically removes and returns every currently-buffered entry, oldest first, leaving the
     * buffer empty -- plus a synthetic overflow-marker entry (stamped with [nowMillis] and a
     * freshly-minted sequence number, appended last so ascending-by-seq order is preserved) if any
     * evictions occurred since the previous call to this function.
     */
    fun drainSnapshot(nowMillis: Long): List<LogEntry>

    /** Current buffered entry count (not including any not-yet-materialized overflow marker). */
    fun size(): Int
}

/** Tag [LogBuffer]'s synthetic overflow-marker entries are logged under -- see its KDoc. */
internal const val OVERFLOW_TAG: String = "LogFileStore.overflow"
