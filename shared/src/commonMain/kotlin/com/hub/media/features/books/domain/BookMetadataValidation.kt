package com.hub.media.features.books.domain

import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.media.domain.MediaMetadataValidation

/**
 * Pure book-metadata validation rules, extracted from [BookRepository.updateBookMetadata]
 * (ROADMAP Task 8 Phase B) so the CSV importer (`ImportDataUseCase`) can apply the exact same
 * title/price/pages/year bounds a manual edit already enforces, rather than forking a second,
 * divergent copy of these checks -- AGENTS.md §7's "reuse, don't fork" requirement for this
 * phase's row validation.
 *
 * Every function returns a human-readable rejection message, or `null` when the value is valid --
 * callers (both [BookRepository.updateBookMetadata] and the importer) short-circuit on the first
 * non-null message. Message text is unchanged from the inline checks this replaces, so existing
 * callers observe identical [com.hub.media.core.util.Resource.Error] text.
 *
 * ### Two rules are delegated rather than owned (#81)
 * [validateTitle] and [validatePurchasePrice] forward to [MediaMetadataValidation]. They used to
 * *live* here and be reached from movie and TV code, which was backwards: those rules never had
 * anything to do with books, and both the movie and TV objects carried a written apology for
 * importing a book-named object to get at them. This object is now a peer of
 * [com.hub.media.features.movies.data.MovieMetadataValidation] and
 * [com.hub.media.features.tv.data.TVMetadataValidation] rather than their supplier: all three
 * delegate the media-agnostic rules and own only what is genuinely theirs. For books that is total
 * pages and the release-year floor.
 */
public object BookMetadataValidation {
    /** A title must not be blank. Media-agnostic -- delegated, see this object's KDoc. */
    public fun validateTitle(title: String): String? = MediaMetadataValidation.validateTitle(title)

    /** A known price must be finite and `>= 0`. Media-agnostic -- delegated, see this object's KDoc. */
    public fun validatePurchasePrice(purchasePrice: Double?): String? =
        MediaMetadataValidation.validatePurchasePrice(purchasePrice)

    /** A known [totalPages] must be `> 0`; `null` ("unknown") always passes. */
    public fun validateTotalPages(totalPages: Int?): String? =
        if (totalPages != null && totalPages <= 0) "Total pages must be a positive number" else null

    /**
     * A known [releaseYear] must fall within [MIN_RELEASE_YEAR]..[MAX_RELEASE_YEAR]; `null`
     * ("unknown") always passes.
     */
    public fun validateReleaseYear(releaseYear: Int?): String? =
        MediaMetadataValidation.validateReleaseYear(releaseYear, MIN_RELEASE_YEAR, MAX_RELEASE_YEAR)

    /**
     * Lower bound for a book's release year: the Gutenberg Bible (~1455) is the conventional start
     * of the printed-book era, so nothing this app tracks should legitimately predate it by much;
     * chosen as a round, generous floor rather than a precise historical cutoff.
     *
     * Lived on [BookRepository] until #81. It moved here because a *validation* bound belongs with
     * the validation rule that reads it -- the movie and TV floors were already placed that way,
     * and having the book one on a repository meant this domain object had to import the data
     * layer to validate a year.
     */
    public const val MIN_RELEASE_YEAR: Int = 1450

    /**
     * Upper bound for a book's release year. Shared with every other media type and documented on
     * [MediaMetadataValidation.MAX_RELEASE_YEAR]; re-exported here so a caller validating a book
     * reads one pair of bounds from one place.
     */
    public const val MAX_RELEASE_YEAR: Int = MediaMetadataValidation.MAX_RELEASE_YEAR
}
