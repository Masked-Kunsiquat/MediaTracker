package com.hub.media.features.media.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.error
import kotlin.coroutines.cancellation.CancellationException

/**
 * Outcome of a bulk delete.
 *
 * @property itemsDeleted Rows actually removed.
 * @property coversRemoved Cover files deleted because nothing referenced them any more.
 * @property coversKept Cover files left in place because a surviving item still references them.
 */
public data class DeleteMediaSummary(
    val itemsDeleted: Int,
    val coversRemoved: Int,
    val coversKept: Int,
)

/**
 * Abstraction over bulk delete.
 * Consolidated and generalized per Issue #67.
 */
public interface BulkDeleteUseCase {
    /** See [DeleteMediaUseCase.execute]. */
    public suspend fun execute(ids: List<String>): Resource<DeleteMediaSummary>
}

/**
 * Deletes one or more media items and cleans up any cover files that become unreferenced.
 */
public class DeleteMediaUseCase(
    private val database: AppDatabase,
    private val imageStorage: LocalImageStorageManager,
    private val logger: Logger = AppLogger,
) : BulkDeleteUseCase {
    /**
     * Deletes the items identified by [ids] and removes any cover file left unreferenced.
     */
    public override suspend fun execute(ids: List<String>): Resource<DeleteMediaSummary> {
        if (ids.isEmpty()) return Resource.Success(DeleteMediaSummary(0, 0, 0))
        return try {
            val dao = database.mediaItemDao()
            // Read the candidate hashes before the rows go.
            val candidateHashes = dao.getCoverHashesForIds(ids)
            val itemsDeleted = dao.deleteByIds(ids)

            var removed = 0
            var kept = 0
            for (filename in candidateHashes) {
                val hash = filename.removeSuffix(".jpg")
                imageStorage.withLock(hash) {
                    if (dao.countByCoverHash(filename) > 0) {
                        kept++
                    } else if (imageStorage.deleteImage(filename, useLock = false)) {
                        removed++
                    } else {
                        logger.error(TAG) { "Failed to delete unreferenced cover file: $filename" }
                    }
                }
            }
            Resource.Success(
                DeleteMediaSummary(itemsDeleted = itemsDeleted, coversRemoved = removed, coversKept = kept),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Bulk delete failed for ${ids.size} items" }
            Resource.Error(message = "Failed to delete items: ${e.message ?: "Unknown error"}", cause = e)
        }
    }

    private companion object {
        const val TAG = "DeleteMediaUseCase"
    }
}
