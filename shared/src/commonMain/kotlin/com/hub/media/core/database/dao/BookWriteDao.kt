package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Transaction
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
