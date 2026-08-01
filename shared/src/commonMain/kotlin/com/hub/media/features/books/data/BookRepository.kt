package com.hub.media.features.books.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Repository for managing book-related data operations. Encapsulates all direct database
 * access and provides a stable API for use cases and ViewModels.
 *
 * Per AGENTS.md §5, all database write failures are wrapped in [Resource.Error] to prevent
 * crashes on offline/constraint-violation states.
 */
public class BookRepository(private val db: AppDatabase) {

    /**
     * Observes all books in the database as a reactive stream, ordered by title.
     * No [Resource] wrapper on Flow-based reads per AGENTS.md §5 conventions.
     */
    public fun observeAllBooks(): Flow<List<MediaItemEntity>> =
        db.mediaItemDao().observeByType(MediaType.BOOK)

    /**
     * Observes a single book by ID as a reactive stream via an indexed primary-key query.
     * Emits null if the book does not exist (or after it is deleted).
     */
    public fun observeBook(id: String): Flow<MediaItemEntity?> =
        db.mediaItemDao().observeById(id)

    /**
     * Observes a single book together with its [BookDetailsEntity] as a reactive stream (ROADMAP
     * Task 4 Phase B: `BookDetailViewModel` metadata). Combines [observeBook] with
     * [com.hub.media.core.database.dao.BookDetailsDao.observeByMediaId] rather than a Room
     * `@Relation` query (no DAO changes per the Room schema freeze) — [BookWithDetails] is reused
     * as the wrapper shape purely because it already exists.
     *
     * Emits null once [id]'s [MediaItemEntity] is missing (never created, or deleted), matching
     * [observeBook]'s null-on-delete semantics; [BookWithDetails.details] itself may independently
     * be null if no [BookDetailsEntity] row exists for the media id (data-integrity edge case,
     * never expected via [addBook]'s atomic insert).
     */
    public fun observeBookDetail(id: String): Flow<BookWithDetails?> =
        combine(
            db.mediaItemDao().observeById(id),
            db.bookDetailsDao().observeByMediaId(id),
        ) { mediaItem, details ->
            mediaItem?.let { BookWithDetails(mediaItem = it, details = details) }
        }

    /**
     * Adds a new book with details and optional external identifiers in a single atomic
     * database transaction ([com.hub.media.core.database.dao.BookWriteDao.insertBookAtomically],
     * a `@Transaction` DAO method). If any of the inserts fails — including a duplicate
     * (mediaId, provider) pair among [externalIdentifiers], which aborts under the ABORT
     * conflict strategy — the whole transaction is rolled back: no [MediaItemEntity],
     * [BookDetailsEntity], or [ExternalIdentifierEntity] rows remain, and a [Resource.Error]
     * is returned (never throws).
     *
     * @param title The book title.
     * @param releaseYear Optional publication year.
     * @param purchasePrice Optional price paid.
     * @param format The book format (PHYSICAL, EBOOK, AUDIOBOOK).
     * @param totalPages Optional page count.
     * @param isbn Optional ISBN.
     * @param coverImageHash Optional `<sha256>.jpg` filename (from
     *   [com.hub.media.core.storage.LocalImageStorageManager.saveImage]) for the locally stored
     *   cover image, per AGENTS.md §4.
     * @param externalIdentifiers Optional (provider, externalId) mappings to external catalogs.
     *   A duplicate provider in this list violates the composite primary key and fails the
     *   whole insert atomically.
     * @return [Resource.Success] with the new media ID, or [Resource.Error] on failure.
     */
    public suspend fun addBook(
        title: String,
        releaseYear: Int? = null,
        purchasePrice: Double? = null,
        format: BookFormat,
        totalPages: Int? = null,
        isbn: String? = null,
        coverImageHash: String? = null,
        externalIdentifiers: List<Pair<IdentifierProvider, String>> = emptyList(),
    ): Resource<String> = try {
        val mediaId = newId()
        val now = Clock.System.now()

        val mediaItem = MediaItemEntity(
            id = mediaId,
            type = MediaType.BOOK,
            title = title,
            releaseYear = releaseYear,
            purchasePrice = purchasePrice,
            createdAt = now,
            coverImageHash = coverImageHash,
        )

        val bookDetails = BookDetailsEntity(
            mediaId = mediaId,
            isbn = isbn,
            format = format,
            totalPages = totalPages,
        )

        val identifierEntities = externalIdentifiers.map { (provider, externalId) ->
            ExternalIdentifierEntity(
                mediaId = mediaId,
                provider = provider,
                externalId = externalId,
            )
        }

        db.bookWriteDao().insertBookAtomically(mediaItem, bookDetails, identifierEntities)

        Resource.Success(mediaId)
    } catch (e: Exception) {
        Resource.Error(
            message = "Failed to add book: ${e.message ?: "Unknown error"}",
            cause = e,
        )
    }

    /**
     * Deletes a book and all associated data (cascades via FK constraints).
     *
     * @param id The media ID of the book to delete.
     * @return [Resource.Success] if deleted, or [Resource.Error] on failure.
     */
    public suspend fun deleteBook(id: String): Resource<Unit> = try {
        db.mediaItemDao().deleteById(id)
        Resource.Success(Unit)
    } catch (e: Exception) {
        Resource.Error(
            message = "Failed to delete book: ${e.message ?: "Unknown error"}",
            cause = e,
        )
    }

    /**
     * Updates an existing book's metadata.
     *
     * @param id The media ID to update.
     * @param title New title.
     * @param releaseYear New release year.
     * @param purchasePrice New purchase price.
     * @return [Resource.Success] if updated, or [Resource.Error] on failure.
     */
    public suspend fun updateBook(
        id: String,
        title: String,
        releaseYear: Int? = null,
        purchasePrice: Double? = null,
    ): Resource<Unit> = try {
        val existing = db.mediaItemDao().getById(id)
            ?: return Resource.Error("Book with id=$id not found")

        val updated = existing.copy(
            title = title,
            releaseYear = releaseYear,
            purchasePrice = purchasePrice,
        )
        db.mediaItemDao().update(updated)
        Resource.Success(Unit)
    } catch (e: Exception) {
        Resource.Error(
            message = "Failed to update book: ${e.message ?: "Unknown error"}",
            cause = e,
        )
    }
}
