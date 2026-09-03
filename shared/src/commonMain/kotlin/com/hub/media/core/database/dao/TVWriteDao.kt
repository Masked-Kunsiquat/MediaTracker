package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.TVDetailsEntity
import com.hub.media.core.database.entities.WatchStatus
import kotlin.time.Instant

/**
 * Multi-table TV writes that must be atomic (ROADMAP Task 13 Phase C) -- the TV counterpart of
 * [MovieWriteDao]. A show additionally spans `episodes`, so the atomic surface here is one table
 * wider than the movie equivalent.
 *
 * Every insert uses [OnConflictStrategy.ABORT] so a constraint violation throws rather than being
 * silently replaced, which is what lets [insertShowAtomically] roll the whole operation back.
 */
@Dao
interface TVWriteDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMediaItem(item: MediaItemEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTVDetails(details: TVDetailsEntity)

    /**
     * Inserts one or more episode rows. Used both inside [insertShowAtomically] (a new show's
     * initial quick-fill) and standalone by
     * [com.hub.media.features.tv.data.TVShowRepository.setSeasonLength] (quick-filling a season onto an
     * existing show, where `media_items`/`tv_details` already exist and only this table changes).
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEpisodes(episodes: List<EpisodeEntity>)

    /**
     * Inserts one provider mapping for a show, mirroring [BookWriteDao.insertExternalIdentifier] --
     * ABORT for the same reason: a duplicate `(mediaId, provider)` inside a single add is a caller
     * bug, and rolling the whole insert back is the right answer to it.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExternalIdentifier(identifier: ExternalIdentifierEntity)

    /**
     * Inserts a show's [MediaItemEntity], [TVDetailsEntity], its quick-filled [EpisodeEntity] rows,
     * and any [ExternalIdentifierEntity] mappings in one transaction. If any part fails, none of it
     * remains -- a show that exists in the library with no details row, or with details but no
     * episodes, is not a state any caller should have to handle.
     *
     * [externalIdentifiers] joins the transaction rather than being written afterwards because the
     * mapping is what a later pass matches a library row *back* to its provider record on. A show
     * inserted without its TMDB id is not merely missing a field: nothing can tell that row apart
     * from one typed in by hand, so the backfill that fills episode titles has no way to know which
     * show to ask about, and the only repair is the user identifying it again. Written second-best
     * -- outside the transaction -- a process death between the two writes produces exactly that row.
     */
    @Transaction
    suspend fun insertShowAtomically(
        item: MediaItemEntity,
        details: TVDetailsEntity,
        episodes: List<EpisodeEntity>,
        externalIdentifiers: List<ExternalIdentifierEntity>,
    ) {
        insertMediaItem(item)
        insertTVDetails(details)
        insertEpisodes(episodes)
        externalIdentifiers.forEach { insertExternalIdentifier(it) }
    }

    /** The episode numbers already recorded for one season, for [insertMissingEpisodes]. */
    @Query("SELECT episodeNumber FROM episodes WHERE mediaId = :mediaId AND seasonNumber = :seasonNumber")
    suspend fun episodeNumbersInSeason(
        mediaId: String,
        seasonNumber: Int,
    ): List<Int>

    /**
     * Inserts whichever of [candidates] are not already present, deciding which those are **inside
     * the same transaction as the insert**.
     *
     * Reading the season and then inserting as two separate operations leaves a window: two
     * quick-fills of the same season racing (a double-tapped confirm button is the realistic way in)
     * both see the same "missing" set, and the second insert hits the unique
     * `(mediaId, seasonNumber, episodeNumber)` index. Nothing is corrupted — the insert aborts and
     * rolls back — but the user is shown a raw constraint failure for having tapped twice. Deciding
     * and inserting under one transaction removes the window rather than reporting it.
     *
     * @param candidates Every episode row the caller would create for a full season, already
     *   carrying its generated id; this filters them down to the ones that do not exist yet.
     * @return the number of rows actually inserted, which is 0 when the season is already complete.
     */
    @Transaction
    suspend fun insertMissingEpisodes(
        mediaId: String,
        seasonNumber: Int,
        candidates: List<EpisodeEntity>,
    ): Int {
        val existing = episodeNumbersInSeason(mediaId, seasonNumber).toSet()
        val missing = candidates.filterNot { it.episodeNumber in existing }
        if (missing.isNotEmpty()) {
            insertEpisodes(missing)
        }
        return missing.size
    }

    /** Deletes every episode of one season. Backs removing a season added by mistake. */
    @Query("DELETE FROM episodes WHERE mediaId = :mediaId AND seasonNumber = :seasonNumber")
    suspend fun deleteSeason(
        mediaId: String,
        seasonNumber: Int,
    ): Int

    /** Deletes the episodes of one season numbered above [keepCount]. */
    @Query(
        "DELETE FROM episodes WHERE mediaId = :mediaId AND seasonNumber = :seasonNumber " +
            "AND episodeNumber > :keepCount",
    )
    suspend fun deleteEpisodesAbove(
        mediaId: String,
        seasonNumber: Int,
        keepCount: Int,
    ): Int

    /**
     * Makes one season exactly [episodeCount] episodes long, in a single transaction: inserting
     * whichever of [candidates] are missing and deleting anything numbered above the count.
     *
     * Both halves together, because a season length is one intent. Quick-fill could only ever grow a
     * season before this, so a mistyped count (20 where 10 was meant) left ten episodes that could
     * not be removed and a show that could never read as finished — the only escape was deleting the
     * show, which takes every watched date on it.
     *
     * Deleting *is* destructive, unlike everything else quick-fill does: an episode numbered above
     * the new count takes its [EpisodeEntity.watchedAt] with it. That is the caller's decision to
     * confirm, not this DAO's to soften — it is why the count of what would be lost belongs in front
     * of the user before this runs.
     *
     * @return the number of episode rows deleted, so a caller can report what a shrink actually cost.
     */
    @Transaction
    suspend fun setSeasonLength(
        mediaId: String,
        seasonNumber: Int,
        episodeCount: Int,
        candidates: List<EpisodeEntity>,
    ): Int {
        insertMissingEpisodes(mediaId, seasonNumber, candidates)
        return deleteEpisodesAbove(mediaId, seasonNumber, episodeCount)
    }

    /**
     * Targeted update of `media_items`' editable columns, scoped to `TV_SHOW` rows.
     *
     * The `type` predicate is what makes this DAO's affected-row count mean "no such *show*" rather
     * than merely "no such row" -- see [MovieWriteDao.updateMediaItemFields]'s KDoc for why: without
     * it, a book's id reaching
     * [com.hub.media.features.tv.data.TVShowRepository.updateShowMetadata] would overwrite that
     * book's title/releaseYear/purchasePrice with TV-form values and still report success.
     *
     * The literal matches `Converters.mediaTypeToName`, which persists a `MediaType` as its `name`
     * -- the same spelling Room's own generated binding uses for a typed parameter.
     */
    @Query(
        "UPDATE media_items SET title = :title, releaseYear = :releaseYear, " +
            "purchasePrice = :purchasePrice WHERE id = :mediaId AND type = 'TV_SHOW'",
    )
    suspend fun updateMediaItemFields(
        mediaId: String,
        title: String,
        releaseYear: Int?,
        purchasePrice: Double?,
    ): Int

    @Query("UPDATE tv_details SET totalSeasons = :totalSeasons, status = :status WHERE mediaId = :mediaId")
    suspend fun updateTVDetailFields(
        mediaId: String,
        totalSeasons: Int?,
        status: WatchStatus,
    ): Int

    /**
     * Targeted update of just the status column, leaving `totalSeasons` alone. Backs
     * [com.hub.media.features.tv.data.TVShowRepository.updateWatchStatus], which exists so a status
     * tap does not have to re-send `totalSeasons` just to change this one column -- same reasoning
     * as [MovieWriteDao.updateWatchStatusFields].
     *
     * No `type` predicate, and none is needed: `tv_details` holds TV rows only, so its affected-row
     * count already means "no such show" on its own.
     */
    @Query("UPDATE tv_details SET status = :status WHERE mediaId = :mediaId")
    suspend fun updateStatusFields(
        mediaId: String,
        status: WatchStatus,
    ): Int

    /**
     * Targeted update of just the editable columns across both tables, in one transaction -- the
     * same shape as [MovieWriteDao.updateMovieMetadataAtomically], and for the same reason: writing
     * a full-row copy back would silently revert a concurrent writer's change to some other column.
     *
     * Self-heals a missing details row rather than reporting a success that wrote half the values.
     * A `media_items` row without its `tv_details` half is the data-integrity edge
     * [com.hub.media.features.media.data.MediaWithDetails.TVShow.details] documents as possible;
     * before this, the `UPDATE` there would match nothing, `totalSeasons`/`status` would go nowhere,
     * and the count this returns would still say "updated". Inserting inside the same transaction is
     * what makes the repair atomic with the half that did land.
     *
     * @return the number of `media_items` rows affected, so a caller can tell "no such show" (0)
     *   from a successful update.
     */
    @Transaction
    suspend fun updateShowMetadataAtomically(
        mediaId: String,
        title: String,
        releaseYear: Int?,
        purchasePrice: Double?,
        totalSeasons: Int?,
        status: WatchStatus,
    ): Int {
        val mediaRows = updateMediaItemFields(mediaId, title, releaseYear, purchasePrice)
        if (mediaRows > 0) {
            val detailRows = updateTVDetailFields(mediaId, totalSeasons, status)
            if (detailRows == 0) {
                insertTVDetails(
                    TVDetailsEntity(
                        mediaId = mediaId,
                        totalSeasons = totalSeasons,
                        status = status,
                    ),
                )
            }
        }
        return mediaRows
    }

    /**
     * The per-episode watched tick, scoped by episode id.
     *
     * `COALESCE` is what preserves an existing timestamp: re-ticking an already-watched episode must
     * not bump the date it was actually watched, and deciding that in SQL rather than by reading the
     * row first means there is no window between the decision and the write for a second tick to
     * land in. It is the single-episode form of the `watchedAt IS NULL` predicate
     * [markSeasonWatched] uses for the same reason.
     *
     * @return `1` if [episodeId] resolved to an existing row, `0` otherwise -- including when the
     *   episode was already watched and `COALESCE` therefore wrote the value it already held.
     */

    /**
     * Fills an episode's provider metadata **onto columns that are still null**, matching the row by
     * its natural key rather than by id.
     *
     * ### The rule is the statement, not the caller's care
     * #75 requires that the backfill enrich and never overwrite: it runs against a library the user
     * has already been ticking off, and a background pass that changed what they had recorded would
     * be the worst failure this app could have. Two things enforce that here rather than in Kotlin:
     *
     * - Every column is wrapped in `COALESCE(column, :value)`, so a value already present wins and a
     *   `null` argument changes nothing. A correction the user typed survives a later pass.
     * - **`watchedAt` is not in the SET list at all.** Not guarded, not conditional -- absent. This
     *   statement has no way to alter watch state, so no future edit to a caller can make it do so.
     *
     * ### Matched on (seasonNumber, episodeNumber), not on id
     * A provider knows nothing about this app's row ids. #74 settled that an episode's identity in
     * the world is its slot within its show, which is why `episodes` carries a unique index on
     * exactly this triple -- the same key #113's CSV importer matches on, arrived at independently.
     *
     * @return rows affected: `1` when the episode exists, `0` when the provider described one this
     *   library does not hold. Zero is ordinary rather than an error -- the backfill never creates --
     *   and it is what lets a caller count what it actually filled.
     */
    @Query(
        "UPDATE episodes SET " +
            "title = COALESCE(title, :title), " +
            "airDate = COALESCE(airDate, :airDate), " +
            "runtimeMinutes = COALESCE(runtimeMinutes, :runtimeMinutes), " +
            "overview = COALESCE(overview, :overview), " +
            "communityRating = COALESCE(communityRating, :communityRating) " +
            "WHERE mediaId = :mediaId AND seasonNumber = :seasonNumber AND episodeNumber = :episodeNumber",
    )
    suspend fun fillEpisodeMetadata(
        mediaId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        title: String?,
        airDate: Instant?,
        runtimeMinutes: Int?,
        overview: String?,
        communityRating: Double?,
    ): Int

    /**
     * Applies [fills] in one transaction, returning how many rows they matched.
     *
     * A show is one request but many rows -- 458 of them for a daily series -- and one implicit
     * transaction per `UPDATE` means one disk sync per episode, which on a phone is the difference
     * between a pause and a stall. Batching is a performance fix rather than a correctness one:
     * enrichment is idempotent, so a pass that died halfway simply fills the rest next time.
     *
     * @return the summed affected-row count, so a caller still learns how many episodes it matched.
     */
    @Transaction
    suspend fun fillEpisodeMetadata(
        mediaId: String,
        fills: List<EpisodeMetadataFill>,
    ): Int {
        var matched = 0
        for (fill in fills) {
            matched +=
                fillEpisodeMetadata(
                    mediaId = mediaId,
                    seasonNumber = fill.seasonNumber,
                    episodeNumber = fill.episodeNumber,
                    title = fill.title,
                    airDate = fill.airDate,
                    runtimeMinutes = fill.runtimeMinutes,
                    overview = fill.overview,
                    communityRating = fill.communityRating,
                )
        }
        return matched
    }

    @Query("UPDATE episodes SET watchedAt = COALESCE(watchedAt, :watchedAt) WHERE id = :episodeId")
    suspend fun markEpisodeWatched(
        episodeId: String,
        watchedAt: Instant,
    ): Int

    /**
     * Clears one episode's watched state. Unconditional, unlike [markEpisodeWatched]: there is no
     * timestamp worth preserving on the way to `null`.
     *
     * @return `1` if [episodeId] resolved to an existing row, `0` otherwise.
     */
    @Query("UPDATE episodes SET watchedAt = NULL WHERE id = :episodeId")
    suspend fun clearEpisodeWatched(episodeId: String): Int

    /**
     * Marks every *unwatched* episode of one season watched, stamping [watchedAt] on each.
     *
     * The `watchedAt IS NULL` predicate is the point of this query, not an optimization: without
     * it, marking a season watched would rewrite the timestamp of every episode already watched,
     * so ticking the last episode of a season you finished months ago would restamp the whole
     * season as watched today. That is the same rule
     * [com.hub.media.features.tv.data.TVShowRepository.setEpisodeWatched] applies per episode --
     * an already-watched episode keeps the date it was actually watched -- and a bulk action is
     * not a reason to abandon it.
     *
     * @return the number of episode rows that changed, which is *not* the season's size: a season
     *   already fully watched returns 0 while being a perfectly successful no-op. Callers must not
     *   read 0 here as "no such season" -- see that repository function for how it tells them apart.
     */
    @Query(
        "UPDATE episodes SET watchedAt = :watchedAt WHERE mediaId = :mediaId AND " +
            "seasonNumber = :seasonNumber AND watchedAt IS NULL",
    )
    suspend fun markSeasonWatched(
        mediaId: String,
        seasonNumber: Int,
        watchedAt: Instant,
    ): Int

    /**
     * Clears the watched state of every episode of one season. Unconditional, unlike
     * [markSeasonWatched]: there is no timestamp worth preserving on the way to `null`, and a
     * partially-watched season must end up fully unwatched.
     *
     * @return the number of episode rows affected.
     */
    @Query("UPDATE episodes SET watchedAt = NULL WHERE mediaId = :mediaId AND seasonNumber = :seasonNumber")
    suspend fun clearSeasonWatched(
        mediaId: String,
        seasonNumber: Int,
    ): Int
}
