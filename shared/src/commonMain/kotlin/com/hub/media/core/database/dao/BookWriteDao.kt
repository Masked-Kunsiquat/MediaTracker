package com.hub.media.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import kotlin.time.Instant

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
     * Targeted single-row update of [MediaItemEntity]'s user-editable metadata columns only
     * (title/releaseYear/purchasePrice) -- see [updateBookMetadataAtomically]'s KDoc for why this
     * is a targeted `UPDATE` rather than a full-row read-modify-write.
     *
     * @return The number of rows affected: `1` if [mediaId] exists, `0` otherwise. This is the
     *   authoritative, in-transaction "does this book exist" signal -- not a boolean the caller
     *   computed from a read taken before the transaction opened.
     */
    @Query(
        "UPDATE media_items SET title = :title, releaseYear = :releaseYear, " +
            "purchasePrice = :purchasePrice WHERE id = :mediaId",
    )
    suspend fun updateMediaItemMetadata(
        mediaId: String,
        title: String,
        releaseYear: Int?,
        purchasePrice: Double?,
    ): Int

    /**
     * Targeted single-row update of [BookDetailsEntity]'s user-editable metadata columns only
     * (format/totalPages/status/finishedAt/trackingMode) -- see [updateBookMetadataAtomically]'s
     * KDoc. Leaves [BookDetailsEntity.isbn] (and the row's identity) completely untouched.
     *
     * @return The number of rows affected: `1` if a [BookDetailsEntity] row exists for [mediaId],
     *   `0` otherwise -- the in-transaction signal [updateBookMetadataAtomically] uses to decide
     *   whether to self-heal by inserting a fresh row.
     */
    @Query(
        "UPDATE book_details SET format = :format, totalPages = :totalPages, " +
            "status = :status, finishedAt = :finishedAt, trackingMode = :trackingMode WHERE mediaId = :mediaId",
    )
    suspend fun updateBookDetailsMetadata(
        mediaId: String,
        format: BookFormat,
        totalPages: Int?,
        status: ReadingStatus,
        finishedAt: Instant?,
        trackingMode: TrackingMode,
    ): Int

    /**
     * Atomically updates a book's universal ([MediaItemEntity]) and book-specific
     * ([BookDetailsEntity]) metadata in a single database transaction
     * ([com.hub.media.features.books.data.BookRepository.updateBookMetadata], ROADMAP Task 6
     * Phase A). Follows the same `@Transaction` default-body pattern as [insertBookAtomically]:
     * both writes run on the same underlying connection, so a failure of either rolls back both.
     *
     * Uses targeted `UPDATE ... SET <only the requested columns>` statements
     * ([updateMediaItemMetadata], [updateBookDetailsMetadata]) rather than reading full rows,
     * `.copy()`-ing them, and writing them back whole: a full-row write is only as fresh as the
     * read that produced it, so any field touched by a concurrent writer between that read and
     * this transaction (e.g. [com.hub.media.features.books.data.BookRepository.updateCoverImageHash]
     * changing `coverImageHash` in between) would be silently reverted. Targeted columns can never
     * clobber a field this method was never asked to change.
     *
     * This also removes the need for a caller-supplied "does the book_details row already exist"
     * flag: [updateBookDetailsMetadata]'s own affected-row count, checked *inside* this
     * transaction, is the current, race-free answer -- unlike a boolean computed from a read taken
     * before the transaction opened, which could already be stale by the time this method runs
     * (concurrent insert/delete of that row). Zero rows affected means no [BookDetailsEntity] row
     * exists yet, so this self-heals by inserting a fresh one with the given format/totalPages/
     * status/finishedAt/trackingMode and a `null` isbn (see
     * [com.hub.media.features.books.data.BookRepository.updateBookMetadata] KDoc for the full
     * data-integrity edge case this covers).
     *
     * @return The number of [MediaItemEntity] rows affected by [updateMediaItemMetadata]: `1` on
     *   success, `0` if [mediaId] does not resolve to an existing [MediaItemEntity] (in which case
     *   [updateBookDetailsMetadata]/the self-heal insert are never attempted).
     */
    @Transaction
    suspend fun updateBookMetadataAtomically(
        mediaId: String,
        title: String,
        releaseYear: Int?,
        purchasePrice: Double?,
        format: BookFormat,
        totalPages: Int?,
        status: ReadingStatus,
        finishedAt: Instant?,
        trackingMode: TrackingMode,
    ): Int {
        val mediaRowsAffected = updateMediaItemMetadata(mediaId, title, releaseYear, purchasePrice)
        if (mediaRowsAffected == 0) return 0

        val detailsRowsAffected =
            updateBookDetailsMetadata(mediaId, format, totalPages, status, finishedAt, trackingMode)
        if (detailsRowsAffected == 0) {
            insertBookDetails(
                BookDetailsEntity(
                    mediaId = mediaId,
                    isbn = null,
                    format = format,
                    totalPages = totalPages,
                    status = status,
                    finishedAt = finishedAt,
                    trackingMode = trackingMode,
                ),
            )
        }
        return mediaRowsAffected
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
