package com.hub.media.features.portability.csv

import com.hub.media.features.portability.domain.CsvExportBundle
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** What reading a picked archive produced: its contents, or why it was refused. */
public sealed interface ArchiveReadResult {
    public data class Read(
        val manifest: ExportManifest,
        val libraryCsv: String,
        val readingLogsCsv: String?,
        val episodesCsv: String?,
        val watchLogsCsv: String?,
    ) : ArchiveReadResult

    /** [reason] is shown to the user as is. */
    public data class Refused(
        val reason: String,
    ) : ArchiveReadResult
}

/**
 * The one-file export (#196): `manifest.json` plus the four CSVs, byte-for-byte what the separate
 * files used to be, in one zip. The CSV format is untouched; only the container is new.
 */
public object ExportArchive {
    public const val MANIFEST: String = "manifest.json"
    public const val LIBRARY: String = "library_export.csv"
    public const val READING_LOGS: String = "reading_logs_export.csv"
    public const val EPISODES: String = "episodes_export.csv"
    public const val WATCH_LOGS: String = "watch_logs_export.csv"

    /** The only entries an import keeps; anything else in the zip is drained and discarded. */
    private val ENTRIES = setOf(MANIFEST, LIBRARY, READING_LOGS, EPISODES, WATCH_LOGS)

    public const val NOT_OURS: String = "Not a Media Tracker export"
    public const val NEWER: String =
        "This export was made by a newer version of the app — update it to import this file"
    public const val NO_LIBRARY: String = "This export has no library_export.csv"
    public const val UNREADABLE: String = "Couldn't read this file"
    public const val NOT_LIBRARY_CSV: String =
        "This CSV isn't a library export — pick the export .zip, or a library_export.csv"

    // encodeDefaults: `format` and `formatVersion` equal their defaults, and the version marker is the
    // one field a future import must be able to read -- left out, a newer app would assume its own.
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** Every entry is always written, even a CSV that holds only its header. */
    public fun encode(
        bundle: CsvExportBundle,
        manifest: ExportManifest,
    ): ByteArray =
        zipEntries(
            listOf(
                MANIFEST to json.encodeToString(ExportManifest.serializer(), manifest).encodeToByteArray(),
                LIBRARY to bundle.libraryCsv.encodeToByteArray(),
                READING_LOGS to bundle.readingLogsCsv.encodeToByteArray(),
                EPISODES to bundle.episodesCsv.encodeToByteArray(),
                WATCH_LOGS to bundle.watchLogsCsv.encodeToByteArray(),
            ),
        )

    /**
     * Reads a picked archive, refusing anything that is not one of ours, or is newer than this app
     * understands, **before** a single row is imported. Entries match by exact name, at the root or
     * inside one top-level folder; a duplicated name keeps its last copy.
     */
    public fun decode(
        bytes: ByteArray,
        maxEntryBytes: Long = MAX_ENTRY_BYTES,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
    ): ArchiveReadResult {
        val entries =
            try {
                unzipEntries(bytes, wanted = ENTRIES, maxEntryBytes = maxEntryBytes, maxTotalBytes = maxTotalBytes)
            } catch (e: Exception) {
                return ArchiveReadResult.Refused(UNREADABLE)
            }
        // The export's files sit at the root -- or, once someone has unzipped the export, edited it and
        // compressed the folder again, all inside that one folder. More than one candidate is ambiguous.
        val root = entries.filterKeys { '/' !in it }
        val layout =
            if (MANIFEST in root) {
                root
            } else {
                val folders =
                    entries.keys
                        .filter {
                            it.endsWith(
                                "/$MANIFEST",
                            )
                        }.map { it.substringBefore('/') }
                        .distinct()
                val prefix = (folders.singleOrNull() ?: return ArchiveReadResult.Refused(NOT_OURS)) + "/"
                entries.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
            }
        val manifestBytes = layout[MANIFEST] ?: return ArchiveReadResult.Refused(NOT_OURS)
        val manifest =
            try {
                json.decodeFromString(ExportManifest.serializer(), manifestBytes.decodeToString())
            } catch (e: SerializationException) {
                return ArchiveReadResult.Refused(NOT_OURS)
            } catch (e: IllegalArgumentException) {
                return ArchiveReadResult.Refused(NOT_OURS)
            }
        if (manifest.format != EXPORT_FORMAT) return ArchiveReadResult.Refused(NOT_OURS)
        if (manifest.formatVersion > EXPORT_FORMAT_VERSION) return ArchiveReadResult.Refused(NEWER)
        val library = layout[LIBRARY] ?: return ArchiveReadResult.Refused(NO_LIBRARY)
        return ArchiveReadResult.Read(
            manifest = manifest,
            libraryCsv = library.decodeToString(),
            readingLogsCsv = layout[READING_LOGS]?.decodeToString(),
            episodesCsv = layout[EPISODES]?.decodeToString(),
            watchLogsCsv = layout[WATCH_LOGS]?.decodeToString(),
        )
    }

    /** A zip starts with `PK` 3 4; anything else is treated as a lone CSV. */
    public fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 3.toByte() &&
            bytes[3] == 4.toByte()

    /**
     * Data records in [csv], counted by the same reader the importers use, so a quoted newline is one
     * record and a BOM or CRLF changes nothing. 0 for an unparseable or empty file.
     */
    public fun dataRowCount(csv: String): Int =
        when (val parsed = CsvReader.parse(csv)) {
            is CsvParseResult.Success -> (parsed.rows.size - 1).coerceAtLeast(0)
            is CsvParseResult.Failure -> 0
        }
}
