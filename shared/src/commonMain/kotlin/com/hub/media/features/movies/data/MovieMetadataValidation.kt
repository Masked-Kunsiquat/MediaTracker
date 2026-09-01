package com.hub.media.features.movies.data

import com.hub.media.features.media.domain.MediaMetadataValidation

/**
 * Pure movie-metadata validation (ROADMAP Task 13 Phase B). Same contract as
 * [MediaMetadataValidation]: each function returns a human-readable rejection message, or `null`
 * when the value is valid, and callers short-circuit on the first non-null.
 *
 * ### What is delegated, and the one thing that is not
 * [validateTitle], [validatePurchasePrice] and [validateReleaseYear] all forward to
 * [MediaMetadataValidation]. The first two are media-agnostic outright; the third delegates only
 * its *shape* and message, passing this object's own [MIN_RELEASE_YEAR] in as the bound -- the
 * floor is the one genuinely movie-specific thing here, and it stays below with its reasoning.
 *
 * The price check in particular encodes a non-obvious lesson — rejecting
 * non-finite values, because `NaN < 0.0` is `false` under IEEE 754 and `"Infinity"` typed into a
 * form parses cleanly. Forking that would mean re-deriving it, and getting it subtly wrong here is
 * exactly how the two would drift.
 *
 * Until #81 these forwarded to `BookMetadataValidation` instead, and this KDoc carried an apology
 * for it: reaching into a book-named object from movie code was odd, and the honest fix was to
 * give the media-agnostic rules a media-neutral home. That is now done, and this object is a peer
 * of the book one rather than its debtor.
 */
public object MovieMetadataValidation {
    /** A title must not be blank. Media-agnostic — see this object's KDoc. */
    public fun validateTitle(title: String): String? = MediaMetadataValidation.validateTitle(title)

    /** A known price must be finite and `>= 0`. Media-agnostic — see this object's KDoc. */
    public fun validatePurchasePrice(purchasePrice: Double?): String? =
        MediaMetadataValidation.validatePurchasePrice(purchasePrice)

    /**
     * A known community rating must be finite and within `0.0..10.0`; `null` ("unknown") always
     * passes. Media-agnostic -- see this object's KDoc, and
     * [MediaMetadataValidation.validateCommunityRating] for why one shared column gets one shared
     * rule.
     */
    public fun validateCommunityRating(communityRating: Double?): String? =
        MediaMetadataValidation.validateCommunityRating(communityRating)

    /** A known runtime must be `> 0`; `null` ("unknown") always passes, and `0` is never valid. */
    public fun validateRuntimeMinutes(runtimeMinutes: Int?): String? =
        if (runtimeMinutes != null && runtimeMinutes <= 0) "Runtime must be a positive number" else null

    /**
     * A known [releaseYear] must fall within [MIN_RELEASE_YEAR]..[MAX_RELEASE_YEAR]; `null`
     * ("unknown") always passes.
     */
    public fun validateReleaseYear(releaseYear: Int?): String? =
        MediaMetadataValidation.validateReleaseYear(releaseYear, MIN_RELEASE_YEAR, MAX_RELEASE_YEAR)

    /**
     * Lower bound for a film's release year. Deliberately **not** the book bound of 1450: cinema
     * does not predate the late 1880s, so reusing the printing-press floor would accept a film
     * released four centuries before film existed. 1888 is the year of the oldest surviving motion
     * picture, chosen as a real historical floor rather than a round number.
     */
    public const val MIN_RELEASE_YEAR: Int = 1888

    /**
     * Upper bound. Shared with every other media type and documented on
     * [MediaMetadataValidation.MAX_RELEASE_YEAR]; re-exported here so a caller validating a film
     * reads one pair of bounds from one place.
     */
    public const val MAX_RELEASE_YEAR: Int = MediaMetadataValidation.MAX_RELEASE_YEAR
}
