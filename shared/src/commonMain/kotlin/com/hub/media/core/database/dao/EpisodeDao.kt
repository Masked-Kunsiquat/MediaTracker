package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.hub.media.core.database.entities.EpisodeEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reads [EpisodeEntity] (ROADMAP Task 13 Phase C). Read-only for the same reason
 * [TVDetailsDao]/[MovieDetailsDao] are: every write to this table is either part of a multi-table
 * transaction (quick-fill inserts a show's `media_items`/`tv_details`/`episodes` rows together) or
 * needs an atomically-scoped `UPDATE`, so it belongs to [TVWriteDao] rather than here.
 */
@Dao
interface EpisodeDao {
    /** All episodes of a show, ordered for season-by-season display. */
    @Query("SELECT * FROM episodes WHERE mediaId = :mediaId ORDER BY seasonNumber ASC, episodeNumber ASC")
    fun observeByMediaId(mediaId: String): Flow<List<EpisodeEntity>>

    /** One-shot counterpart of [observeByMediaId], same ordering. */
    @Query("SELECT * FROM episodes WHERE mediaId = :mediaId ORDER BY seasonNumber ASC, episodeNumber ASC")
    suspend fun getByMediaId(mediaId: String): List<EpisodeEntity>

    /**
     * The episodes already recorded for one season of one show, ordered by [EpisodeEntity.episodeNumber].
     * Backs [com.hub.media.features.tv.data.TVShowRepository.setSeasonLength], which reads this to work
     * out which episode numbers are missing before quick-filling the rest -- see that function's
     * KDoc for why it must not re-create episodes that already exist.
     */
    @Query(
        "SELECT * FROM episodes WHERE mediaId = :mediaId AND seasonNumber = :seasonNumber " +
            "ORDER BY episodeNumber ASC",
    )
    suspend fun getByMediaIdAndSeason(
        mediaId: String,
        seasonNumber: Int,
    ): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getById(id: String): EpisodeEntity?

    /**
     * Whole-table observation. Backs [com.hub.media.core.database.MediaRepository.observeAllEpisodes]
     * (ROADMAP Task 13 Phase C), which `episodes_export.csv` reads through -- the library reads
     * [observeProgress] and a show screen reads [observeByMediaId] for their own narrower needs, but
     * an export needs every episode in the database regardless of show.
     */
    @Query("SELECT * FROM episodes")
    fun observeAll(): Flow<List<EpisodeEntity>>

    /**
     * Per-show watched/total episode counts, one row per show that has at least one episode.
     * Backs the library list's progress display ("4 / 10 episodes") without loading every episode
     * row into memory -- a `GROUP BY` aggregate scales with the number of *shows*, not the number
     * of episodes across the whole library.
     *
     * This is the query form of the rule [com.hub.media.core.database.entities.TVDetailsEntity]'s
     * KDoc states: there is no stored progress counter, so "episodes watched" is always derived by
     * counting [EpisodeEntity.watchedAt] `IS NOT NULL` rows. [TVProgressRow] is a plain projection
     * (no `@Entity`) matched to this query's column aliases by constructor parameter name -- it is
     * not a table.
     */
    @Query(
        "SELECT mediaId, COUNT(*) AS totalEpisodes, " +
            "SUM(CASE WHEN watchedAt IS NOT NULL THEN 1 ELSE 0 END) AS watchedEpisodes " +
            "FROM episodes GROUP BY mediaId",
    )
    fun observeProgress(): Flow<List<TVProgressRow>>
}

/**
 * Derived (never stored) per-show episode progress -- see [EpisodeDao.observeProgress].
 *
 * @property totalEpisodes Every episode row that exists for [mediaId], watched or not.
 * @property watchedEpisodes The subset of those with a non-null [EpisodeEntity.watchedAt].
 */
public data class TVProgressRow(
    val mediaId: String,
    val totalEpisodes: Int,
    val watchedEpisodes: Int,
)
