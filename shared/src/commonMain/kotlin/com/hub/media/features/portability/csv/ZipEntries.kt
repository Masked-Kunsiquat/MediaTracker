package com.hub.media.features.portability.csv

/** Zips [entries] (name to bytes) into one archive, in order. */
internal expect fun zipEntries(entries: List<Pair<String, ByteArray>>): ByteArray

/**
 * The file entries of a zip whose names are in [wanted] (every entry when it is null), by name --
 * at the root, or inside one top-level folder, as a re-zipped export folder has them.
 * Entries not wanted are skipped without being inflated, and an entry that inflates past
 * [maxEntryBytes] throws, as does inflating more than [maxTotalBytes] across all wanted entries --
 * a crafted archive of many small wanted entries must not exhaust memory either (#196).
 * Throws when [bytes] is not a readable zip holding any wanted entry.
 */
internal expect fun unzipEntries(
    bytes: ByteArray,
    wanted: Set<String>? = null,
    maxEntryBytes: Long = MAX_ENTRY_BYTES,
    maxTotalBytes: Long = MAX_TOTAL_BYTES,
): Map<String, ByteArray>

/** The largest single entry an import inflates: far beyond any real library's CSV. */
internal const val MAX_ENTRY_BYTES: Long = 64L * 1024 * 1024

/** The most an import inflates across every entry it reads: room for the five files, not for a flood. */
internal const val MAX_TOTAL_BYTES: Long = 128L * 1024 * 1024
