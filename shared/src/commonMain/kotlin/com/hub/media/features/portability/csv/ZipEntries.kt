package com.hub.media.features.portability.csv

/** Zips [entries] (name to bytes) into one archive, in order. */
internal expect fun zipEntries(entries: List<Pair<String, ByteArray>>): ByteArray

/** Every file entry in a zip, by name. Throws when [bytes] is not a readable, non-empty zip. */
internal expect fun unzipEntries(bytes: ByteArray): Map<String, ByteArray>
