package com.hub.media.core.storage

/**
 * Platform-specific temporary directory provider for image storage tests.
 *
 * JVM uses `java.io.File.createTempDir()` from a cleanup hook context.
 * Android would require `Context.cacheDir` but is not exercised in unit tests.
 */
internal expect suspend fun createTestTempDir(): String

/**
 * Platform-specific cleanup for temporary directories created by [createTestTempDir].
 */
internal expect suspend fun cleanupTestTempDir(path: String)
