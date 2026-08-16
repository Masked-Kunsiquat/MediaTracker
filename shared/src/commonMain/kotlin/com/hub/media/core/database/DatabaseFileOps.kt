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
internal expect suspend fun readFileHeaderBytes(
    path: String,
    byteCount: Int,
): ByteArray?

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
internal expect suspend fun writeFileBytes(
    path: String,
    bytes: ByteArray,
): Boolean

/**
 * Appends [bytes] to the end of the file at [path], creating it (and any missing parent
 * directories) if it does not yet exist. Unlike [writeFileBytes], this never reads or rewrites
 * the file's existing contents.
 *
 * Added for the ROADMAP Task 15 Phase B log store, which is append-only by nature and where the
 * read-modify-write alternative was actively harmful on both counts that matter:
 * - **Cost.** Rewriting the whole file per flush makes each flush O(file size) rather than
 *   O(batch size). With a ~1 MB cap that is a 1 MB read plus a 1 MB write for every batch of
 *   buffered entries -- which would have defeated the very rationale the buffering exists for
 *   (that task's "a bulk backfill over hundreds of books must not hit disk synchronously per log
 *   entry"), trading many small writes for far more total bytes moved.
 * - **Crash damage.** A whole-file rewrite puts every previously-written record at risk on every
 *   flush: a process death mid-write can damage records written minutes ago. A real append
 *   confines the worst case to the tail of the file, which is exactly the bounded failure mode the
 *   log codec's malformed-line tolerance is designed around.
 *
 * @return `true` if the append succeeded.
 */
internal expect suspend fun appendFileBytes(
    path: String,
    bytes: ByteArray,
): Boolean

/**
 * Size of the file at [path] in bytes, or `0` if it does not exist or cannot be read.
 *
 * Exists so the Task 15 Phase B log store can make its rollover decision (is this file over the
 * cap?) without reading the file's contents into memory purely to call `.size` on them -- the
 * whole point of [appendFileBytes] above is that the flush path never needs the existing bytes.
 */
internal expect suspend fun fileSizeBytes(path: String): Long

/**
 * Reads at most the *last* [byteCount] bytes of the file at [path] -- the tail counterpart to
 * [readFileHeaderBytes], added so the Task 15 Phase B log store can find the highest sequence
 * number it retained without loading a ~1 MB file to look at its final line. That scan runs
 * synchronously on process startup, so reading only the tail is the difference between a fixed
 * few kilobytes and the whole retained log on every launch.
 *
 * **The first line in the returned window is very likely truncated**, both because the window
 * starts at an arbitrary byte offset and because that offset can land inside a multi-byte UTF-8
 * sequence. Callers must therefore treat the leading partial line as unusable rather than assuming
 * the window begins on a record boundary -- the log store's tail scan works backwards from the end
 * and skips anything that fails to decode, which handles this for free.
 *
 * @return The bytes read (the whole file if it is shorter than [byteCount]), or `null` if the file
 *   doesn't exist or can't be read.
 */
internal expect suspend fun readFileTailBytes(
    path: String,
    byteCount: Int,
): ByteArray?

/**
 * Deletes the file at [path] if it exists.
 *
 * @return `true` if a file was actually present and removed; `false` if there was nothing to
 *   delete or the delete failed.
 */
internal expect suspend fun deleteFileIfExists(path: String): Boolean

/**
 * Moves the file at [fromPath] to [toPath] atomically, replacing any existing file already at
 * [toPath]. Every call site in this codebase moves within the same parent directory (the
 * database's own directory), so this is expected to succeed via a real atomic rename on any
 * filesystem this app actually runs on. Deliberately **requires** the atomic path rather than
 * falling back to a non-atomic copy-then-delete if the platform provider rejects it: the
 * crash-recovery design built on top of this function ([selfHealDatabaseIfNeeded]'s "live file
 * present" sentinel, [com.hub.media.features.portability.domain.DefaultRestoreDatabaseUseCase.swap]'s
 * rollback messaging) assumes every call either fully happens or fully doesn't -- a non-atomic
 * fallback could leave a truncated file at [toPath] that a plain existence check can't tell apart
 * from a genuine one if a process dies mid-copy, defeating that whole design silently.
 *
 * @return `true` on success, `false` if [fromPath] doesn't exist or the move failed for any
 *   reason, including the platform being unable to do an atomic move (nothing at [toPath] is
 *   disturbed in that case).
 */
internal expect suspend fun renameFile(
    fromPath: String,
    toPath: String,
): Boolean
