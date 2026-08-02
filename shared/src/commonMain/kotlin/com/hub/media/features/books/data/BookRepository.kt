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
     * Atomically corrects an existing book's metadata (ROADMAP Task 6 Phase A): title,
     * releaseYear, and purchasePrice on [MediaItemEntity], plus totalPages and format on
     * [BookDetailsEntity], updated together in [db.bookWriteDao]'s
     * [com.hub.media.core.database.dao.BookWriteDao.updateBookMetadataAtomically] transaction
     * rather than as two sequential awaits — a failure partway through (e.g. process death) can
     * never leave the two tables individually correct but mutually inconsistent (e.g. a new title
     * with a stale page count).
     *
     * The motivating real-world case: provider (Open Library/Google Books) edition records
     * regularly get physical details wrong — e.g. Open Library reporting 384 pages for an edition
     * that physically has 366 — with no user-facing correction path until now. [isbn] is
     * deliberately NOT a parameter: it is an identity/lookup key tying this row to its external
     * provider record, not user-facing descriptive metadata, so it stays out of this edit surface
     * entirely (ROADMAP Task 6 Phase A scope).
     *
     * ### Validation (checked before any DB access; a violation never touches the database)
     * - [title] must not be blank.
     * - [purchasePrice], if non-null, must be `>= 0`.
     * - [totalPages], if non-null, must be `> 0` (`null` means "page count unknown," which is
     *   valid; `0` or negative is never a valid page count for a real book).
     * - [releaseYear], if non-null, must fall within [MIN_RELEASE_YEAR]..[MAX_RELEASE_YEAR].
     *
     * ### No existing [BookDetailsEntity] row (data-integrity edge case)
     * [observeBookDetail]'s KDoc documents that [BookWithDetails.details] can independently be
     * null even though [addBook] always inserts both rows atomically (a hand-rolled or corrupted
     * row could still produce this). If [mediaId] resolves to a [MediaItemEntity] but has no
     * [BookDetailsEntity] row, this method self-heals: it still updates [MediaItemEntity] as
     * normal, and INSERTs a fresh [BookDetailsEntity] with the given [format]/[totalPages] and a
     * `null` [BookDetailsEntity.isbn] (there is nothing to recover the original ISBN from) rather
     * than silently discarding the format/totalPages input or failing the whole update outright —
     * title/releaseYear/purchasePrice are meaningful and updatable on [MediaItemEntity] alone, and
     * creating the missing row is strictly better than leaving the inconsistency in place.
     *
     * @param mediaId The media id to update.
     * @param title New title.
     * @param releaseYear New release year, or null to clear it.
     * @param purchasePrice New purchase price, or null to clear it.
     * @param totalPages New page count, or null for "unknown."
     * @param format New [BookFormat].
     * @return [Resource.Success] if updated, or [Resource.Error] if [mediaId] does not exist or a
     *   validation rule above is violated (never throws).
     */
    public suspend fun updateBookMetadata(
        mediaId: String,
        title: String,
        releaseYear: Int? = null,
        purchasePrice: Double? = null,
        totalPages: Int? = null,
        format: BookFormat,
    ): Resource<Unit> {
        if (title.isBlank()) {
            return Resource.Error("Title must not be blank")
        }
        if (purchasePrice != null && purchasePrice < 0.0) {
            return Resource.Error("Purchase price must not be negative")
        }
        if (totalPages != null && totalPages <= 0) {
            return Resource.Error("Total pages must be a positive number")
        }
        if (releaseYear != null && releaseYear !in MIN_RELEASE_YEAR..MAX_RELEASE_YEAR) {
            return Resource.Error(
                "Release year must be between $MIN_RELEASE_YEAR and $MAX_RELEASE_YEAR",
            )
        }

        return try {
            val existingMediaItem = db.mediaItemDao().getById(mediaId)
                ?: return Resource.Error("Book with id=$mediaId not found")
            val existingDetails = db.bookDetailsDao().getByMediaId(mediaId)

            val updatedMediaItem = existingMediaItem.copy(
                title = title,
                releaseYear = releaseYear,
                purchasePrice = purchasePrice,
            )
            val updatedDetails = (
                existingDetails ?: BookDetailsEntity(
                    mediaId = mediaId,
                    isbn = null,
                    format = format,
                    totalPages = totalPages,
                )
                ).copy(format = format, totalPages = totalPages)

            db.bookWriteDao().updateBookMetadataAtomically(
                mediaItem = updatedMediaItem,
                bookDetails = updatedDetails,
                hasExistingBookDetails = existingDetails != null,
            )
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(
                message = "Failed to update book metadata: ${e.message ?: "Unknown error"}",
                cause = e,
            )
        }
    }

    public companion object {
        /**
         * Lower bound for [updateBookMetadata]'s [BookRepository.updateBookMetadata] `releaseYear`
         * validation: the Gutenberg Bible (~1455) is the conventional start of the printed-book
         * era, so nothing this app tracks should legitimately predate it by much; chosen as a
         * round, generous floor rather than a precise historical cutoff.
         */
        public const val MIN_RELEASE_YEAR: Int = 1450

        /**
         * Upper bound for `releaseYear` validation: a static far-future year (rather than deriving
         * "current year + N" from a [kotlin.time.Clock]) so the bound is deterministic for tests
         * and callers alike, while still comfortably covering forthcoming/pre-order release years
         * for decades without needing to be revisited.
         */
        public const val MAX_RELEASE_YEAR: Int = 2100
    }
}
