package com.hub.media.core.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A small, coroutine-safe least-recently-used cache.
 *
 * Hand-rolled rather than pulled in as a dependency (AGENTS.md §5, "no unnecessary dependencies"),
 * and hand-rolled rather than using `LinkedHashMap`'s access-order constructor, which exists on
 * the JVM but not in Kotlin's common `LinkedHashMap`. Common `LinkedHashMap` does guarantee
 * *insertion* order, so recency is maintained by removing and re-inserting a key on every hit —
 * which moves it to the end — and evicting from the front when [maxSize] is exceeded.
 *
 * Guarded by a [Mutex] rather than left unsynchronized: the motivating caller is a type-ahead
 * where several searches are genuinely in flight at once (ROADMAP Task 9 Phase B1), so concurrent
 * reads and writes are the normal case here, not a rare race.
 *
 * @param maxSize Maximum number of entries retained. Coerced to at least 1 — a zero-size cache
 *   would evict every entry immediately on insert, which is a silently useless cache rather than
 *   an obviously broken one.
 */
public class LruCache<K, V>(maxSize: Int) {

    private val maxSize: Int = maxSize.coerceAtLeast(1)
    private val entries = LinkedHashMap<K, V>()
    private val mutex = Mutex()

    /** Returns the value for [key], marking it most-recently-used, or null if absent. */
    public suspend fun get(key: K): V? = mutex.withLock {
        val value = entries.remove(key) ?: return@withLock null
        entries[key] = value
        value
    }

    /** Stores [value] under [key] as most-recently-used, evicting the oldest entry if needed. */
    public suspend fun put(key: K, value: V) {
        mutex.withLock {
            // Remove first so an overwrite refreshes recency instead of leaving the key at its
            // original insertion position.
            entries.remove(key)
            entries[key] = value
            while (entries.size > maxSize) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
            }
        }
    }

    /** Current entry count. Exposed for tests and diagnostics. */
    public suspend fun size(): Int = mutex.withLock { entries.size }

    /** Drops every entry. */
    public suspend fun clear() {
        mutex.withLock { entries.clear() }
    }
}
