package com.github.maskedkunisquat.mediatracker.export

import android.content.Context
import android.net.Uri
import java.io.IOException

/**
 * Reads [uri]'s full content as UTF-8 text via the platform [android.content.ContentResolver]
 * (ROADMAP Task 8 Phase B) -- the read-side mirror of [writeCsvToUri]. This is the one
 * Android-specific step in the import pipeline: the shared module's `CsvTableReader`/
 * `LibraryCsvImporter`/`ReadingLogCsvImporter`/`ImportDataUseCase` are pure Kotlin/KMP-clean and
 * only ever consume [String]s (AGENTS.md §6) -- only this app module touches `Uri`/
 * `ContentResolver`.
 *
 * [uri] is expected to come from an `ActivityResultContracts.OpenDocument` launch (Storage Access
 * Framework), which needs **no runtime permission** -- same SAF plumbing [writeCsvToUri]
 * established for export, reused here for read access to exactly the one document the user chose.
 *
 * @return The file's text content, or `null` if the resolver could not open an input stream for
 *   [uri] at all, or any [IOException] occurred while reading (both surfaced by the caller as a
 *   failure Snackbar rather than a crash, per AGENTS.md §5).
 */
internal fun readCsvFromUri(context: Context, uri: Uri): String? = try {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        stream.readBytes().toString(Charsets.UTF_8)
    }
} catch (e: IOException) {
    null
}
