package com.hub.media.core.storage

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android unit tests run on the host JVM, so plain java.nio file I/O works here —
 * unlike the Room DAO tests, no real Android Context is required.
 */
internal actual suspend fun createTestTempDir(): String = withContext(Dispatchers.IO) {
    Files.createTempDirectory("mediatracker-test-").toAbsolutePath().toString()
}

internal actual suspend fun cleanupTestTempDir(path: String) {
    withContext(Dispatchers.IO) {
        val dir = File(path)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }
}
