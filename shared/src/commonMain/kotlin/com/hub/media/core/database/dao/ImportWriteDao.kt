package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.ReadingSessionEntity

/**
 * A brand-new book to insert as part of a bulk CSV import (ROADMAP Task 8 Phase B): this book's
 * `media_id` did not match anything already in the database, so [mediaItem]/[details] are fresh
 * rows and [identifiers] are inserted as-is.
 */
public data class ImportBookInsert(
    public val mediaItem: MediaItemEntity,
    public val details: BookDetailsEntity,
    public val identifiers: List<ExternalIdentifierEntity>,
)

/**
 * An existing book to update as part of a bulk CSV import. [mediaItem]/[details] are the full
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
public data class ImportBookUpdate(
    public val mediaItem: MediaItemEntity,
    public val details: BookDetailsEntity,
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

    @Update
    public suspend fun updateMediaItem(item: MediaItemEntity)

    @Update
    public suspend fun updateBookDetails(details: BookDetailsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertExternalIdentifier(identifier: ExternalIdentifierEntity)

    @Query("DELETE FROM external_identifiers WHERE mediaId = :mediaId")
    public suspend fun deleteExternalIdentifiersForMedia(mediaId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertSession(session: ReadingSessionEntity)

    @Update
    public suspend fun updateSession(session: ReadingSessionEntity)

    /**
     * Applies every queued book insert/update, then every queued session insert/update, all in one
     * transaction.
     *
     * Books are applied before sessions here purely for readability -- there is no ordering
     * dependency the database enforces between the two loops, because by the time this method
     * runs, [com.hub.media.features.portability.domain.ImportDataUseCase] has already resolved
     * every session's `media_id` against the *pre-write* snapshot of known books (existing DB rows
     * plus this same import's own book rows). The `reading_sessions.mediaId` foreign key would
     * catch an actual ordering bug anyway (it throws, rolling back the whole transaction), but
     * correct resolution logic upstream means it is never expected to fire.
     */
    @Transaction
    public suspend fun importAtomically(
        bookInserts: List<ImportBookInsert>,
        bookUpdates: List<ImportBookUpdate>,
        sessionInserts: List<ReadingSessionEntity>,
        sessionUpdates: List<ReadingSessionEntity>,
    ) {
        for (insert in bookInserts) {
            insertMediaItem(insert.mediaItem)
            insertBookDetails(insert.details)
            insert.identifiers.forEach { upsertExternalIdentifier(it) }
        }
        for (update in bookUpdates) {
            updateMediaItem(update.mediaItem)
            updateBookDetails(update.details)
            if (update.replaceIdentifiers) {
                deleteExternalIdentifiersForMedia(update.mediaItem.id)
            }
            update.identifiers.forEach { upsertExternalIdentifier(it) }
        }
        for (session in sessionInserts) {
            insertSession(session)
        }
        for (session in sessionUpdates) {
            updateSession(session)
        }
    }
}
