package com.hub.media.core.storage

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of SHA-256 hashing using java.security.MessageDigest.
 */
internal actual suspend fun sha256Hex(bytes: ByteArray): String = withContext(Dispatchers.IO) {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(bytes)
    hashBytes.joinToString("") { "%02x".format(it) }
}

/**
 * Android implementation of image file writing with deduplication.
 * Uses java.io.File for file I/O.
 */
internal actual suspend fun writeImageIfNotExists(
    basePath: String,
    filename: String,
    bytes: ByteArray,
): Boolean = withContext(Dispatchers.IO) {
    val baseDir = File(basePath)
    baseDir.mkdirs()

    val file = File(baseDir, filename)
    if (file.exists()) {
        // File already exists, skip writing (deduplication)
        false
    } else {
        file.writeBytes(bytes)
        true
    }
}

internal actual suspend fun deleteImageFile(directoryPath: String, fileName: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val file = File(directoryPath, fileName)
            if (file.exists()) file.delete() else false
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // Never swallowed: this is coroutine cancellation, not a delete failure. Absorbing it
            // would let a cancelled caller carry on as though nothing happened.
            throw e
        } catch (e: Exception) {
            false
        }
    }
