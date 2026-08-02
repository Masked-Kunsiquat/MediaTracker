package com.hub.media.core.database

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
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

internal actual suspend fun deleteFileIfExists(path: String): Boolean = withContext(Dispatchers.IO) {
    val file = File(path)
    if (file.exists()) file.delete() else false
}

internal actual suspend fun renameFile(fromPath: String, toPath: String): Boolean =
    withContext(Dispatchers.IO) {
        val from = File(fromPath)
        if (!from.exists()) return@withContext false
        try {
            try {
                Files.move(
                    from.toPath(),
                    File(toPath).toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(
                    from.toPath(),
                    File(toPath).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            true
        } catch (e: Exception) {
            false
        }
    }
