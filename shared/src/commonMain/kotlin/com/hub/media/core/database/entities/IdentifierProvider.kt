package com.hub.media.core.database.entities

/**
 * The external catalog/API a [ExternalIdentifierEntity.externalId] is scoped to.
 * See AGENTS.md §4 for the API sources these correspond to.
 *
 * **Adding a value needs no migration.** `external_identifiers.provider` is a TEXT column (frozen
 * schema v5), so a new constant is stored as its own name — the same reasoning that let
 * [BookFormat] gain `PAPERBACK`/`HARDCOVER` without one (ROADMAP Task 6 Phase A). Removing or
 * renaming a value is a different matter and *would* need a data migration, since rows already
 * written carry the old name.
 */
enum class IdentifierProvider {
    ISBN,

    /**
     * An Open Library **edition** key, e.g. `/books/OL33891995M` — one specific printing, the
     * thing an ISBN identifies.
     *
     * The bare name is a historical wart: it predates the app knowing that Open Library models
     * works and editions separately, and it reads as though it meant "the Open Library id" in
     * general. It is left alone deliberately — renaming it would mean migrating every row already
     * written, for no functional gain — and [OPEN_LIBRARY_WORK] is named unambiguously so the
     * contrast makes the distinction obvious at the call site.
     */
    OPEN_LIBRARY,

    /**
     * An Open Library **work** key, e.g. `/works/OL27482W` — the abstract book, shared by every
     * printing of it.
     *
     * Captured even though nothing reads it yet, which is the whole point. Open Library hangs
     * authorship, subjects and description off the work rather than the edition, so a work key is
     * the join needed by several already-planned features: re-reads spanning two printings
     * (ROADMAP Task 10), genre tagging that shouldn't be redone per printing (Task 12), "tap an
     * author -> all their books" (Task 9 Phase A's deferred follow-up), and import dedup when a
     * CSV lists a different printing than the one owned. None of those need a `works` table today;
     * all of them need this string to have been recorded at ingestion time. A book added without
     * it can only be repaired by another rate-limited crawl over the whole library, which is
     * exactly the hole Task 14 had to dig the covers and authors out of.
     */
    OPEN_LIBRARY_WORK,

    GOOGLE_BOOKS,
    TMDB,
    TVDB,
}
