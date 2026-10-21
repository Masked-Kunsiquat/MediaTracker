package com.hub.media.features.tv.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.dao.TVProgressCounts
import com.hub.media.core.database.entities.WatchThroughEntity
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.guardResource
import com.hub.media.core.util.newId
import com.hub.media.features.books.data.isValidRating
import kotlinx.coroutines.flow.Flow

/**
 * A show's watch-throughs (#231): Watch again, Remove this watch-through, Abandon/Resume, and ratings.
 *
 * @param includeSpecials Whether specials count toward "every episode ticked" -- the setting (#88) the
 *   library and the show screen already count progress by.
 */
public class WatchThroughRepository(
    private val db: AppDatabase,
    private val includeSpecials: suspend () -> Boolean,
    private val logger: Logger = AppLogger,
) {
    public fun observeRuns(mediaId: String): Flow<List<WatchThroughEntity>> =
        db.watchThroughDao().observeForMedia(mediaId)

    /**
     * Starts run *n+1*, offered once the latest is complete or abandoned. Two calls racing each other
     * cannot both succeed: both number the new run from the run they checked, so the second fails on
     * the unique index.
     */
    public suspend fun watchAgain(mediaId: String): Resource<WatchThroughEntity> =
        logger.guardResource(
            tag = TAG,
            failure = "Couldn't start another watch-through",
            logMessage = { "Failed to start a watch-through of show $mediaId" },
        ) {
            val latest = db.watchThroughDao().getLatest(mediaId)
            val episodes = db.episodeDao().getByMediaId(mediaId)
            val counted = TVProgressCounts.of(mediaId, episodes).counted(includeSpecials())
            when {
                latest == null -> Resource.Error("TV show with id=$mediaId not found")
                !latest.abandoned &&
                    !isComplete(
                        counted,
                    ) -> Resource.Error("Finish or abandon this watch-through first")
                else -> {
                    val id = db.tvWriteDao().startNextRun(mediaId, newId(), afterNumber = latest.number)
                    Resource.Success(checkNotNull(db.watchThroughDao().getById(id)))
                }
            }
        }

    /** Removes an empty latest run after #1, the undo for a mistaken Watch again; its ticks give way to the previous run's. */
    public suspend fun removeRun(runId: String): Resource<Unit> =
        logger.guardResource(
            tag = TAG,
            failure = "Couldn't remove the watch-through",
            logMessage = { "Failed to remove watch-through $runId" },
        ) {
            if (db.tvWriteDao().removeRun(runId)) {
                Resource.Success(Unit)
            } else {
                Resource.Error("Only an empty latest watch-through after #1 can be removed")
            }
        }

    /** Abandon or Resume: the latest run's flag. Earlier runs keep theirs. */
    public suspend fun setAbandoned(
        mediaId: String,
        abandoned: Boolean,
    ): Resource<Unit> =
        logger.guardResource(
            tag = TAG,
            failure = "Failed to update show status",
            logMessage = { "Failed to update status for show: id=$mediaId" },
        ) {
            val latest = db.watchThroughDao().getLatest(mediaId)
            if (latest == null) {
                Resource.Error("TV show with id=$mediaId not found")
            } else {
                db.watchThroughDao().setAbandoned(latest.id, abandoned)
                Resource.Success(Unit)
            }
        }

    public suspend fun setRating(
        runId: String,
        halves: Int?,
    ): Resource<Unit> =
        logger.guardResource(
            tag = TAG,
            failure = "Failed to save the rating",
            logMessage = { "Failed to rate watch-through $runId" },
        ) {
            when {
                halves != null && !isValidRating(halves) -> Resource.Error("A rating is half a star to five stars")
                db.watchThroughDao().updateRating(runId, halves) == 0 -> Resource.Error("Watch-through not found")
                else -> Resource.Success(Unit)
            }
        }

    private companion object {
        const val TAG = "WatchThroughRepository"
    }
}
