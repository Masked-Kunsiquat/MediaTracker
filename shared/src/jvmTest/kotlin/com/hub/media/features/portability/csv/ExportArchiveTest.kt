package com.hub.media.features.portability.csv

import com.hub.media.features.portability.domain.CsvExportBundle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The export archive (#196): one zip holding `manifest.json` and the four unchanged CSVs, and every
 * way a picked file is refused before anything is imported.
 */
class ExportArchiveTest {
    private val bundle =
        CsvExportBundle(
            libraryCsv = "a,b\r\n1,2\r\n3,\"multi\nline\"\r\n",
            readingLogsCsv = "a,b\r\n",
            episodesCsv = "a,b\r\n1,2\r\n",
            watchLogsCsv = "a,b\r\n",
        )
    private val manifest =
        ExportManifest(
            appVersion = "0.25.0",
            exportedAt = "2026-10-14T03:00:00Z",
            files = listOf(ExportManifestFile(ExportArchive.LIBRARY, 6, 2)),
            settings = mapOf("include_specials" to "false"),
        )

    private fun manifestBytes(m: ExportManifest = manifest) = Json.encodeToString(m).encodeToByteArray()

    private fun zipWith(m: ExportManifest) =
        zipEntries(
            listOf(
                ExportArchive.MANIFEST to manifestBytes(m),
                ExportArchive.LIBRARY to "a,b\r\n".encodeToByteArray(),
            ),
        )

    private fun assertRefused(
        bytes: ByteArray,
        reason: String,
    ) = assertEquals(ArchiveReadResult.Refused(reason), ExportArchive.decode(bytes))

    @Test
    fun roundTrip_keepsEveryFileByteForByte_andTheManifest() {
        val read = assertIs<ArchiveReadResult.Read>(ExportArchive.decode(ExportArchive.encode(bundle, manifest)))
        assertEquals(bundle.libraryCsv, read.libraryCsv)
        assertEquals(bundle.readingLogsCsv, read.readingLogsCsv)
        assertEquals(bundle.episodesCsv, read.episodesCsv)
        assertEquals(bundle.watchLogsCsv, read.watchLogsCsv)
        assertEquals(manifest, read.manifest)
    }

    @Test
    fun manifest_alwaysWritesItsFormatAndVersion() {
        // Found on the device (#196): kotlinx.serialization omits default-valued fields, so the version
        // marker every future import relies on was never written. A later app would then read its own
        // current version as the default -- the check would pass for anything.
        val manifestJson =
            unzipEntries(
                ExportArchive.encode(bundle, manifest),
            ).getValue(ExportArchive.MANIFEST).decodeToString()
        assertTrue("\"format\":\"media-tracker-export\"" in manifestJson, manifestJson)
        assertTrue("\"formatVersion\":1" in manifestJson, manifestJson)
    }

    @Test
    fun isZip_checksTheHeader() {
        assertTrue(ExportArchive.isZip(ExportArchive.encode(bundle, manifest)))
        assertFalse(ExportArchive.isZip("csv_schema_version,".encodeToByteArray()))
        assertFalse(ExportArchive.isZip(ByteArray(0)))
    }

    @Test
    fun dataRowCount_countsRecordsNotLines() {
        assertEquals(2, ExportArchive.dataRowCount(bundle.libraryCsv), "a quoted newline is one record")
        assertEquals(0, ExportArchive.dataRowCount(bundle.readingLogsCsv))
        assertEquals(1, ExportArchive.dataRowCount("\uFEFFa,b\n1,2\n"), "a BOM and LF endings do not change the count")
    }

    @Test
    fun noManifest_isRefused() =
        assertRefused(
            zipEntries(listOf(ExportArchive.LIBRARY to "a,b\r\n".encodeToByteArray())),
            "Not a Media Tracker export",
        )

    @Test
    fun malformedManifest_isRefused() =
        assertRefused(
            zipEntries(
                listOf(
                    ExportArchive.MANIFEST to "{\"format\":".encodeToByteArray(),
                    ExportArchive.LIBRARY to "a,b\r\n".encodeToByteArray(),
                ),
            ),
            "Not a Media Tracker export",
        )

    @Test
    fun wrongFormat_isRefused() =
        assertRefused(zipWith(manifest.copy(format = "something-else")), "Not a Media Tracker export")

    @Test
    fun newerFormatVersion_isRefused() =
        assertRefused(
            zipWith(manifest.copy(formatVersion = EXPORT_FORMAT_VERSION + 1)),
            "This export was made by a newer version of the app — update it to import this file",
        )

    @Test
    fun noLibrary_isRefused() =
        assertRefused(
            zipEntries(listOf(ExportArchive.MANIFEST to manifestBytes())),
            "This export has no library_export.csv",
        )

    @Test
    fun unreadableBytes_areRefused() = assertRefused(byteArrayOf(0x50, 0x4B, 3, 4, 9, 9, 9), "Couldn't read this file")

    @Test
    fun optionalFilesMissing_readAsNull() {
        val read = assertIs<ArchiveReadResult.Read>(ExportArchive.decode(zipWith(manifest)))
        assertNull(read.readingLogsCsv)
        assertNull(read.episodesCsv)
        assertNull(read.watchLogsCsv)
    }

    @Test
    fun anOversizedWantedEntry_isRefused_notInflated() {
        // A zip bomb (final review, #196): a small archive that inflates past the cap is refused, not read.
        val bomb =
            zipEntries(
                listOf(
                    ExportArchive.MANIFEST to manifestBytes(),
                    ExportArchive.LIBRARY to ByteArray(10_000),
                ),
            )
        assertTrue(bomb.size < 10_000, "positive control: the entry compresses well below its inflated size")
        assertEquals(
            ArchiveReadResult.Refused(ExportArchive.UNREADABLE),
            ExportArchive.decode(bomb, maxEntryBytes = 1_000),
        )
    }

    @Test
    fun unrelatedEntries_areSkipped_notInflated() {
        // An unrelated, oversized entry -- a photo in a backup zip -- is never read, so it cannot exhaust memory.
        val zip =
            zipEntries(
                listOf(
                    ExportArchive.MANIFEST to manifestBytes(),
                    ExportArchive.LIBRARY to "a,b\r\n".encodeToByteArray(),
                    "photos/huge.jpg" to ByteArray(10_000),
                ),
            )
        assertIs<ArchiveReadResult.Read>(ExportArchive.decode(zip, maxEntryBytes = 1_000))
    }

    @Test
    fun aRezippedFolder_isRead() {
        // Unzip, edit a CSV, compress the folder again (Finder or Windows): every entry now sits under the
        // folder's name, and macOS adds __MACOSX resource forks beside it (#196 re-grade).
        val folder = "media-tracker-export-2026-10-14/"
        val zip =
            zipEntries(
                listOf(
                    folder + ExportArchive.MANIFEST to manifestBytes(),
                    folder + ExportArchive.LIBRARY to "a,b\r\n1,2\r\n".encodeToByteArray(),
                    folder + ExportArchive.EPISODES to "a,b\r\n".encodeToByteArray(),
                    "__MACOSX/" + folder + "._manifest.json" to byteArrayOf(0, 5, 22, 7),
                ),
            )
        val read = assertIs<ArchiveReadResult.Read>(ExportArchive.decode(zip))
        assertEquals("a,b\r\n1,2\r\n", read.libraryCsv)
        assertEquals("a,b\r\n", read.episodesCsv)
        assertNull(read.watchLogsCsv)
    }

    @Test
    fun twoFoldersWithManifests_isRefused() {
        // Ambiguous: which export is the user importing? Refuse rather than guess.
        val zip =
            zipEntries(
                listOf(
                    "one/" + ExportArchive.MANIFEST to manifestBytes(),
                    "one/" + ExportArchive.LIBRARY to "a,b\r\n".encodeToByteArray(),
                    "two/" + ExportArchive.MANIFEST to manifestBytes(),
                    "two/" + ExportArchive.LIBRARY to "a,b\r\n".encodeToByteArray(),
                ),
            )
        assertEquals(ArchiveReadResult.Refused(ExportArchive.NOT_OURS), ExportArchive.decode(zip))
    }

    @Test
    fun manyFoldersOfWantedEntries_cannotExceedTheTotalCap() {
        // Security review (#199): every entry is under the per-entry cap, but thousands of folders each
        // holding a wanted name would all be inflated and kept. The total is capped too.
        val manyFolders = (1..50).map { "f$it/" + ExportArchive.LIBRARY to ByteArray(500) }
        val zip = zipEntries(manyFolders)
        assertEquals(
            ArchiveReadResult.Refused(ExportArchive.UNREADABLE),
            ExportArchive.decode(zip, maxEntryBytes = 1_000, maxTotalBytes = 10_000),
        )
    }

    @Test
    fun skippedEntries_countAgainstTheTotalBudget() {
        // CodeRabbit (#199): skipping an entry still inflates it to find the next one. A bomb disguised as
        // an unwanted entry must hit the budget, not run unbounded.
        val zip =
            zipEntries(
                listOf(
                    ExportArchive.MANIFEST to manifestBytes(),
                    ExportArchive.LIBRARY to "a,b\r\n".encodeToByteArray(),
                    "bomb.bin" to ByteArray(20_000),
                ),
            )
        assertEquals(
            ArchiveReadResult.Refused(ExportArchive.UNREADABLE),
            ExportArchive.decode(zip, maxEntryBytes = 1_000_000, maxTotalBytes = 10_000),
        )
    }

    @Test
    fun unknownManifestFields_areIgnored() {
        val json =
            """{"format":"media-tracker-export","formatVersion":1,"appVersion":"9","exportedAt":"x",""" +
                """"files":[],"future":{"anything":1}}"""
        val bytes =
            zipEntries(
                listOf(
                    ExportArchive.MANIFEST to json.encodeToByteArray(),
                    ExportArchive.LIBRARY to "a,b\r\n".encodeToByteArray(),
                ),
            )
        val read = assertIs<ArchiveReadResult.Read>(ExportArchive.decode(bytes))
        assertEquals("9", read.manifest.appVersion)
        assertEquals(emptyMap(), read.manifest.settings, "settings default to empty when absent")
    }
}
