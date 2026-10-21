package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.hub.media.core.database.entities.WatchLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reads `watch_logs` (#90). **Read-only**, like [EpisodeDao]: every write must keep
 * `episodes.watchedAt` equal to the episode's latest viewing, so writes live as transactions in
 * [TVWriteDao], where the episode row is in the same transaction.
 */
@Dao
interface WatchLogDao {
    /** One episode's viewings, newest first -- the episode sheet's history list. */
    @Query("SELECT * FROM watch_logs WHERE episodeId = :episodeId ORDER BY watchedAt DESC")
    fun observeForEpisode(episodeId: String): Flow<List<WatchLogEntity>>

    /**
     * How many viewings each of a show's episodes has, in one `GROUP BY` -- the "Watched N×" row
     * label. Episodes with no viewings are absent rather than zero.
     */
    @Query(
        "SELECT episodeId, COUNT(*) AS viewings FROM watch_logs " +
            "WHERE mediaId = :mediaId AND episodeId IS NOT NULL GROUP BY episodeId",
    )
    fun observeViewingCounts(mediaId: String): Flow<List<EpisodeViewingCount>>

    /** A film's viewings (#192), newest first: its rows with no episode. */
    @Query("SELECT * FROM watch_logs WHERE mediaId = :mediaId AND episodeId IS NULL ORDER BY watchedAt DESC")
    fun observeForFilm(mediaId: String): Flow<List<WatchLogEntity>>

    /** Every viewing, in a stable order -- `watch_logs_export.csv`. */
    @Query("SELECT * FROM watch_logs ORDER BY mediaId, episodeId, watchedAt")
    fun observeAll(): Flow<List<WatchLogEntity>>

    /** Per run and season: distinct episodes viewed, and the run's first and last viewing there (#231). */
    @Query(
        "SELECT l.watchThroughId AS runId, e.seasonNumber AS seasonNumber, COUNT(DISTINCT l.episodeId) AS episodes, " +
            "MIN(l.watchedAt) AS firstAt, MAX(l.watchedAt) AS lastAt FROM watch_logs l " +
            "JOIN episodes e ON e.id = l.episodeId WHERE l.mediaId = :mediaId AND l.watchThroughId IS NOT NULL " +
            "GROUP BY l.watchThroughId, e.seasonNumber",
    )
    fun observeRunSeasonRows(mediaId: String): Flow<List<RunSeasonRow>>
}

/** A projection of [WatchLogDao.observeViewingCounts]. */
public data class EpisodeViewingCount(
    val episodeId: String,
    val viewings: Int,
)
