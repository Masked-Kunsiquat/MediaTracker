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
    /**
     * How this book's reading progress is measured (schema v4, ROADMAP Task 7 Phase A). See
     * [TrackingMode]'s KDoc for the full rationale for making this an explicit, user-controlled
     * field rather than continuing to infer it from [totalPages] being non-null.
     *
     * Pre-existing rows (added before this column existed) are backfilled by `MIGRATION_3_4`
     * ([com.hub.media.core.database.MIGRATION_3_4]) to **exactly match the behavior the app already
     * exhibited** pre-v4: [TrackingMode.PAGES] for a row whose [totalPages] is non-null,
     * [TrackingMode.PERCENT] otherwise — so no existing book's tracking mode changes as an observable
     * side effect of upgrading, only its representation (explicit column instead of inferred). Freshly-
     * ingested books ([com.hub.media.features.books.data.BookRepository.addBook] /
     * [com.hub.media.features.books.domain.AddBookByIsbnUseCase]) apply the identical rule at
     * insert time: a known page count defaults to [TrackingMode.PAGES], otherwise
     * [TrackingMode.PERCENT] — see [com.hub.media.features.books.data.BookRepository.addBook]'s
     * KDoc.
     */
    val trackingMode: TrackingMode = TrackingMode.PAGES,
    /**
     * Author display names (schema v5, ROADMAP Task 9 Phase A), denormalized into a single
     * delimited [String] rather than a separate authors table — a deliberate user decision, not an
     * oversight, kept even though [com.hub.media.features.books.network.BookMetadata.authors]
     * arrives as a `List<String>` and Goodreads carries three separate author columns.
     *
     * ### Why denormalized, not a table
     * A normalized `authors` table's headline benefit is author-level dedup/identity ("show me
     * every book by this author"), but that benefit isn't free: it requires *matching* author
     * identity across sources that don't agree on how a name is written. Open Library and Google
     * Books emit "J.R.R. Tolkien"; Goodreads' `Author l-f` column emits "Tolkien, J. R. R." for the
     * same person; Goodreads also splits a book's authorship across three columns (`Author`,
     * `Author l-f`, `Additional Authors`) with no stable id tying any of them to a canonical
     * author row. A table would still have to fuzzy-match these strings to decide "is this the
     * same author" — it would not eliminate the string-matching problem, only move it from display
     * time to insert time, while adding join-table plumbing (schema + migration + DAO methods) that
     * has no consumer yet (nothing in this app today does "tap an author -> see all their books").
     *
     * Starting denormalized is also the *reversible* choice: a future authors table can always be
     * *derived* from the strings already stored here (parse, dedupe, backfill a join table) once
     * "tap an author -> all their books" becomes a real feature (see ROADMAP Task 9's follow-up
     * note) — but data that was never captured in the first place (the alternative, if this column
     * didn't exist) is gone forever and cannot be un-lost by a later migration. AGENTS.md §1's "user
     * data safety... override[s] development shortcuts" favors capturing the information now in the
     * simplest correct form over waiting for the "proper" normalized shape.
     *
     * ### Encoding: multiple authors joined by [AUTHOR_SEPARATOR] (`"; "`), not `,`
     * A comma is exactly the wrong choice here: Goodreads' own `Author l-f` column already writes a
     * single author as `"Tolkien, J. R. R."` — a comma *inside one name* — so joining multiple
     * authors with `,` would make `"Tolkien, J. R. R., Rowling, J. K."` structurally ambiguous
     * (is that two authors, or one four-part name?) with no way to recover the original split. A
     * semicolon essentially never appears inside a personal name in any of the sources this app
     * reads from (Open Library, Google Books, Goodreads), and `"; "` (semicolon + space) is the
     * conventional separator bibliographies already use for multi-author name lists, so the stored
     * string is also directly human-readable without any parsing (e.g. in [LibraryScreen]/
     * `BookDetailScreen`'s display, or a spreadsheet opening `library_export.csv`).
     *
     * `null` means "unknown" (no author on record), distinct from an empty string — the same
     * null-vs-blank convention every other optional `String` column on this entity ([isbn]) already
     * uses. Existing rows (added before this column existed) are backfilled to `null` by
     * `MIGRATION_4_5` ([com.hub.media.core.database.MIGRATION_4_5]) — never fabricated, since no
     * pre-v5 signal records who wrote a book. Freshly-ingested books
     * ([com.hub.media.features.books.data.BookRepository.addBook] via
     * [com.hub.media.features.books.domain.AddBookByIsbnUseCase]) populate this from
     * [com.hub.media.features.books.network.BookMetadata.authors], which both supported providers
     * already resolve (Open Library makes an extra `/authors/{key}` round-trip specifically to
     * resolve display names) — this column is what finally keeps that data instead of discarding it.
     */
    val authors: String? = null,
) {
    public companion object {
        /**
         * Separator joining multiple [authors] into one stored/displayed string. See [authors]'
         * KDoc for why `"; "` was chosen over a bare `,` (which collides with Goodreads'
         * `"Last, First"` name format) or any character with a real chance of appearing inside a
         * personal name.
         */
        public const val AUTHOR_SEPARATOR: String = "; "
    }
}

/**
 * Joins [authors] (in the order provided — callers pass their preferred author ordering, e.g. a
 * provider's own listed order) into [BookDetailsEntity.authors]' stored form using
 * [BookDetailsEntity.AUTHOR_SEPARATOR]. Blank entries are dropped (a provider or CSV column
 * occasionally emits an empty name slot); the result is `null` — never an empty string — when no
 * non-blank author remains, matching [BookDetailsEntity.authors]' null-means-unknown convention.
 */
public fun joinAuthors(authors: List<String>): String? =
    authors
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(BookDetailsEntity.AUTHOR_SEPARATOR)
        .ifEmpty { null }
