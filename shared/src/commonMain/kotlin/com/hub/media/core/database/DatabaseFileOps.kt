package com.hub.media.core.database

/**
 * Small platform file primitives backing ROADMAP Task 8 Phase C (`.sqlite` backup/restore).
 * `internal` (module-wide, not package-private -- visible to
 * [com.hub.media.features.portability.domain] callers in the same `shared` module) since these are
 * plumbing, not a public API surface. Android and JVM actuals both implement these with plain
 * `java.io.File`/`java.nio.file` calls (both targets run on a JVM), duplicated the same way
 * `LocalImageStorageManager`'s `sha256Hex`/`writeImageIfNotExists` actuals already are, rather than
 * introducing an intermediate source set for two nearly-identical bodies.
 */

/** True if a regular file exists at [path]. */
internal expect suspend fun fileExists(path: String): Boolean

/**
 * Reads at most the first [byteCount] bytes of the file at [path] -- enough to validate a header
 * (see [SQLITE_HEADER_SIZE]) without loading a potentially large database file fully into memory.
 *
 * @return The bytes read (fewer than [byteCount] if the file itself is shorter), or `null` if the
 *   file doesn't exist or can't be read.
 */
internal expect suspend fun readFileHeaderBytes(path: String, byteCount: Int): ByteArray?

/**
 * Reads the whole file at [path] as bytes. Only ever used on small files (e.g. the plain-text
 * restore-result marker in `DatabaseRestoreRecovery.kt`) -- large files must use
 * [readFileHeaderBytes] instead.
 *
 * @return The file's full contents, or `null` if it doesn't exist or can't be read.
 */
internal expect suspend fun readFileBytes(path: String): ByteArray?

/**
 * Writes [bytes] to [path], overwriting any existing file and creating parent directories if
 * needed.
 *
 * @return `true` if the write succeeded.
 */
internal expect suspend fun writeFileBytes(path: String, bytes: ByteArray): Boolean

/**
 * Deletes the file at [path] if it exists.
 *
 * @return `true` if a file was actually present and removed; `false` if there was nothing to
 *   delete or the delete failed.
 */
internal expect suspend fun deleteFileIfExists(path: String): Boolean

/**
 * Moves the file at [fromPath] to [toPath], replacing any existing file already at [toPath].
 * Every call site in this codebase moves within the same parent directory (the database's own
 * directory), so an atomic same-filesystem rename is the expected, common case; a non-atomic
 * fallback is used only if the platform genuinely can't do an atomic move (defensive -- not
 * expected to trigger given same-directory moves, but cheap to handle).
 *
 * @return `true` on success, `false` if [fromPath] doesn't exist or the move failed for any
 *   reason (nothing at [toPath] is disturbed in that case).
 */
internal expect suspend fun renameFile(fromPath: String, toPath: String): Boolean
