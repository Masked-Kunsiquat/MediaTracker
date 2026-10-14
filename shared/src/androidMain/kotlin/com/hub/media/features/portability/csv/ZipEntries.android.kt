package com.hub.media.features.portability.csv

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal actual fun zipEntries(entries: List<Pair<String, ByteArray>>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, data) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(data)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

internal actual fun unzipEntries(
    bytes: ByteArray,
    wanted: Set<String>?,
    maxEntryBytes: Long,
): Map<String, ByteArray> {
    val result = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        generateSequence { zip.nextEntry }.forEach { entry ->
            // Skipped entries are never inflated: closing them only advances the stream.
            if (!entry.isDirectory && (wanted == null || entry.name in wanted)) {
                result[entry.name] = zip.readCapped(maxEntryBytes)
            }
        }
    }
    if (result.isEmpty()) throw ZipException("No entries")
    return result
}

/** Reads the current entry, throwing once it inflates past [max] rather than holding all of it. */
private fun ZipInputStream.readCapped(max: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        if (total > max) throw ZipException("Entry larger than $max bytes")
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
