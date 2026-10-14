package com.hub.media.features.portability.domain

import com.hub.media.core.database.deleteFilesMatching
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.info
import com.hub.media.core.util.warn
import kotlin.coroutines.cancellation.CancellationException

/**
 * Removes temp files that outlived the flow that made them (#162), run once at startup.
 *
 * - **`<db>.backup-*.tmp`** beside the live database: a snapshot [DatabaseBackupUseCase] staged for
 *   a destination picker that never reported back -- the process died while the picker was open.
 *   A whole-database-sized file, and nothing else ever looked for it.
 * - **`restore-incoming-*.tmp`** in the cache directory: a picked restore file copied in, then
 *   abandoned -- by process death, or by a restore rejected before it was staged.
 *
 * Safe at startup because neither flow survives a process: the backup's pending snapshot and the
 * restore's pending file both live in ViewModels, so at launch nothing can still be using one.
 */
public class SweepStaleTempFilesUseCase(
    private val databaseFilePath: String,
    private val cacheDirPath: String,
    private val logger: Logger = AppLogger,
) {
    /** @return How many files were removed. */
    public suspend fun execute(): Int =
        try {
            val split = maxOf(databaseFilePath.lastIndexOf('/'), databaseFilePath.lastIndexOf('\\'))
            val databaseDir = databaseFilePath.substring(0, split.coerceAtLeast(0))
            val backupPrefix = databaseFilePath.substring(split + 1) + ".backup-"
            val removed =
                deleteFilesMatching(databaseDir) { it.startsWith(backupPrefix) && it.endsWith(".tmp") } +
                    deleteFilesMatching(cacheDirPath) { it.startsWith("restore-incoming-") && it.endsWith(".tmp") }
            if (removed > 0) logger.info(TAG) { "Removed $removed stale temp file(s)" }
            removed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, e) { "Stale temp file sweep failed" }
            0
        }

    private companion object {
        const val TAG = "SweepStaleTempFiles"
    }
}
