package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MovieDetailsEntity
import com.hub.media.core.database.entities.ReadThroughEntity
import com.hub.media.core.database.entities.ReadingSessionEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TVDetailsEntity
import com.hub.media.core.database.entities.WatchLogEntity
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.database.entities.WatchThroughEntity
import com.hub.media.core.util.newId
import kotlin.time.Instant

/**
 * The details row accompanying one imported [MediaItemEntity] -- exactly one of the three
 * per-media-type detail tables (Issue #106).
 *
 * A sealed hierarchy rather than three nullable fields on [ImportMediaInsert]/[ImportMediaUpdate]:
 * an item has exactly one details row, and three nullables would make "a movie carrying book
 * details" and "an item carrying none" both representable and silently wrong. The `when` in
 * [ImportWriteDao.importAtomically] is exhaustive over this, so a fourth media type cannot be added
 * without the write path failing to compile -- which is the point. This mirrors
 * [com.hub.media.features.media.data.MediaWithDetails]'s shape on the read side.
 */
public sealed class ImportDetails {
    /**
     * @property readOne The book's read #1 as this import leaves it (#230): from the file's status and finish
     *   date (and a Goodreads rating), merged with the existing read #1 under the duplicate policy. `null`
     *   leaves the book's reads alone, only making sure read #1 exists.
     */
    public data class Book(
        public val details: BookDetailsEntity,
        public val readOne: ReadThroughEntity? = null,
    ) : ImportDetails()

    public data class Movie(
        public val details: MovieDetailsEntity,
    ) : ImportDetails()

    public data class TVShow(
        public val details: TVDetailsEntity,
    ) : ImportDetails()
}

/**
 * A brand-new item to insert as part of a bulk CSV import (ROADMAP Task 8 Phase B): this item's
 * `media_id` did not match anything already in the database, so [mediaItem]/[details] are fresh
 * rows and [identifiers] are inserted as-is.
 *
 * [details]' variant is expected to agree with [mediaItem]'s
 * [com.hub.media.core.database.entities.MediaItemEntity.type] -- guaranteed by construction in
 * [com.hub.media.features.portability.domain.ImportDataUseCase], which builds both from the same
 * parsed row's type. Not re-checked here: this DAO does no validation of its own (see the interface
 * KDoc), and a mismatch would in any case fail loudly at the details table's own foreign key rather
 * than corrupt anything.
 */
public data class ImportMediaInsert(
    public val mediaItem: MediaItemEntity,
    public val details: ImportDetails,
    public val identifiers: List<ExternalIdentifierEntity>,
)

/**
 * An existing item to update as part of a bulk CSV import. [mediaItem]/[details] are the full
 * replacement rows -- already resolved by
 * [com.hub.media.features.portability.domain.ImportDataUseCase] according to the chosen duplicate
 * policy (REPLACE overwrites every field this importer manages; MERGE only backfills fields the
 * existing row left null -- see that class's KDoc for the exact per-field rules).
 *
 * @property replaceIdentifiers When `true`, every existing [ExternalIdentifierEntity] for this
 *   book is deleted before [identifiers] are inserted (REPLACE policy: the imported set becomes
 *   the complete set). When `false`, [identifiers] are inserted additively with nothing deleted
 *   first (MERGE policy: [identifiers] here is pre-filtered by the use case to only providers the
 *   book didn't already have, so merge can never overwrite an existing provider mapping).
 */
public data class ImportMediaUpdate(
    public val mediaItem: MediaItemEntity,
    public val details: ImportDetails,
    public val identifiers: List<ExternalIdentifierEntity>,
    public val replaceIdentifiers: Boolean,
)

/**
 * Write-side DAO for CSV import's single all-or-nothing transaction (ROADMAP Task 8 Phase B,
 * mirroring [BookWriteDao]'s exact shape and rollback guarantee, just scaled from one book to a
 * whole file's worth).
 *
 * Every method here is a raw insert/update with no validation of its own -- all business-rule
 * validation and duplicate-policy resolution happens in
 * [com.hub.media.features.portability.domain.ImportDataUseCase] *before* [importAtomically] is
 * ever called, reading a snapshot of the database first and deciding what to do with every row.
 * By the time this method runs, every operation it performs is expected to succeed; the only
 * failures it needs to guard against are genuine, unexpected ones (e.g. a constraint violation),
 * and Room's `@Transaction` default-body semantics mean that if *any* statement below throws, the
 * whole method rolls back -- nothing already applied within the same call survives (AGENTS.md §1:
 * "user data safety over shortcuts" -- a malformed row late in the file must not leave a
 * half-imported library).
 */
@Dao
public interface ImportWriteDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertMediaItem(item: MediaItemEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertBookDetails(details: BookDetailsEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertMovieDetails(details: MovieDetailsEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertTvDetails(details: TVDetailsEntity)

    /**
     * @return The number of rows affected: `1` if [item]'s id resolved to an existing row, `0`
     *   otherwise. Room's generated `@Update` silently no-ops rather than throwing when the primary
     *   key doesn't match an existing row -- e.g. the row was deleted by another writer between the
     *   pre-write snapshot [com.hub.media.features.portability.domain.ImportDataUseCase] resolved
     *   duplicates against and this transaction actually running -- so [importAtomically] must check
     *   this return value rather than assuming success (mirrors
     *   [ReadingSessionDao.update]/[com.hub.media.features.books.data.ReadingSessionRepository
     *   .updateSession]'s identical guard for the exact same Room behavior).
     */
    @Update
    public suspend fun updateMediaItem(item: MediaItemEntity): Int

    /** @return Rows affected -- see [updateMediaItem]'s KDoc; the same guard applies here. */
    @Update
    public suspend fun updateBookDetails(details: BookDetailsEntity): Int

    /** @return Rows affected -- see [updateMediaItem]'s KDoc; the same guard applies here. */
    @Update
    public suspend fun updateMovieDetails(details: MovieDetailsEntity): Int

    /** @return Rows affected -- see [updateMediaItem]'s KDoc; the same guard applies here. */
    @Update
    public suspend fun updateTvDetails(details: TVDetailsEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertEpisode(episode: EpisodeEntity)

    /** @return Rows affected -- see [updateMediaItem]'s KDoc; the same guard applies here. */
    @Update
    public suspend fun updateEpisode(episode: EpisodeEntity): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertExternalIdentifier(identifier: ExternalIdentifierEntity)

    @Query("DELETE FROM external_identifiers WHERE mediaId = :mediaId")
    public suspend fun deleteExternalIdentifiersForMedia(mediaId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertSession(session: ReadingSessionEntity)

    /** Read #1 for a book that has none yet (#230); a no-op when it exists, via the unique (mediaId, number) index. */
    @Query(
        "INSERT OR IGNORE INTO read_throughs (id, mediaId, number, status, startedAt, finishedAt, rating) " +
            "VALUES (:id, :mediaId, 1, :status, NULL, :finishedAt, NULL)",
    )
    public suspend fun insertFirstReadIfMissing(
        id: String,
        mediaId: String,
        status: ReadingStatus,
        finishedAt: Instant?,
    )

    @Query("SELECT id FROM read_throughs WHERE mediaId = :mediaId ORDER BY number DESC LIMIT 1")
    public suspend fun latestReadId(mediaId: String): String?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertRead(read: ReadThroughEntity)

    @Query(
        "UPDATE read_throughs SET status = :status, startedAt = :startedAt, finishedAt = :finishedAt, " +
            "rating = :rating WHERE mediaId = :mediaId AND number = :number",
    )
    public suspend fun updateReadByNumber(
        mediaId: String,
        number: Int,
        status: ReadingStatus,
        startedAt: Instant?,
        finishedAt: Instant?,
        rating: Int?,
    ): Int

    /** Writes [read] onto the book's read with its number, or inserts it when there is none (#230). */
    private suspend fun upsertRead(read: ReadThroughEntity) {
        val updated =
            updateReadByNumber(read.mediaId, read.number, read.status, read.startedAt, read.finishedAt, read.rating)
        if (updated == 0) insertRead(read)
    }

    private suspend fun writeReadOne(details: ImportDetails.Book) {
        val readOne = details.readOne
        if (readOne != null) {
            upsertRead(readOne)
        } else {
            // No read #1 planned: the book keeps its reads, and one that somehow has none gets a To read
            // read #1, as every book must have.
            insertFirstReadIfMissing(newId(), details.details.mediaId, ReadingStatus.TO_READ, null)
        }
    }

    /**
     * [session] with its read filled in (#230): a session the import built from the file carries a blank
     * `readThroughId` and joins its book's latest read, which this transaction has already created.
     */
    private suspend fun withRead(session: ReadingSessionEntity): ReadingSessionEntity =
        if (session.readThroughId.isNotEmpty()) {
            session
        } else {
            // A book with no read at all gets read #1 at Reading, as ReadingSessionDao.insertIntoLatestRead does.
            val readId =
                latestReadId(session.mediaId)
                    ?: newId().also { insertFirstReadIfMissing(it, session.mediaId, ReadingStatus.READING, null) }
            session.copy(readThroughId = readId)
        }

    /** @return Rows affected -- see [updateMediaItem]'s KDoc; the same guard applies here. */
    @Update
    public suspend fun updateSession(session: ReadingSessionEntity): Int

    /**
     * Applies every queued media insert/update, then every queued session and episode
     * insert/update, all in one transaction.
     *
     * ### Media rows go first, and here that is a requirement rather than a preference
     * Both `reading_sessions.mediaId` and `episodes.mediaId` are foreign keys into `media_items`,
     * enforced immediately by SQLite rather than deferred to commit. A session or episode belonging
     * to an item this same import is inserting therefore *must* be written after that item's own
     * row, or the insert throws and rolls the whole transaction back. The loops below are ordered
     * accordingly.
     *
     * That is the only ordering this method needs. Which *item* a session or episode belongs to was
     * already settled upstream by
     * [com.hub.media.features.portability.domain.ImportDataUseCase], against the *pre-write*
     * snapshot of known media (existing rows plus this same import's own), so no loop here has to
     * look anything up. Sessions and episodes are mutually independent and their relative order
     * carries no meaning.
     *
     * ### Every update's affected-row count is checked
     * [updateMediaItem]/[updateBookDetails]/[updateSession] and their movie/show/episode
     * counterparts resolve duplicates against a snapshot
     * read *before* this transaction opened -- so a row present in that snapshot could have been
     * deleted by a concurrent writer in the window between the read and this call. Room's `@Update`
     * does not throw in that case, it just affects zero rows, which would otherwise let this method
     * (and [com.hub.media.features.portability.domain.ImportDataUseCase]'s summary) silently report
     * a delete-raced row as "updated" when nothing was actually written. Each call below is checked
     * against that zero-rows-affected signal and throws (rolling back the whole transaction, same as
     * a genuine constraint violation) rather than letting it pass silently -- AGENTS.md §1 requires
     * the write path fail loudly here rather than let the summary over-report.
     */
    @Transaction
    public suspend fun importAtomically(
        mediaInserts: List<ImportMediaInsert>,
        mediaUpdates: List<ImportMediaUpdate>,
        sessionInserts: List<ReadingSessionEntity>,
        sessionUpdates: List<ReadingSessionEntity>,
        episodeInserts: List<EpisodeEntity>,
        episodeUpdates: List<EpisodeEntity>,
        viewingDeletesForEpisodes: Collection<String> = emptyList(),
        viewingInserts: List<WatchLogEntity> = emptyList(),
        touchedEpisodeIds: Collection<String> = emptyList(),
        filmViewingDeletes: Collection<String> = emptyList(),
        touchedFilmIds: Collection<String> = emptyList(),
        readUpserts: List<ReadThroughEntity> = emptyList(),
    ) {
        for (insert in mediaInserts) {
            insertMediaItem(insert.mediaItem)
            when (val details = insert.details) {
                is ImportDetails.Book -> {
                    insertBookDetails(details.details)
                    writeReadOne(details)
                }
                is ImportDetails.Movie -> insertMovieDetails(details.details)
                is ImportDetails.TVShow -> {
                    insertTvDetails(details.details)
                    insertFirstRunIfMissing(WatchThroughEntity(id = newId(), mediaId = insert.mediaItem.id, number = 1))
                }
            }
            insert.identifiers.forEach { upsertExternalIdentifier(it) }
        }
        for (update in mediaUpdates) {
            val mediaId = update.mediaItem.id
            check(updateMediaItem(update.mediaItem) != 0) {
                "Import update failed: media item '$mediaId' no longer exists -- it may have been " +
                    "deleted after this import's duplicate-resolution snapshot was read"
            }
            // Self-heals a missing details row rather than failing the import, matching
            // TVWriteDao.updateShowMetadataAtomically and MovieWriteDao.updateMovieMetadataAtomically
            // -- and for the reason those two document: a `media_items` row without its details half
            // is a data-integrity edge MediaWithDetails' nullable `details` says is possible, on all
            // three variants. Zero rows affected here therefore does NOT imply the delete race
            // updateMediaItem's check above guards against; the item itself was just confirmed
            // present one line up. Before this, a book in that state would have aborted the entire
            // import, which is the opposite of the repair every other write path performs.
            when (val details = update.details) {
                is ImportDetails.Book -> {
                    if (updateBookDetails(details.details) == 0) insertBookDetails(details.details)
                    writeReadOne(details)
                }
                is ImportDetails.Movie ->
                    if (updateMovieDetails(details.details) == 0) insertMovieDetails(details.details)
                is ImportDetails.TVShow ->
                    if (updateTvDetails(details.details) == 0) insertTvDetails(details.details)
            }
            if (update.replaceIdentifiers) {
                deleteExternalIdentifiersForMedia(mediaId)
            }
            update.identifiers.forEach { upsertExternalIdentifier(it) }
        }
        // Reads from read_throughs_export.csv (#230): after the books they belong to, before the sessions
        // that point at them.
        readUpserts.forEach { upsertRead(it) }
        for (session in sessionInserts) {
            insertSession(withRead(session))
        }
        for (session in sessionUpdates) {
            check(updateSession(withRead(session)) != 0) {
                "Import update failed: reading session '${session.id}' no longer exists -- it may " +
                    "have been deleted after this import's duplicate-resolution snapshot was read"
            }
        }
        for (episode in episodeInserts) {
            insertEpisode(episode)
        }
        for (episode in episodeUpdates) {
            check(updateEpisode(episode) != 0) {
                "Import update failed: episode '${episode.id}' no longer exists -- it may have " +
                    "been deleted after this import's duplicate-resolution snapshot was read"
            }
        }
        // Viewings (#90), last, so they see the episode rows this import just wrote. REPLACE drops an
        // episode's stored viewings before the file's go in; then every episode this import touched
        // is normalised so `watchedAt` is its latest viewing. An episode whose `watchedAt` has no
        // viewing at that instant (the file marked it watched but gave no matching viewing) first
        // gets that viewing, so the recompute can never move its date backwards.
        viewingDeletesForEpisodes.forEach { deleteViewingsForEpisode(it) }
        filmViewingDeletes.forEach { deleteFilmViewings(it) }
        // An episode viewing joins its show's latest run when the plan left it unassigned (#231).
        viewingInserts.forEach {
            insertViewing(
                if (it.episodeId != null && it.watchThroughId == null) {
                    it.copy(watchThroughId = latestRunId(it.mediaId))
                } else {
                    it
                },
            )
        }
        for (episodeId in touchedEpisodeIds) {
            val episode = episodeById(episodeId) ?: continue
            val at = episode.watchedAt
            if (at != null && viewingCountAt(episodeId, at) == 0) {
                insertViewing(
                    WatchLogEntity(
                        id = newId(),
                        mediaId = episode.mediaId,
                        episodeId = episodeId,
                        watchedAt = at,
                        watchThroughId = latestRunId(episode.mediaId),
                    ),
                )
            }
            recomputeEpisodeWatchedAt(episodeId)
        }
        // Films (#192), the same rule: a Watched film's `watchedAt` gets a viewing if the file gave it
        // none at that instant, so the recompute can never move its date backwards.
        for (filmId in touchedFilmIds) {
            val film = movieDetailsById(filmId) ?: continue
            val at = film.watchedAt
            if (film.status == WatchStatus.WATCHED && at != null && filmViewingCountAt(filmId, at) == 0) {
                insertViewing(WatchLogEntity(id = newId(), mediaId = filmId, episodeId = null, watchedAt = at))
            }
            recomputeFilmWatchedAt(filmId)
        }
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertViewing(log: WatchLogEntity)

    /** See [TVWriteDao.insertFirstRunIfMissing]. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insertFirstRunIfMissing(run: WatchThroughEntity): Long

    @Query("SELECT id FROM watch_throughs WHERE mediaId = :mediaId ORDER BY number DESC LIMIT 1")
    public suspend fun latestRunIdOrNull(mediaId: String): String?

    /** See [TVWriteDao.latestRunId]. */
    public suspend fun latestRunId(mediaId: String): String =
        latestRunIdOrNull(mediaId) ?: run {
            insertFirstRunIfMissing(WatchThroughEntity(id = newId(), mediaId = mediaId, number = 1))
            checkNotNull(latestRunIdOrNull(mediaId))
        }

    @Query("DELETE FROM watch_logs WHERE episodeId = :episodeId")
    public suspend fun deleteViewingsForEpisode(episodeId: String): Int

    @Query("DELETE FROM watch_logs WHERE mediaId = :mediaId AND episodeId IS NULL")
    public suspend fun deleteFilmViewings(mediaId: String): Int

    @Query(
        "SELECT COUNT(*) FROM watch_logs WHERE mediaId = :mediaId AND episodeId IS NULL AND watchedAt = :watchedAt",
    )
    public suspend fun filmViewingCountAt(
        mediaId: String,
        watchedAt: Instant,
    ): Int

    @Query("SELECT * FROM movie_details WHERE mediaId = :mediaId")
    public suspend fun movieDetailsById(mediaId: String): MovieDetailsEntity?

    /** The same rule as [MovieWriteDao.recomputeFilmWatchedAt]. */
    @Query(
        "UPDATE movie_details SET watchedAt = CASE WHEN status = 'WATCHED' THEN " +
            "(SELECT MAX(watchedAt) FROM watch_logs WHERE mediaId = :mediaId AND episodeId IS NULL) " +
            "ELSE NULL END WHERE mediaId = :mediaId",
    )
    public suspend fun recomputeFilmWatchedAt(mediaId: String): Int

    @Query("SELECT COUNT(*) FROM watch_logs WHERE episodeId = :episodeId AND watchedAt = :watchedAt")
    public suspend fun viewingCountAt(
        episodeId: String,
        watchedAt: Instant,
    ): Int

    @Query("SELECT * FROM episodes WHERE id = :episodeId")
    public suspend fun episodeById(episodeId: String): EpisodeEntity?

    /** The same rule as [TVWriteDao.recomputeWatchedAt]: `watchedAt` is the latest viewing. */
    @Query(
        "UPDATE episodes SET watchedAt = (SELECT MAX(watchedAt) FROM watch_logs WHERE episodeId = :episodeId) " +
            "WHERE id = :episodeId",
    )
    public suspend fun recomputeEpisodeWatchedAt(episodeId: String): Int
}
