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
 * Only files last modified **before this process started** are removed: neither flow survives a
 * process (both keep their pending file in a ViewModel), so those are certainly abandoned. The sweep
 * runs asynchronously at launch, so a file a flow created since then may be in use and is kept.
 */
public class SweepStaleTempFilesUseCase(
    private val databaseFilePath: String,
    private val cacheDirPath: String,
    private val logger: Logger = AppLogger,
) {
    /**
     * @param startedAtMillis When this process started, in epoch millis.
     * @return How many files were removed.
     */
    public suspend fun execute(startedAtMillis: Long): Int =
        try {
            val split = maxOf(databaseFilePath.lastIndexOf('/'), databaseFilePath.lastIndexOf('\\'))
            val databaseDir = databaseFilePath.substring(0, split.coerceAtLeast(0))
            val backupPrefix = databaseFilePath.substring(split + 1) + ".backup-"
            val removed =
                deleteFilesMatching(databaseDir) { name, modified ->
                    modified < startedAtMillis && name.startsWith(backupPrefix) && name.endsWith(".tmp")
                } +
                    deleteFilesMatching(cacheDirPath) { name, modified ->
                        modified < startedAtMillis && name.startsWith("restore-incoming-") && name.endsWith(".tmp")
                    }
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
