package com.hub.media.features.tv.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.error
import kotlinx.datetime.TimeZone
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * "Watched again" (#90): records one more viewing of an episode. The date goes through
 * [resolveWatchedAt], the same rules #131's tick prompt uses -- noon local, clamped to now, air
 * dates read in UTC -- and the write keeps `watchedAt` the latest viewing
 * ([com.hub.media.core.database.dao.TVWriteDao.addViewing]).
 */
public class RecordRewatchUseCase(
    private val db: AppDatabase,
    private val clock: Clock = Clock.System,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val logger: Logger = AppLogger,
) {
    public suspend fun execute(
        episodeId: String,
        watchedWhen: WatchedWhen,
    ): Resource<Unit> =
        try {
            val episode = db.episodeDao().getById(episodeId)
            val at = episode?.let { resolveWatchedAt(watchedWhen, it.airDate, clock.now(), zone()) }
            when {
                episode == null -> Resource.Error("Episode with id=$episodeId not found")
                at == null -> Resource.Error("This episode has no past air date to mark it watched on")
                !db.tvWriteDao().addViewing(episodeId, at) -> Resource.Error("Episode with id=$episodeId not found")
                else -> Resource.Success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to record a rewatch of episode $episodeId" }
            Resource.Error("Failed to record the viewing: ${e.message ?: "Unknown error"}", cause = e)
        }

    private companion object {
        const val TAG = "RecordRewatch"
    }
}
