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
internal fun writeCsvToUri(context: Context, uri: Uri, content: String): Boolean = try {
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
