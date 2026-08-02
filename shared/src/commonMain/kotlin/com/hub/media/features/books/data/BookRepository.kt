package com.hub.media.features.books.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.BookDetailsEntity
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.ExternalIdentifierEntity
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Repository for managing book-related data operations. Encapsulates all direct database
 * access and provides a stable API for use cases and ViewModels.
 *
 * Per AGENTS.md §5, all database write failures are wrapped in [Resource.Error] to prevent
 * crashes on offline/constraint-violation states.
 *
 * @param clock Source of "now" for [resolveFinishedAt]'s FINISHED-transition timestamp. Defaults
 *   to [Clock.System] for production use; tests inject a fake [Clock] to assert on a deterministic
 *   finish timestamp instead of a real wall-clock read (same injected-clock pattern as
 *   [com.hub.media.features.stats.data.StatsRepository] / [com.hub.media.ui.StatsViewModel] /
 *   [com.hub.media.features.books.timer.ReadingTimer]). The default keeps every existing
 *   `BookRepository(db)` call site and test source-compatible.
 */
public class BookRepository(private val db: AppDatabase, private val clock: Clock = Clock.System) {

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
     * Observes every book together with its [BookDetailsEntity] as a reactive stream (ROADMAP
     * Task 6 Phase C: library status filtering needs each book's [BookDetailsEntity.status], which
     * [observeAllBooks]'s bare [MediaItemEntity] list can't expose). Joins [observeAllBooks]'s
     * title-ordered list with [com.hub.media.core.database.dao.BookDetailsDao.observeAll] by
     * `mediaId`, preserving [observeAllBooks]'s title order — the join itself never reorders, it
     * only attaches each item's details (or `null`, the same data-integrity edge case
     * [observeBookDetail] documents) alongside it.
     */
    public fun observeAllBooksWithDetails(): Flow<List<BookWithDetails>> =
        combine(
            observeAllBooks(),
            db.bookDetailsDao().observeAll(),
        ) { mediaItems, allDetails ->
            val detailsByMediaId = allDetails.associateBy { it.mediaId }
            mediaItems.map { mediaItem ->
                BookWithDetails(mediaItem = mediaItem, details = detailsByMediaId[mediaItem.id])
            }
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
     * @param status Initial [ReadingStatus] (ROADMAP Task 6 Phase C). Defaults to
     *   [ReadingStatus.TO_READ] — a book that was just added has, by definition, not been started
     *   yet; [com.hub.media.features.books.domain.AddBookByIsbnUseCase] relies on this default
     *   rather than passing it explicitly, since ISBN metadata carries no signal about whether the
     *   user has already started/finished the physical copy they're cataloguing.
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
        status: ReadingStatus = ReadingStatus.TO_READ,
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
            status = status,
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
     * @param status New [ReadingStatus] (ROADMAP Task 6 Phase C). [BookDetailsEntity.finishedAt] is
     *   derived from the transition, not taken as a separate parameter — see [resolveFinishedAt].
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
        status: ReadingStatus,
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
            // Only read for resolveFinishedAt's transition decision (old status/finishedAt) --
            // NOT to build a full-row copy to write back. The actual write below is a targeted
            // UPDATE of just the requested columns, so this read being taken outside the
            // transaction can never cause a concurrent writer's change to some *other* field to be
            // silently reverted (see BookWriteDao.updateBookMetadataAtomically's KDoc).
            val existingDetails = db.bookDetailsDao().getByMediaId(mediaId)
            val finishedAt = resolveFinishedAt(
                newStatus = status,
                oldStatus = existingDetails?.status ?: ReadingStatus.TO_READ,
                oldFinishedAt = existingDetails?.finishedAt,
                clock = clock,
            )

            val mediaRowsAffected = db.bookWriteDao().updateBookMetadataAtomically(
                mediaId = mediaId,
                title = title,
                releaseYear = releaseYear,
                purchasePrice = purchasePrice,
                format = format,
                totalPages = totalPages,
                status = status,
                finishedAt = finishedAt,
            )
            if (mediaRowsAffected == 0) {
                return Resource.Error("Book with id=$mediaId not found")
            }
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(
                message = "Failed to update book metadata: ${e.message ?: "Unknown error"}",
                cause = e,
            )
        }
    }

    /**
     * One-shot (non-reactive) fetch of [BookWithDetails] for [mediaId], for callers that need a
     * single current snapshot rather than [observeBookDetail]'s ongoing [Flow] — namely
     * [com.hub.media.features.books.domain.RefetchCoverUseCase] (ROADMAP Task 6 Phase E), which
     * only needs the book's current ISBN once per invocation, not a live subscription. Null if
     * [mediaId] does not resolve to a [MediaItemEntity] (never created, or deleted) — same
     * null-on-delete semantics as [observeBookDetail].
     */
    public suspend fun getBookWithDetails(mediaId: String): BookWithDetails? {
        val mediaItem = db.mediaItemDao().getById(mediaId) ?: return null
        val details = db.bookDetailsDao().getByMediaId(mediaId)
        return BookWithDetails(mediaItem = mediaItem, details = details)
    }

    /**
     * Updates only [MediaItemEntity.coverImageHash] for [mediaId] (ROADMAP Task 6 Phase E's
     * re-fetch-cover affordance — see [com.hub.media.features.books.domain.RefetchCoverUseCase]).
     * Deliberately narrower than [updateBookMetadata]: every other [MediaItemEntity] field and all
     * of [BookDetailsEntity] are left completely untouched, so a cover refetch can never have a
     * side effect on any user-edited field beyond the cover itself.
     *
     * @param mediaId The book whose cover is being updated.
     * @param coverImageHash The new `<sha256>.jpg` filename (from
     *   [com.hub.media.core.storage.LocalImageStorageManager.saveImage]).
     * @return [Resource.Success] if updated, or [Resource.Error] if [mediaId] does not resolve to
     *   a [MediaItemEntity] (never expected in practice — [RefetchCoverUseCase] only calls this
     *   right after successfully reading the same row via [getBookWithDetails]) or the underlying
     *   DB write throws.
     *
     * Uses [com.hub.media.core.database.dao.MediaItemDao.updateCoverImageHash], a targeted
     * single-column `UPDATE`, rather than reading the row, `.copy()`-ing it, and writing the whole
     * row back: that read-modify-write shape is only as fresh as the read, so any other field
     * changed by a concurrent writer (e.g. [updateBookMetadata] editing title/releaseYear/
     * purchasePrice) in between would be silently reverted. The targeted `UPDATE`'s own
     * affected-row count (`0` vs `1`) is now how "no such book" is detected, since this no longer
     * reads the row first to check.
     */
    public suspend fun updateCoverImageHash(mediaId: String, coverImageHash: String): Resource<Unit> = try {
        val rowsAffected = db.mediaItemDao().updateCoverImageHash(mediaId, coverImageHash)
        if (rowsAffected == 0) {
            Resource.Error("Book with id=$mediaId not found")
        } else {
            Resource.Success(Unit)
        }
    } catch (e: Exception) {
        Resource.Error(
            message = "Failed to update cover image: ${e.message ?: "Unknown error"}",
            cause = e,
        )
    }

    /**
     * Quick, single-field [ReadingStatus] change (ROADMAP Task 6 Phase C) — e.g. a status
     * chip/dropdown on the Book Detail screen — without the full [updateBookMetadata] round-trip
     * (re-entering title/release year/purchase price/total pages/format just to flip one enum).
     * [BookDetailsEntity.finishedAt] is derived exactly like [updateBookMetadata]'s, via the same
     * [resolveFinishedAt] helper, so both entry points can never disagree about when a book was
     * "finished."
     *
     * Unlike [updateBookMetadata], this does not self-heal a missing [BookDetailsEntity] row (the
     * data-integrity edge case documented on [observeBookDetail]) — there is no title/format/pages
     * input here to construct a replacement row from, so a missing row is reported as an error
     * instead of silently fabricating one with placeholder values.
     *
     * @param mediaId The media id whose status is changing.
     * @param status The new [ReadingStatus].
     * @return [Resource.Success] if updated, or [Resource.Error] if [mediaId] has no
     *   [BookDetailsEntity] row (never expected via [addBook]'s atomic insert; see
     *   [observeBookDetail]'s KDoc for how it can arise anyway).
     */
    public suspend fun updateReadingStatus(mediaId: String, status: ReadingStatus): Resource<Unit> = try {
        val existingDetails = db.bookDetailsDao().getByMediaId(mediaId)
            ?: return Resource.Error("No book details found for id=$mediaId")

        val finishedAt = resolveFinishedAt(
            newStatus = status,
            oldStatus = existingDetails.status,
            oldFinishedAt = existingDetails.finishedAt,
            clock = clock,
        )
        db.bookDetailsDao().update(existingDetails.copy(status = status, finishedAt = finishedAt))
        Resource.Success(Unit)
    } catch (e: Exception) {
        Resource.Error(
            message = "Failed to update reading status: ${e.message ?: "Unknown error"}",
            cause = e,
        )
    }

    public companion object {

        /**
         * Derives the [BookDetailsEntity.finishedAt] value for a [ReadingStatus] transition
         * (ROADMAP Task 6 Phase C), shared by [updateBookMetadata] and [updateReadingStatus] so the
         * two entry points can never disagree:
         * - Moving to any status other than [ReadingStatus.FINISHED] clears it to `null` — no other
         *   value means "finished on this date" once the status itself says otherwise.
         * - Staying [ReadingStatus.FINISHED] (already finished, saved again — e.g. editing an
         *   already-finished book's title) preserves the original [oldFinishedAt] verbatim rather
         *   than bumping it to "now," so re-saving unrelated fields never silently rewrites when a
         *   book was actually finished.
         * - Transitioning *into* [ReadingStatus.FINISHED] from anything else stamps [clock]'s
         *   current time as the finish moment. [clock] is the caller's injected [BookRepository.clock]
         *   (production callers use the [Clock.System] default; tests can inject a fake [Clock] for
         *   a deterministic, assertable finish timestamp).
         */
        internal fun resolveFinishedAt(
            newStatus: ReadingStatus,
            oldStatus: ReadingStatus,
            oldFinishedAt: Instant?,
            clock: Clock,
        ): Instant? = when {
            newStatus != ReadingStatus.FINISHED -> null
            oldStatus == ReadingStatus.FINISHED && oldFinishedAt != null -> oldFinishedAt
            else -> clock.now()
        }

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
