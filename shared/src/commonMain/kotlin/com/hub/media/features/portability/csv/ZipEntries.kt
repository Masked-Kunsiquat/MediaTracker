package com.hub.media.features.portability.csv

/** Zips [entries] (name to bytes) into one archive, in order. */
internal expect fun zipEntries(entries: List<Pair<String, ByteArray>>): ByteArray

/**
 * The file entries of a zip whose names are in [wanted] (every entry when it is null), by name --
 * at the root, or inside one top-level folder, as a re-zipped export folder has them.
 * Entries not wanted are skipped without being inflated, and an entry that inflates past
 * [maxEntryBytes] throws: a small crafted archive must not be able to exhaust memory (#196).
 * Throws when [bytes] is not a readable zip holding any wanted entry.
 */
internal expect fun unzipEntries(
    bytes: ByteArray,
    wanted: Set<String>? = null,
    maxEntryBytes: Long = MAX_ENTRY_BYTES,
): Map<String, ByteArray>

/** The largest single entry an import inflates: far beyond any real library's CSV. */
internal const val MAX_ENTRY_BYTES: Long = 64L * 1024 * 1024
