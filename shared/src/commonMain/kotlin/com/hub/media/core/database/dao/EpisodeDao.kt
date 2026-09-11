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
     * Every show holding at least one episode row with a still-empty metadata column, in one query.
     *
     * Seeds #140's library-wide TMDB pass: without it, deciding which shows are worth a request means
     * reading every episode of every show into memory to look at them, which is the shape
     * [observeProgress] already avoids for the same reason — the cost should scale with the number of
     * *shows*, not with the number of episodes across the library.
     *
     * ### `seasonNumber >= 1`, matching what the pass can actually fill
     * Specials are never fetched ([com.hub.media.features.tv.network.TmdbClient.showWithSeasons] does
     * not request season 0), so a hand-entered special with a blank title would otherwise make its
     * show a candidate on every single run for something no request could ever resolve. #88's
     * reasoning about phantom mismatches, applied to candidate selection.
     *
     * ### A show here is not a promise there is something to fill
     * `communityRating` can be legitimately null forever — TMDB has no score for an episode nobody has
     * voted on — so such a show answers this query on every fresh run and spends one request learning
     * there is still nothing. That is the same trade
     * [com.hub.media.features.books.domain.BulkBackfillUseCase] documents for a cover that exists
     * nowhere, and it is priced the same way: telling "confirmed unavailable" apart from "not fetched
     * yet" needs a persisted negative cache, and the cost being avoided is one request per unfillable
     * show per user-initiated run.
     */
    @Query(
        "SELECT DISTINCT mediaId FROM episodes WHERE seasonNumber >= 1 AND (" +
            "title IS NULL OR airDate IS NULL OR runtimeMinutes IS NULL OR " +
            "overview IS NULL OR communityRating IS NULL)",
    )
    suspend fun mediaIdsWithIncompleteEpisodes(): List<String>

    /**
     * Every show holding at least one episode row, regardless of whether anything about it is blank.
     *
     * Backs #123's count checking, which is a **different question** from #140's filling and does not
     * converge the way filling does. A show whose episode metadata is complete can still disagree
     * with TMDB about how many episodes a season has — Fleabag did, at 0 against 6 — and a show that
     * is still airing gains episodes after any number of successful runs. So "needs filling" is the
     * wrong gate for "worth comparing", and this query is the right one.
     *
     * ### Shows with no episodes at all are excluded, and that is the point of the `COUNT`
     * A show nobody has set up yet holds zero rows, and comparing it would report *every* season as
     * "you have 0, TMDB has N". That is not a disagreement to reconcile, it is a show waiting to be
     * quick-filled (#74) — and on a real library it buried the two actionable rows under six noise
     * ones. `GROUP BY` with the implicit `COUNT(*) >= 1` that a group implies keeps them out.
     *
     * ### Scoped to `seasonNumber >= 1`, and it has to be
     * The comparison excludes season 0 (#88), so a show whose only rows are specials has nothing
     * comparable in it. Left unscoped, such a show is queued as a candidate and then dropped by the
     * re-check without a request — inflating the candidate count with a title that can never be
     * reported as updated *or* as having nothing to fill.
     *
     * This is the pair rule [mediaIdsWithIncompleteEpisodes] already states for its own predicate:
     * a re-check narrower than the seed is a bug, whichever direction the difference runs.
     */
    @Query("SELECT mediaId FROM episodes WHERE seasonNumber >= 1 GROUP BY mediaId")
    suspend fun mediaIdsWithAnyEpisodes(): List<String>

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
