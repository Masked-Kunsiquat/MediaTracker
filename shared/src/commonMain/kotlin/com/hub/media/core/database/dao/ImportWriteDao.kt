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
import com.hub.media.core.database.entities.ReadingSessionEntity
import com.hub.media.core.database.entities.TVDetailsEntity

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
    public data class Book(
        public val details: BookDetailsEntity,
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
    ) {
        for (insert in mediaInserts) {
            insertMediaItem(insert.mediaItem)
            when (val details = insert.details) {
                is ImportDetails.Book -> insertBookDetails(details.details)
                is ImportDetails.Movie -> insertMovieDetails(details.details)
                is ImportDetails.TVShow -> insertTvDetails(details.details)
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
                is ImportDetails.Book ->
                    if (updateBookDetails(details.details) == 0) insertBookDetails(details.details)
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
        for (session in sessionInserts) {
            insertSession(session)
        }
        for (session in sessionUpdates) {
            check(updateSession(session) != 0) {
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
    }
}
