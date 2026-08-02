package com.hub.media.core.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import kotlin.time.Instant

/**
 * Book-specific metadata for a [MediaItemEntity] of type [MediaType.BOOK].
 * [mediaId] is both the primary key (one-to-one with the parent) and the FK, so it is
 * already covered by a unique index — no extra index is required for the cascade delete.
 */
@Entity(
    tableName = "book_details",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class BookDetailsEntity(
    @PrimaryKey val mediaId: String,
    val isbn: String?,
    val format: BookFormat,
    val totalPages: Int?,
    /**
     * Reading lifecycle status (schema v3, ROADMAP Task 6 Phase C). See [ReadingStatus]'s KDoc
     * for why this lives here rather than on [MediaItemEntity].
     *
     * Pre-existing rows (added before this column existed) are backfilled by `MIGRATION_2_3`
     * ([com.hub.media.core.database.MIGRATION_2_3]): [ReadingStatus.READING] for a book that
     * already has at least one `reading_sessions` row (someone has demonstrably started it — the
     * honest default that isn't still [ReadingStatus.TO_READ]), [ReadingStatus.TO_READ] otherwise
     * (the honest "we simply don't know" default for a book nobody has ever logged a session
     * against — see that migration's KDoc for the full justification). Freshly-ingested books
     * ([com.hub.media.features.books.data.BookRepository.addBook] /
     * [com.hub.media.features.books.domain.AddBookByIsbnUseCase]) start at [ReadingStatus.TO_READ]
     * — nobody has read any of a book the moment it's added to the library.
     */
    val status: ReadingStatus = ReadingStatus.TO_READ,
    /**
     * When this book's [status] most recently became [ReadingStatus.FINISHED], or null if it
     * either has never been [ReadingStatus.FINISHED] or was finished before this column existed
     * (schema v3 adds it as a plain nullable column with no backfill — see `MIGRATION_2_3`'s KDoc
     * for why no pre-existing row can legitimately already be [ReadingStatus.FINISHED] at
     * migration time, which is what makes leaving this null-for-everyone at migration safe rather
     * than lossy).
     *
     * Set by [com.hub.media.features.books.data.BookRepository.updateReadingStatus] /
     * [com.hub.media.features.books.data.BookRepository.updateBookMetadata] whenever [status]
     * transitions *into* [ReadingStatus.FINISHED] (to "now"), left untouched if it is saved again
     * while already [ReadingStatus.FINISHED] (so re-editing an already-finished book's format/title
     * doesn't silently bump its finish date), and cleared back to null if [status] moves *away from*
     * [ReadingStatus.FINISHED] (there is no other value that means "finished on this date" once the
     * status itself says otherwise).
     *
     * Exists specifically to let `StatsDao`/`StatsRepository` scope the books-finished count to a
     * period ("this week"/"this month"), the same way session-based stats already are — a bare
     * lifetime [status] count alone cannot answer "how many books finished *this month*" since it
     * carries no timestamp.
     */
    val finishedAt: Instant? = null,
)
