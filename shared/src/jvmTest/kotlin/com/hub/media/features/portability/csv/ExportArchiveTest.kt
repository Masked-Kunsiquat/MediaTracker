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
        zipEntries(listOf(ExportArchive.MANIFEST to manifestBytes(m), ExportArchive.LIBRARY to "a,b\r\n".encodeToByteArray()))

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
    fun isZip_checksTheHeader() {
        assertTrue(ExportArchive.isZip(ExportArchive.encode(bundle, manifest)))
        assertFalse(ExportArchive.isZip("csv_schema_version,".encodeToByteArray()))
        assertFalse(ExportArchive.isZip(ByteArray(0)))
    }

    @Test
    fun dataRowCount_countsRecordsNotLines() {
        assertEquals(2, ExportArchive.dataRowCount(bundle.libraryCsv), "a quoted newline is one record")
        assertEquals(0, ExportArchive.dataRowCount(bundle.readingLogsCsv))
        assertEquals(1, ExportArchive.dataRowCount("﻿a,b\n1,2\n"), "a BOM and LF endings do not change the count")
    }

    @Test
    fun noManifest_isRefused() =
        assertRefused(zipEntries(listOf(ExportArchive.LIBRARY to "a,b\r\n".encodeToByteArray())), "Not a Media Tracker export")

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
    fun wrongFormat_isRefused() = assertRefused(zipWith(manifest.copy(format = "something-else")), "Not a Media Tracker export")

    @Test
    fun newerFormatVersion_isRefused() =
        assertRefused(
            zipWith(manifest.copy(formatVersion = EXPORT_FORMAT_VERSION + 1)),
            "This export was made by a newer version of the app — update it to import this file",
        )

    @Test
    fun noLibrary_isRefused() =
        assertRefused(zipEntries(listOf(ExportArchive.MANIFEST to manifestBytes())), "This export has no library_export.csv")

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
    fun unknownManifestFields_areIgnored() {
        val json =
            """{"format":"media-tracker-export","formatVersion":1,"appVersion":"9","exportedAt":"x",""" +
                """"files":[],"future":{"anything":1}}"""
        val bytes = zipEntries(listOf(ExportArchive.MANIFEST to json.encodeToByteArray(), ExportArchive.LIBRARY to "a,b\r\n".encodeToByteArray()))
        val read = assertIs<ArchiveReadResult.Read>(ExportArchive.decode(bytes))
        assertEquals("9", read.manifest.appVersion)
        assertEquals(emptyMap(), read.manifest.settings, "settings default to empty when absent")
    }
}
