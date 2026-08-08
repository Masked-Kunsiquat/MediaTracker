package com.hub.media.core.storage

import com.hub.media.core.util.LogLevel

/**
 * Android actual for [LogBuffer] -- see that `expect` class's KDoc for the overflow policy and
 * sequence-numbering contract this implements. Identical (deliberately) to the JVM actual: both
 * targets are JVM-flavored, so the same `synchronized`-guarded `ArrayDeque` body is correct for
 * both, mirroring how [com.hub.media.core.database]'s `DatabaseFileOps.android.kt`/`.jvm.kt` are
 * likewise byte-for-byte duplicates.
 */
internal actual class LogBuffer actual constructor(
    capacity: Int,
    initialSeq: Long,
) {
    // Coerced, not trusted: at capacity 0 the eviction check below (size >= capacity) is true on
    // an empty deque, so append would call removeFirst() on nothing and throw. LogFileStore.append
    // swallows that, so the symptom would be every log entry silently vanishing rather than an
    // obvious crash. One slot is the smallest meaningful buffer, so clamp rather than reject.
    private val capacity: Int = capacity.coerceAtLeast(1)
    private val lock = Any()
    private val deque = ArrayDeque<LogEntry>()
    private var seqCounter = initialSeq
    private var droppedSinceDrain = 0

    actual fun append(timestampMillis: Long, level: LogLevel, tag: String, message: String) {
        synchronized(lock) {
            seqCounter += 1
            val entry = LogEntry(seqCounter, timestampMillis, level, tag, message)
            if (deque.size >= capacity) {
                deque.removeFirst()
                droppedSinceDrain += 1
            }
            deque.addLast(entry)
        }
    }

    actual fun drainSnapshot(nowMillis: Long): List<LogEntry> = synchronized(lock) {
        val out = ArrayList<LogEntry>(deque.size + 1)
        out.addAll(deque)
        deque.clear()
        if (droppedSinceDrain > 0) {
            seqCounter += 1
            out.add(
                LogEntry(
                    seq = seqCounter,
                    timestampMillis = nowMillis,
                    level = LogLevel.WARN,
                    tag = OVERFLOW_TAG,
                    message = "Log buffer overflow: dropped $droppedSinceDrain entries " +
                        "(oldest evicted first -- see LogBuffer's KDoc \"Overflow policy\").",
                ),
            )
            droppedSinceDrain = 0
        }
        out
    }

    actual fun size(): Int = synchronized(lock) { deque.size }
}
