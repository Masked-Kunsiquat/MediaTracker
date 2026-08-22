package com.hub.media.features.movies.data

import com.hub.media.features.books.domain.BookMetadataValidation

/**
 * Pure movie-metadata validation (ROADMAP Task 13 Phase B). Same contract as
 * [BookMetadataValidation]: each function returns a human-readable rejection message, or `null`
 * when the value is valid, and callers short-circuit on the first non-null.
 *
 * ### Two rules are delegated rather than copied
 * [validateTitle] and [validatePurchasePrice] forward to [BookMetadataValidation]. Those rules are
 * media-agnostic despite that object's name, and its price check in particular encodes a
 * non-obvious lesson — rejecting non-finite values, because `NaN < 0.0` is `false` under IEEE 754
 * and `"Infinity"` typed into a form parses cleanly. Forking that would mean re-deriving it, and
 * getting it subtly wrong here is exactly how the two would drift.
 *
 * Reaching into a book-named object from movie code is admittedly odd, and the honest fix is to
 * rename it to something media-neutral. That is deliberately not done here: it touches
 * `ImportDataUseCase` and `BookRepository`, neither of which this phase is otherwise changing.
 */
public object MovieMetadataValidation {
    /** A title must not be blank. Media-agnostic — see this object's KDoc. */
    public fun validateTitle(title: String): String? = BookMetadataValidation.validateTitle(title)

    /** A known price must be finite and `>= 0`. Media-agnostic — see this object's KDoc. */
    public fun validatePurchasePrice(purchasePrice: Double?): String? =
        BookMetadataValidation.validatePurchasePrice(purchasePrice)

    /** A known runtime must be `> 0`; `null` ("unknown") always passes, and `0` is never valid. */
    public fun validateRuntimeMinutes(runtimeMinutes: Int?): String? =
        if (runtimeMinutes != null && runtimeMinutes <= 0) "Runtime must be a positive number" else null

    /**
     * A known [releaseYear] must fall within [MIN_RELEASE_YEAR]..[MAX_RELEASE_YEAR]; `null`
     * ("unknown") always passes.
     */
    public fun validateReleaseYear(releaseYear: Int?): String? =
        if (releaseYear != null && releaseYear !in MIN_RELEASE_YEAR..MAX_RELEASE_YEAR) {
            "Release year must be between $MIN_RELEASE_YEAR and $MAX_RELEASE_YEAR"
        } else {
            null
        }

    /**
     * Lower bound for a film's release year. Deliberately **not** the book bound of 1450: cinema
     * does not predate the late 1880s, so reusing the printing-press floor would accept a film
     * released four centuries before film existed. 1888 is the year of the oldest surviving motion
     * picture, chosen as a real historical floor rather than a round number.
     */
    public const val MIN_RELEASE_YEAR: Int = 1888

    /**
     * Upper bound, matching the book rule's reasoning: a static far-future year keeps the bound
     * deterministic for tests rather than deriving "current year + N" from a clock, while still
     * covering announced future releases for decades.
     */
    public const val MAX_RELEASE_YEAR: Int = 2100
}
