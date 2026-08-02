package com.hub.media.core.database.entities

/**
 * The physical/digital format a book was acquired or consumed in.
 *
 * [BookDetailsEntity.format] is persisted as this enum's `name` (see
 * [com.hub.media.core.database.converters.Converters.bookFormatToName]), stored in a `TEXT`
 * column — Room's exported schema records only the column's type/nullability, never an enum's
 * set of allowed values, so adding a constant here (as [PAPERBACK]/[HARDCOVER] were, ROADMAP
 * Task 6 Phase A) never changes the schema hash and never requires a migration or version bump.
 * Only *removing* or *renaming* an existing constant would break a name-based lookup against
 * already-stored rows.
 */
enum class BookFormat {
    /**
     * Generic/legacy physical value. ISBN-sourced metadata (Open Library, Google Books) rarely
     * distinguishes binding, so [com.hub.media.features.books.domain.AddBookByIsbnUseCase]-driven
     * ingestion defaults every physical acquisition to this value regardless of actual binding.
     * Existing rows inserted before [PAPERBACK]/[HARDCOVER] existed keep this value; a user can
     * upgrade a specific book to the more precise [PAPERBACK]/[HARDCOVER] via the edit-metadata
     * flow (ROADMAP Task 6 Phase A) once they know which one it physically is.
     */
    PHYSICAL,
    EBOOK,
    AUDIOBOOK,

    /** A more precise physical binding than the generic [PHYSICAL] — user-set only, see above. */
    PAPERBACK,

    /** A more precise physical binding than the generic [PHYSICAL] — user-set only, see above. */
    HARDCOVER,
}
