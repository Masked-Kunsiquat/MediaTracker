package com.hub.media.core.storage

/**
 * Content-addressable image storage manager.
 *
 * Per AGENTS.md §4 "Cover/Poster Storage Protocol":
 * 1. Compute SHA-256 hash of raw ByteArray
 * 2. Save to `<baseDirectoryPath>/<hash>.jpg`
 * 3. Skip writing if file already exists (automatic deduplication)
 * 4. Store relative filename or hash in database
 *
 * @param baseDirectoryPath Absolute or relative path to the storage directory.
 *   Created if it does not exist.
 */
public class LocalImageStorageManager(private val baseDirectoryPath: String) {

    /**
     * Saves an image byte array with content-addressing (SHA-256 hash).
     *
     * @param bytes Raw image data (e.g., JPEG, PNG)
     * @return Result containing the relative filename `<hash>.jpg` on success,
     *   or an error if the input is empty or I/O fails.
     */
    public suspend fun saveImage(bytes: ByteArray): Result<String> {
        return try {
            if (bytes.isEmpty()) {
                return Result.failure(IllegalArgumentException("Image bytes cannot be empty (corrupt image byte array per AGENTS.md §7)"))
            }

            val hash = sha256Hex(bytes)
            val filename = "$hash.jpg"
            // Returns false when the file already existed — fine for deduplication
            writeImageIfNotExists(baseDirectoryPath, filename, bytes)
            Result.success(filename)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Computes SHA-256 hash as lowercase hex string.
 * Platform-specific implementation required due to java.security.MessageDigest.
 */
internal expect suspend fun sha256Hex(bytes: ByteArray): String

/**
 * Writes image bytes to `<basePath>/<filename>` if it does not already exist.
 * Creates the base directory if missing.
 *
 * @return true if file was written, false if it already existed
 * @throws Exception on I/O errors
 */
internal expect suspend fun writeImageIfNotExists(
    basePath: String,
    filename: String,
    bytes: ByteArray,
): Boolean
