package com.github.maskedkunisquat.mediatracker.export

import android.content.Context
import android.net.Uri
import java.io.IOException

/**
 * Writes [content] as UTF-8 bytes to [uri] via the platform [android.content.ContentResolver]
 * (ROADMAP Task 8 Phase A). This is the one Android-specific step in the export pipeline: the
 * shared module's `LibraryCsvExporter`/`ReadingLogCsvExporter`/`ExportDataUseCase` are pure
 * Kotlin/KMP-clean and only ever produce [String]s (AGENTS.md §6) -- only this app module touches
 * `Uri`/`ContentResolver`, matching the existing shared-vs-app split ([android.net.Uri] and file
 * I/O have never belonged in `shared`).
 *
 * [uri] is expected to come from an `ActivityResultContracts.CreateDocument` launch (Storage
 * Access Framework), which needs **no runtime permission** -- the system file picker grants this
 * app write access to exactly the one document the user chose, nothing more. This is the SAF
 * plumbing the ROADMAP's deferred "manual cover entry" backlog item was waiting on (see
 * `ROADMAP.md`'s backlog section): the next feature that needs a file/photo picker can reuse this
 * exact pattern instead of re-deriving it.
 *
 * @return `true` if the write completed, `false` on any [IOException] or if the resolver could not
 *   open an output stream for [uri] at all (both surfaced by the caller as a failure Snackbar
 *   rather than a crash, per AGENTS.md §5 -- a silently failed export would be worse than no
 *   export button).
 */
internal fun writeCsvToUri(
    context: Context,
    uri: Uri,
    content: String,
): Boolean =
    try {
        val stream = context.contentResolver.openOutputStream(uri)
        if (stream == null) {
            false
        } else {
            stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            true
        }
    } catch (e: IOException) {
        false
    }

/** [writeCsvToUri] for bytes: the one-file export's zip (#196). Same contract. */
internal fun writeBytesToUri(
    context: Context,
    uri: Uri,
    bytes: ByteArray,
): Boolean =
    try {
        val stream = context.contentResolver.openOutputStream(uri)
        if (stream == null) {
            false
        } else {
            stream.use { it.write(bytes) }
            true
        }
    } catch (e: IOException) {
        false
    }

/**
 * Deletes a document a `CreateDocument` dialog created, best-effort: a provider may not support
 * deletion. For a save whose content was lost to process death (#162, #196), so the user is not
 * left with an empty file that looks like a backup or an export.
 */
internal fun deleteCreatedDocument(
    context: Context,
    uri: Uri,
) {
    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
}

/** The largest file the one-dialog import reads (#196): far beyond any real export. */
internal const val MAX_IMPORT_FILE_BYTES: Long = 64L * 1024 * 1024

/**
 * Reads [input] whole if it holds at most [max] bytes, or returns `null` as soon as it proves larger,
 * having held no more than [max] bytes. A picked file of any size must not be able to exhaust memory.
 */
internal fun readAtMost(
    input: java.io.InputStream,
    max: Long,
): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return out.toByteArray()
        total += n
        if (total > max) return null
        out.write(buffer, 0, n)
    }
}
