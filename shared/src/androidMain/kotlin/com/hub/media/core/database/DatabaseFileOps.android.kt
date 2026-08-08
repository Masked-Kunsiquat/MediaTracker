package com.hub.media.core.database

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual suspend fun fileExists(path: String): Boolean = withContext(Dispatchers.IO) {
    File(path).exists()
}

internal actual suspend fun readFileHeaderBytes(path: String, byteCount: Int): ByteArray? =
    withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext null
        try {
            RandomAccessFile(file, "r").use { raf ->
                val size = minOf(byteCount.toLong(), raf.length()).toInt()
                val buffer = ByteArray(size)
                raf.readFully(buffer)
                buffer
            }
        } catch (e: Exception) {
            null
        }
    }

internal actual suspend fun readFileBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
    val file = File(path)
    if (!file.exists()) {
        null
    } else {
        try {
            file.readBytes()
        } catch (e: Exception) {
            null
        }
    }
}

internal actual suspend fun writeFileBytes(path: String, bytes: ByteArray): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            true
        } catch (e: Exception) {
            false
        }
    }

internal actual suspend fun appendFileBytes(path: String, bytes: ByteArray): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            file.parentFile?.mkdirs()
            // FileOutputStream(file, append = true) rather than File.appendBytes(): identical
            // semantics, but explicit about the O_APPEND intent this whole function exists for.
            java.io.FileOutputStream(file, true).use { it.write(bytes) }
            true
        } catch (e: Exception) {
            false
        }
    }

internal actual suspend fun fileSizeBytes(path: String): Long = withContext(Dispatchers.IO) {
    try {
        val file = File(path)
        if (file.exists()) file.length() else 0L
    } catch (e: Exception) {
        0L
    }
}

internal actual suspend fun deleteFileIfExists(path: String): Boolean = withContext(Dispatchers.IO) {
    val file = File(path)
    if (file.exists()) file.delete() else false
}

internal actual suspend fun renameFile(fromPath: String, toPath: String): Boolean =
    withContext(Dispatchers.IO) {
        val from = File(fromPath)
        if (!from.exists()) return@withContext false
        try {
            // ATOMIC_MOVE only -- deliberately no REPLACE_EXISTING-only fallback if the platform
            // provider rejects it (java.nio.file.AtomicMoveNotSupportedException, caught by the
            // generic Exception handler below same as any other failure). Every call site in this
            // codebase moves within the database's own directory, where an atomic rename is the
            // only case that ever actually happens -- but the whole crash-recovery design
            // (selfHealDatabaseIfNeeded's "live file present" sentinel, swap()'s rollback
            // messaging) assumes a renameFile call either fully happens or fully doesn't. A
            // non-atomic copy-then-delete fallback would break that: a process death mid-copy could
            // leave a truncated file at toPath that still satisfies a plain existence check,
            // silently defeating self-heal. Failing the whole rename (false) instead is strictly
            // safer than proceeding non-atomically, and costs nothing in the one filesystem
            // configuration this app ever actually runs on.
            Files.move(
                from.toPath(),
                File(toPath).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (e: Exception) {
            false
        }
    }
