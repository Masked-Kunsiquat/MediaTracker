package com.hub.media.features.tv.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.error
import com.hub.media.core.util.info
import kotlin.coroutines.cancellation.CancellationException

/**
 * The startup backfill (#90): gives every watched episode that has no viewing exactly one, at its
 * `watchedAt`, so "viewings are the truth" also holds for episodes ticked before viewings existed.
 *
 * Deliberately cheap rather than a migration -- there are no users yet (owner, 2026-10-13). It is
 * idempotent by its own filter, one transaction (so it serialises against a tick), and safe to run
 * on every launch. A restore ends in a process restart, so restored older backups are covered too.
 */
public class EnsureViewingsUseCase(
    private val db: AppDatabase,
    private val logger: Logger = AppLogger,
) {
    /** @return How many viewings were created; 0 once everything has one. */
    public suspend fun execute(): Resource<Int> =
        try {
            val created = db.tvWriteDao().ensureViewingsForWatchedEpisodes()
            if (created > 0) logger.info(TAG) { "Backfilled $created viewing(s) for watched episodes" }
            Resource.Success(created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Viewing backfill failed" }
            Resource.Error("Viewing backfill failed: ${e.message ?: "Unknown error"}", cause = e)
        }

    private companion object {
        const val TAG = "EnsureViewings"
    }
}
