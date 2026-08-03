package com.github.maskedkunisquat.mediatracker.export

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * Binary-file counterparts to [writeCsvToUri]/[readCsvFromUri] (ROADMAP Task 8 Phase C, `.sqlite`
 * backup/restore) -- CSV export/import only ever moves [String] text through SAF, but a database
 * backup/restore moves raw bytes, so these stream between a plain local [File] (the app module's
 * own private staging copy -- see `RestoreDatabaseUseCase`'s KDoc for why staging happens on the
 * app-module side before the shared use case is ever called) and a SAF `Uri`, one buffer at a time
 * rather than loading a potentially multi-megabyte database fully into memory as a [ByteArray].
 * Still no new permission: both `Uri`s here come from `ActivityResultContracts.CreateDocument`/
 * `OpenDocument`, exactly like the CSV pickers.
 */

private const val COPY_BUFFER_SIZE = 64 * 1024

/**
 * Streams the local file at [sourcePath] into the SAF destination [uri] (a
 * `CreateDocument`-provided `Uri`) -- the backup write path.
 *
 * @return `true` if the copy completed, `false` on any [IOException] or if the resolver couldn't
 *   open an output stream for [uri] at all.
 */
internal fun copyFileToUri(context: Context, uri: Uri, sourcePath: String): Boolean = try {
    val output = context.contentResolver.openOutputStream(uri)
    if (output == null) {
        false
    } else {
        output.use { out ->
            File(sourcePath).inputStream().use { input -> input.copyTo(out, bufferSize = COPY_BUFFER_SIZE) }
        }
        true
    }
} catch (e: IOException) {
    false
}

/**
 * Streams the SAF source [uri] (an `OpenDocument`-provided `Uri`) into a fresh local file at
 * [destinationPath] -- the restore read path. This *is* the "copy the incoming file to a temp
 * location" step `RestoreDatabaseUseCase.stage`'s KDoc describes: [destinationPath] should be a
 * path in the app's own private storage (e.g. `context.cacheDir`), and the shared use case
 * validates that exact file rather than an in-memory copy of the picked document.
 *
 * @return `true` if the copy completed, `false` on any [IOException] or if the resolver couldn't
 *   open an input stream for [uri] at all (the partially-written destination file, if any, is
 *   deleted so a failed copy never leaves a corrupt-looking leftover).
 */
internal fun copyUriToFile(context: Context, uri: Uri, destinationPath: String): Boolean = try {
    val input = context.contentResolver.openInputStream(uri)
    if (input == null) {
        false
    } else {
        input.use { inp -> File(destinationPath).outputStream().use { out -> inp.copyTo(out, bufferSize = COPY_BUFFER_SIZE) } }
        true
    }
} catch (e: IOException) {
    File(destinationPath).delete()
    false
}
