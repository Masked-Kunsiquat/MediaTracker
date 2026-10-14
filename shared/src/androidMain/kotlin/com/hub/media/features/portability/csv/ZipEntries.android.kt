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
    maxTotalBytes: Long,
): Map<String, ByteArray> {
    val result = linkedMapOf<String, ByteArray>()
    var total = 0L
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        generateSequence { zip.nextEntry }.forEach { entry ->
            if (!entry.isDirectory && (wanted == null || entry.name.isWanted(wanted))) {
                val data = zip.readCapped(maxEntryBytes)
                total += data.size
                if (total > maxTotalBytes) throw ZipException("Archive larger than $maxTotalBytes bytes")
                result[entry.name] = data
            } else {
                // Skipping an entry still inflates it -- the stream has to decompress through it to find
                // the next one -- so it is drained here, explicitly, against the same budget. Nothing is
                // kept, but a bomb disguised as an unwanted entry stops at the budget instead of running on.
                total += zip.drainCapped(maxTotalBytes - total)
            }
        }
    }
    if (result.isEmpty()) throw ZipException("No entries")
    return result
}

/** A wanted name at the root, or inside exactly one top-level folder (a re-zipped export folder). */
private fun String.isWanted(wanted: Set<String>): Boolean =
    this in wanted || (count { it == '/' } == 1 && substringAfter('/') in wanted)

/** Inflates and discards the current entry, throwing once it passes [budget]. @return Bytes inflated. */
private fun ZipInputStream.drainCapped(budget: Long): Long {
    val buffer = ByteArray(8 * 1024)
    var drained = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) return drained
        drained += n
        if (drained > budget) throw ZipException("Archive larger than the inflation budget")
    }
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
