package com.hub.media.core.database.entities

/**
 * A book's reading lifecycle status (ROADMAP Task 6 Phase C).
 *
 * ### Why this lives on [BookDetailsEntity], not [MediaItemEntity]
 * AGENTS.md §3.2 puts *universal* metadata (id, type, title, release_year, purchase_price,
 * created_at) on `MediaItems` and *domain-specific* metadata on child tables. [ReadingStatus]'s
 * four values are inherently about the act of *reading* a book: [TO_READ] presumes a book sitting
 * unread on a shelf, [DNF] ("did not finish") is a reading-specific abandonment concept with no
 * obvious 1:1 analogue for, say, a half-watched movie (paused vs. abandoned vs. "watched most of
 * it" don't collapse the same way), and [READING] implies the page/percent-position tracking this
 * app already models only for books ([BookDetailsEntity.totalPages], `ReadingSessionEntity`).
 * Movies/TV (ROADMAP Task 8) will almost certainly want *an* analogous "where am I with this item"
 * concept, but not necessarily these exact four states or these exact names — a TV show plausibly
 * wants per-season/per-episode granularity that a flat status enum can't express, and Task 8 is
 * explicitly scoped to design its own `WatchLogs`/watch-state model rather than inherit this one
 * sight unseen. Promoting a *speculative* shared status to `MediaItemEntity` now — before Task 8
 * has even scoped what movies/TV actually need — risks either a lowest-common-denominator enum
 * that fits neither domain well, or a premature schema coupling between books and a media type that
 * doesn't exist in this codebase yet. Keeping [ReadingStatus] book-specific costs nothing today
 * (every current consumer already reaches it through [BookDetailsEntity]) and leaves Task 8 free to
 * either define its own enum or, if its real requirements turn out to genuinely match this one,
 * *deliberately* generalize it later — a decision made with actual movie/TV requirements in hand
 * instead of guessed at here.
 *
 * ### Persistence
 * Stored as this enum's `name` (see [com.hub.media.core.database.converters.Converters]), in a
 * `TEXT NOT NULL` column added by schema v3 / `MIGRATION_2_3`
 * ([com.hub.media.core.database.MIGRATION_2_3]) — see [BookDetailsEntity.status]'s KDoc for the
 * column-level default/derivation rules applied to pre-existing rows during that migration.
 */
public enum class ReadingStatus {
    /** Added to the library but not yet started. The default for newly-ingested books. */
    TO_READ,

    /** Currently being read (a session has been logged, or the user set this explicitly). */
    READING,

    /** Finished. See [BookDetailsEntity.finishedAt] for when. */
    FINISHED,

    /** Did not finish — deliberately abandoned partway through. */
    DNF,
}
