package com.hub.media.core.database.entities

/**
 * How a book's reading progress is measured (ROADMAP Task 7 Phase A).
 *
 * ### Why this exists: replacing an inferred signal with an explicit one
 * Before schema v4, nothing on [BookDetailsEntity] recorded this directly — every consumer that
 * needed to distinguish "this book is tracked by page number" from "this book is tracked by
 * percent" (progress formatting, and the manual/pending session dialogs' derived-`deltaPages`
 * behavior added in ROADMAP Task 6 Phase B) inferred it from [BookDetailsEntity.totalPages] being
 * non-null. That inference is invisible to the user (there was never a UI control for it) and
 * silently flips the moment `totalPages` is edited — clearing a known page count on a book with
 * logged page-based sessions would retroactively reinterpret them as percent-based with no signal
 * to the user that anything changed. [trackingMode] on [BookDetailsEntity] makes the choice an
 * explicit, user-controlled fact instead of a side effect of an unrelated field.
 *
 * ### Values
 * - [PAGES]: progress is a physical/absolute page number (`endUnit`/`startUnit` on
 *   `ReadingSessionEntity` are page numbers; `deltaPages` is derivable as `end - start`).
 * - [PERCENT]: progress is a percentage of the whole (no fixed denominator exists to derive a page
 *   delta from, so `deltaPages` must be entered manually when wanted).
 *
 * ### Persistence
 * Stored as this enum's `name` (see [com.hub.media.core.database.converters.Converters]), in a
 * `TEXT NOT NULL DEFAULT 'PAGES'` column added by schema v4 / `MIGRATION_3_4`
 * ([com.hub.media.core.database.MIGRATION_3_4]) — see [BookDetailsEntity.trackingMode]'s KDoc for
 * the column-level default/derivation rules applied to pre-existing rows during that migration,
 * and [com.hub.media.features.books.data.BookRepository.addBook] for the ingestion default applied
 * to freshly-added books.
 */
public enum class TrackingMode {
    /** Progress is tracked as an absolute page number. */
    PAGES,

    /** Progress is tracked as a percentage of the whole (no fixed page count). */
    PERCENT,
}
