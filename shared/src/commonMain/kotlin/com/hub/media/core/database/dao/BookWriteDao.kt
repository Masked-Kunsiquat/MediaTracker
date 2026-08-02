package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Transaction
import androidx.room.Update
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.MediaItemEntity

/**
 * Write-side DAO for the composite "add a book" operation.
 *
 * Every insert uses [OnConflictStrategy.ABORT] so that any constraint violation
 * (duplicate primary key, duplicate (mediaId, provider) composite key, FK failure)
 * throws instead of being silently replaced — which is what lets [insertBookAtomically]
 * roll back the whole operation.
 */
@Dao
interface BookWriteDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMediaItem(item: MediaItemEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBookDetails(details: BookDetailsEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExternalIdentifier(identifier: ExternalIdentifierEntity)

    @Update
    suspend fun updateMediaItem(item: MediaItemEntity)

    @Update
    suspend fun updateBookDetails(details: BookDetailsEntity)

    /**
     * Atomically updates a book's universal ([MediaItemEntity]) and book-specific
     * ([BookDetailsEntity]) metadata in a single database transaction
     * ([com.hub.media.features.books.data.BookRepository.updateBookMetadata], ROADMAP Task 6
     * Phase A). Follows the same `@Transaction` default-body pattern as [insertBookAtomically]:
     * both writes run on the same underlying connection, so a failure of either rolls back both.
     *
     * @param hasExistingBookDetails Whether [bookDetails]'s row already exists. [Update] silently
     *   no-ops (affects zero rows) rather than throwing when the primary key doesn't match an
     *   existing row, so the caller must tell this method whether to UPDATE or INSERT — this
     *   covers the data-integrity edge case where a [MediaItemEntity] has no [BookDetailsEntity]
     *   row yet (never expected via [insertBookAtomically], but see
     *   [com.hub.media.features.books.data.BookRepository.updateBookMetadata] KDoc for how a save
     *   in that state self-heals by inserting the missing row instead of silently discarding the
     *   format/totalPages input).
     */
    @Transaction
    suspend fun updateBookMetadataAtomically(
        mediaItem: MediaItemEntity,
        bookDetails: BookDetailsEntity,
        hasExistingBookDetails: Boolean,
    ) {
        updateMediaItem(mediaItem)
        if (hasExistingBookDetails) {
            updateBookDetails(bookDetails)
        } else {
            insertBookDetails(bookDetails)
        }
    }

    /**
     * Atomically inserts a media item, its book details, and any external identifiers in a
     * single database transaction. Room wraps this default-bodied method in a transaction
     * because of [Transaction]; if any insert throws (e.g. a duplicate (mediaId, provider)
     * composite key with the ABORT strategy), the entire transaction is rolled back and no
     * partial rows remain (AGENTS.md §1: user data safety over shortcuts).
     */
    @Transaction
    suspend fun insertBookAtomically(
        mediaItem: MediaItemEntity,
        bookDetails: BookDetailsEntity,
        externalIdentifiers: List<ExternalIdentifierEntity>,
    ) {
        insertMediaItem(mediaItem)
        insertBookDetails(bookDetails)
        externalIdentifiers.forEach { insertExternalIdentifier(it) }
    }
}
