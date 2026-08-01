package com.hub.media.core.storage

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JVM implementation: creates a temporary directory using java.nio.file.Files.
 */
internal actual suspend fun createTestTempDir(): String = withContext(Dispatchers.IO) {
    Files.createTempDirectory("mediatracker-test-").toAbsolutePath().toString()
}

/**
 * JVM implementation: recursively deletes the temporary directory.
 */
internal actual suspend fun cleanupTestTempDir(path: String) {
    withContext(Dispatchers.IO) {
        val dir = File(path)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }
}
