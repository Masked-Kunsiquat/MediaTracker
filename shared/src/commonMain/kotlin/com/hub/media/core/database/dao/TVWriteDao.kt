package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.hub.media.core.database.entities.AiringStatus
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.TVDetailsEntity
import com.hub.media.core.database.entities.WatchLogEntity
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.database.entities.WatchThroughEntity
import com.hub.media.core.util.newId
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
        insertFirstRunIfMissing(WatchThroughEntity(id = newId(), mediaId = item.id, number = 1))
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

    /**
     * The `tv_details` half of [updateShowMetadataAtomically]: `totalSeasons` only.
     *
     * `status` is deliberately absent (#173). The edit form has no status control -- abandoning is
     * the detail screen's toggle on the show's watch-through (#231) -- so writing it here could only ever put
     * back a value read when the form opened, silently undoing an abandon made since.
     */
    @Query("UPDATE tv_details SET totalSeasons = :totalSeasons WHERE mediaId = :mediaId")
    suspend fun updateTVDetailFields(
        mediaId: String,
        totalSeasons: Int?,
    ): Int

    /**
     * Targeted update of just the editable columns across both tables, in one transaction -- the
     * same shape as [MovieWriteDao.updateMovieMetadataAtomically], and for the same reason: writing
     * a full-row copy back would silently revert a concurrent writer's change to some other column.
     *
     * Self-heals a missing details row rather than reporting a success that wrote half the values.
     * A `media_items` row without its `tv_details` half is the data-integrity edge
     * [com.hub.media.features.media.data.MediaWithDetails.TVShow.details] documents as possible;
     * before this, the `UPDATE` there would match nothing, `totalSeasons` would go nowhere, and
     * the count this returns would still say "updated". Inserting inside the same transaction is
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
    ): Int {
        val mediaRows = updateMediaItemFields(mediaId, title, releaseYear, purchasePrice)
        if (mediaRows > 0) {
            val detailRows = updateTVDetailFields(mediaId, totalSeasons)
            if (detailRows == 0) {
                // The default WATCHLIST is the one status that means nothing for a show (only
                // ABANDONED is read), so this repair invents no state a user could see.
                insertTVDetails(TVDetailsEntity(mediaId = mediaId, totalSeasons = totalSeasons))
            }
        }
        return mediaRows
    }

    /**
     * Fills a show's own provider metadata **onto columns that are still null**, scoped to `TV_SHOW`
     * rows — the show-level counterpart of [fillEpisodeMetadata], obeying the identical rule for the
     * identical reason: every column is wrapped in `COALESCE(column, :value)`, so a value already
     * present wins and a `null` argument changes nothing.
     *
     * Costs no request of its own. The pass that fills a show's episodes already holds the show
     * record, because [com.hub.media.features.tv.network.TmdbClient.showWithSeasons] returns both in
     * one response — so a show that was going to be asked about anyway answers its own blank synopsis
     * and air dates for free.
     *
     * `title` is absent from the SET list for the reason [MovieWriteDao.fillMediaItemMetadata] gives.
     *
     * @return rows affected: `1` when the show exists, `0` when [mediaId] is not a `TV_SHOW` row.
     */
    @Query(
        "UPDATE media_items SET " +
            "releaseYear = COALESCE(releaseYear, :releaseYear), " +
            "communityRating = COALESCE(communityRating, :communityRating), " +
            "synopsis = COALESCE(synopsis, :synopsis) " +
            "WHERE id = :mediaId AND type = 'TV_SHOW'",
    )
    suspend fun fillMediaItemMetadata(
        mediaId: String,
        releaseYear: Int?,
        communityRating: Double?,
        synopsis: String?,
    ): Int

    /**
     * The `tv_details` half of [fillShowMetadata].
     *
     * Neither `status` nor anything watch-related is in the SET list, and they are absent rather than
     * guarded — see [fillEpisodeMetadata] on why absence is the stronger guarantee.
     *
     * `totalSeasons` is filled like any other blank column, and that is safe *because* it is
     * advisory: [com.hub.media.core.database.entities.TVDetailsEntity] documents the episode rows as
     * the truth about what exists. Writing it changes no count and no completion state.
     *
     * @param firstAirDate Epoch milliseconds, not an [Instant] — this column and
     *   [EpisodeEntity.airDate] disagree about representation, a schema inconsistency frozen at v6.
     *   The conversion happens at the mapping edge, exactly where
     *   [com.hub.media.features.tv.data.TVShowRepository.addShow] does it.
     */
    @Query(
        "UPDATE tv_details SET " +
            "totalSeasons = COALESCE(totalSeasons, :totalSeasons), " +
            "airingStatus = COALESCE(airingStatus, :airingStatus), " +
            "firstAirDate = COALESCE(firstAirDate, :firstAirDate), " +
            "lastAirDate = COALESCE(lastAirDate, :lastAirDate) " +
            "WHERE mediaId = :mediaId",
    )
    suspend fun fillTVDetailMetadata(
        mediaId: String,
        totalSeasons: Int?,
        airingStatus: AiringStatus?,
        firstAirDate: Long?,
        lastAirDate: Long?,
    ): Int

    /**
     * Applies both halves of one show's own metadata fill in a single transaction.
     *
     * A missing `tv_details` row is left missing rather than self-healed, unlike
     * [updateShowMetadataAtomically] — see [MovieWriteDao.fillMovieMetadata] for the reasoning, which
     * is the same on both sides: a details row cannot be created without inventing a [WatchStatus],
     * and inventing watch state is the one thing enrichment must never do.
     *
     * @return the number of `media_items` rows affected, so a caller can tell "no such show" (0)
     *   from a fill that landed.
     */
    @Transaction
    suspend fun fillShowMetadata(
        mediaId: String,
        releaseYear: Int?,
        communityRating: Double?,
        totalSeasons: Int?,
        airingStatus: AiringStatus?,
        synopsis: String?,
        firstAirDate: Long?,
        lastAirDate: Long?,
    ): Int {
        val mediaRows = fillMediaItemMetadata(mediaId, releaseYear, communityRating, synopsis)
        if (mediaRows > 0) {
            fillTVDetailMetadata(mediaId, totalSeasons, airingStatus, firstAirDate, lastAirDate)
        }
        return mediaRows
    }

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

    // ---- Watched state and viewings (#90) --------------------------------------------------------
    //
    // Viewings are the truth: every method below leaves `episodes.watchedAt` equal to the episode's
    // latest `watch_logs` row *in the show's latest watch-through* (#231), or `null` when it has none
    // there, inside its own transaction. Nothing else
    // in the app writes `watchedAt` -- see docs/superpowers/specs/2026-10-13-watch-history-design.md.

    @Query("SELECT * FROM episodes WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE mediaId = :mediaId AND seasonNumber = :seasonNumber")
    suspend fun episodesInSeason(
        mediaId: String,
        seasonNumber: Int,
    ): List<EpisodeEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertViewing(log: WatchLogEntity)

    /** Creates watch-through #1 unless the show has it (#231); the unique (mediaId, number) index makes this a no-op. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFirstRunIfMissing(run: WatchThroughEntity): Long

    @Query("SELECT id FROM watch_throughs WHERE mediaId = :mediaId ORDER BY number DESC LIMIT 1")
    suspend fun latestRunIdOrNull(mediaId: String): String?

    /** The show's latest run, creating #1 if it has none -- the self-heal #230's `insertIntoLatestRead` does. */
    suspend fun latestRunId(mediaId: String): String =
        latestRunIdOrNull(mediaId) ?: run {
            insertFirstRunIfMissing(WatchThroughEntity(id = newId(), mediaId = mediaId, number = 1))
            checkNotNull(latestRunIdOrNull(mediaId))
        }

    @Query("SELECT * FROM watch_logs WHERE episodeId = :episodeId")
    suspend fun viewingsFor(episodeId: String): List<WatchLogEntity>

    @Query("SELECT * FROM watch_logs WHERE id = :logId")
    suspend fun viewingById(logId: String): WatchLogEntity?

    @Query("DELETE FROM watch_logs WHERE id = :logId")
    suspend fun deleteViewingRow(logId: String): Int

    @Query("UPDATE watch_logs SET watchedAt = :at WHERE id = :logId")
    suspend fun setViewingDate(
        logId: String,
        at: Instant,
    ): Int

    /** Re-derives `watchedAt` from the episode's viewings in its show's latest run, or `null` with none (#231). */
    @Query(
        "UPDATE episodes SET watchedAt = (SELECT MAX(l.watchedAt) FROM watch_logs l WHERE l.episodeId = :episodeId " +
            "AND l.watchThroughId = (SELECT w.id FROM watch_throughs w WHERE w.mediaId = episodes.mediaId " +
            "ORDER BY w.number DESC LIMIT 1)) WHERE id = :episodeId",
    )
    suspend fun recomputeWatchedAt(episodeId: String): Int

    /** [recomputeWatchedAt] for every episode of a show: after Watch again or Remove this watch-through. */
    @Query(
        "UPDATE episodes SET watchedAt = (SELECT MAX(l.watchedAt) FROM watch_logs l WHERE l.episodeId = episodes.id " +
            "AND l.watchThroughId = (SELECT w.id FROM watch_throughs w WHERE w.mediaId = episodes.mediaId " +
            "ORDER BY w.number DESC LIMIT 1)) WHERE mediaId = :mediaId",
    )
    suspend fun recomputeWatchedAtForShow(mediaId: String): Int

    @Query("SELECT * FROM watch_logs WHERE episodeId = :episodeId AND watchThroughId = :runId")
    suspend fun viewingsInRun(
        episodeId: String,
        runId: String,
    ): List<WatchLogEntity>

    @Query("DELETE FROM watch_logs WHERE episodeId = :episodeId AND watchThroughId = :runId")
    suspend fun deleteViewingsInRun(
        episodeId: String,
        runId: String,
    ): Int

    @Query("SELECT COUNT(*) FROM watch_throughs WHERE id = :runId")
    suspend fun runExists(runId: String): Int

    @Query("SELECT COALESCE(MAX(number), 0) FROM watch_throughs WHERE mediaId = :mediaId")
    suspend fun maxRunNumber(mediaId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: WatchThroughEntity)

    @Query(
        "DELETE FROM watch_throughs WHERE id = :id AND number > 1 " +
            "AND number = (SELECT MAX(w.number) FROM watch_throughs w WHERE w.mediaId = watch_throughs.mediaId) " +
            "AND NOT EXISTS (SELECT 1 FROM watch_logs l WHERE l.watchThroughId = :id)",
    )
    suspend fun deleteRunIfRemovable(id: String): Int

    @Query("SELECT mediaId FROM watch_throughs WHERE id = :id")
    suspend fun runMediaId(id: String): String?

    /**
     * Watch again (#231): run *n+1*, and every episode unticked in the same transaction.
     *
     * [afterNumber] is the run the caller checked was complete or abandoned, so a second call racing the
     * first inserts the same number again and fails on the unique (mediaId, number) index, which the
     * repository reports -- rather than stacking an empty run #3 on top of the new #2.
     *
     * @return the new run's id.
     */
    @Transaction
    suspend fun startNextRun(
        mediaId: String,
        newRunId: String,
        afterNumber: Int? = null,
    ): String {
        latestRunId(mediaId)
        val number = (afterNumber ?: maxRunNumber(mediaId)) + 1
        insertRun(WatchThroughEntity(id = newRunId, mediaId = mediaId, number = number))
        recomputeWatchedAtForShow(mediaId)
        return newRunId
    }

    /** Remove this watch-through (#231): the latest, not #1, no viewings. The previous run's ticks come back. */
    @Transaction
    suspend fun removeRun(runId: String): Boolean {
        val mediaId = runMediaId(runId) ?: return false
        if (deleteRunIfRemovable(runId) == 0) return false
        recomputeWatchedAtForShow(mediaId)
        return true
    }

    /**
     * Sets `watchedAt` only on a still-unwatched episode, so the count is the number that actually
     * changed -- SQLite counts a `COALESCE` that rewrites a row to its own value as an update.
     */
    @Query("UPDATE episodes SET watchedAt = :watchedAt WHERE id = :episodeId AND watchedAt IS NULL")
    suspend fun markEpisodeWatchedIfUnwatched(
        episodeId: String,
        watchedAt: Instant,
    ): Int

    /**
     * Marks each episode watched at its own time (#131), and records that viewing (#90). Only rows
     * the `watchedAt IS NULL` guard actually changed get a viewing: an already-watched episode keeps
     * its date and gains nothing -- what makes a season mark safe over a half-watched season.
     *
     * @return How many episodes actually changed.
     */
    @Transaction
    suspend fun markEpisodesWatchedAt(stamps: Map<String, Instant>): Int {
        var changed = 0
        for ((id, at) in stamps) {
            if (markEpisodeWatchedIfUnwatched(id, at) == 1) {
                val episode = getByIds(listOf(id)).single()
                insertViewing(
                    WatchLogEntity(
                        id = newId(),
                        mediaId = episode.mediaId,
                        episodeId = id,
                        watchedAt = at,
                        watchThroughId = latestRunId(episode.mediaId),
                    ),
                )
                changed++
            }
        }
        return changed
    }

    /**
     * "Watched again" (#90): one more viewing. `watchedAt` becomes the latest viewing, so a
     * back-dated rewatch adds history without moving it.
     *
     * @return `false` when the episode no longer exists.
     */
    @Transaction
    suspend fun addViewing(
        episodeId: String,
        at: Instant,
    ): Boolean {
        val episode = getByIds(listOf(episodeId)).singleOrNull() ?: return false
        insertViewing(
            WatchLogEntity(
                id = newId(),
                mediaId = episode.mediaId,
                episodeId = episodeId,
                watchedAt = at,
                watchThroughId = latestRunId(episode.mediaId),
            ),
        )
        recomputeWatchedAt(episodeId)
        return true
    }

    /**
     * Deletes one viewing and returns it, for Undo. Deleting the last one un-ticks the episode,
     * because `watchedAt` is recomputed from what remains.
     */
    @Transaction
    suspend fun deleteViewing(logId: String): WatchLogEntity? {
        val log = viewingById(logId) ?: return null
        deleteViewingRow(logId)
        log.episodeId?.let { recomputeWatchedAt(it) }
        return log
    }

    /** Corrects one viewing's date (#90) -- how an already-watched date is fixed. */
    @Transaction
    suspend fun updateViewingDate(
        logId: String,
        at: Instant,
    ): Boolean {
        val log = viewingById(logId) ?: return false
        setViewingDate(logId, at)
        log.episodeId?.let { recomputeWatchedAt(it) }
        return true
    }

    /**
     * Undo for [deleteViewing]. Only when the episode still exists -- re-inserting a viewing for a
     * deleted episode would violate `watch_logs`' composite foreign key -- and that viewing is not
     * already back.
     */
    @Transaction
    suspend fun restoreViewing(log: WatchLogEntity): Boolean {
        val episodeId = log.episodeId ?: return false
        if (getByIds(listOf(episodeId)).isEmpty() || viewingById(log.id) != null) return false
        // Its run was removed since (#231): nothing to put it back into.
        if (log.watchThroughId != null && runExists(log.watchThroughId) == 0) return false
        insertViewing(log)
        recomputeWatchedAt(episodeId)
        return true
    }

    /**
     * Unticks one episode: removes its viewings **in the latest run** (#231) and clears `watchedAt`,
     * returning them so Undo can put back exactly what was removed. Earlier runs keep their history.
     * Empty when it was not watched in this run.
     */
    @Transaction
    suspend fun clearEpisodeReturningPrevious(episodeId: String): List<WatchLogEntity> {
        val episode = getByIds(listOf(episodeId)).singleOrNull() ?: return emptyList()
        val run = latestRunId(episode.mediaId)
        val previous = viewingsInRun(episodeId, run)
        deleteViewingsInRun(episodeId, run)
        recomputeWatchedAt(episodeId)
        return previous
    }

    /** [clearEpisodeReturningPrevious] for a whole season: Clear season's Undo restores each one. */
    @Transaction
    suspend fun clearSeasonReturningPrevious(
        mediaId: String,
        seasonNumber: Int,
    ): List<WatchLogEntity> = episodesInSeason(mediaId, seasonNumber).flatMap { clearEpisodeReturningPrevious(it.id) }

    /**
     * Undo for an untick or Clear season. Per episode, only if it **still exists** (a season shrink
     * may have deleted it -- re-inserting would violate the foreign key), is **unwatched** and has
     * **no viewings** (a re-tick or rewatch made since wins).
     *
     * @return How many episodes were restored.
     */
    @Transaction
    suspend fun restoreAll(previous: List<WatchLogEntity>): Int {
        var restored = 0
        for ((episodeId, logs) in previous.filter { it.episodeId != null }.groupBy { it.episodeId!! }) {
            val episode = getByIds(listOf(episodeId)).singleOrNull() ?: continue
            if (episode.watchedAt != null) continue
            // A run removed since (#231) cannot take its viewings back; the foreign key would refuse them.
            if (logs.any { log -> log.watchThroughId?.let { runExists(it) == 0 } == true }) continue
            if (viewingsInRun(episodeId, latestRunId(episode.mediaId)).isNotEmpty()) continue
            logs.forEach { insertViewing(it) }
            recomputeWatchedAt(episodeId)
            restored++
        }
        return restored
    }

    /** Watched episodes with no viewing in their show's latest run (#231). */
    @Query(
        "SELECT * FROM episodes WHERE watchedAt IS NOT NULL AND NOT EXISTS " +
            "(SELECT 1 FROM watch_logs l WHERE l.episodeId = episodes.id AND l.watchThroughId = " +
            "(SELECT w.id FROM watch_throughs w WHERE w.mediaId = episodes.mediaId ORDER BY w.number DESC LIMIT 1))",
    )
    suspend fun watchedWithoutViewings(): List<EpisodeEntity>

    /**
     * The startup backfill (#90): one viewing, at `watchedAt`, for every watched episode that has
     * none. Idempotent by its own filter, and one transaction, so it serialises against a concurrent
     * tick. A read-then-insert rather than `INSERT ... SELECT` because SQLite cannot mint this app's
     * UUID ids.
     *
     * @return How many viewings it created.
     */
    @Transaction
    suspend fun ensureViewingsForWatchedEpisodes(): Int =
        watchedWithoutViewings()
            .onEach { episode ->
                insertViewing(
                    WatchLogEntity(
                        id = newId(),
                        mediaId = episode.mediaId,
                        episodeId = episode.id,
                        watchedAt = checkNotNull(episode.watchedAt),
                        watchThroughId = latestRunId(episode.mediaId),
                    ),
                )
            }.size
}
