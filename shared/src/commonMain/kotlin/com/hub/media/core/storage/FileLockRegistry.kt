package com.hub.media.core.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared registry of per-file locks to coordinate concurrent access to the same content-addressed
 * hash (e.g. [com.hub.media.features.media.domain.DeleteMediaUseCase] deleting a hash that
 * [LocalImageStorageManager.saveImage] is simultaneously trying to write).
 *
 * This registry is process-local and in-memory.
 */
public class FileLockRegistry {
    private val locks = mutableMapOf<String, Mutex>()
    private val registryMutex = Mutex()

    /**
     * Executes [block] while holding a [Mutex] unique to [hash].
     */
    public suspend fun <T> withLock(
        hash: String,
        block: suspend () -> T,
    ): T {
        val lock =
            registryMutex.withLock {
                locks.getOrPut(hash) { Mutex() }
            }
        return lock.withLock { block() }
    }
}
