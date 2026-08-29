package com.hub.media.features.tv.data

import com.hub.media.features.media.domain.MediaMetadataValidation

/**
 * Pure TV-metadata validation (ROADMAP Task 13 Phase C). Same contract as
 * [com.hub.media.features.movies.data.MovieMetadataValidation]: each function returns a
 * human-readable rejection message, or `null` when the value is valid, and callers short-circuit
 * on the first non-null.
 *
 * ### Two rules are delegated rather than copied
 * [validateTitle] and [validatePurchasePrice] forward to [MediaMetadataValidation], exactly as
 * [com.hub.media.features.movies.data.MovieMetadataValidation] does. Those rules are
 * media-agnostic, and its price check in particular encodes a non-obvious lesson -- rejecting
 * non-finite values, because `NaN < 0.0` is `false` under IEEE 754 and `"Infinity"` typed into a
 * form parses cleanly. Forking that would mean re-deriving it, and getting it subtly wrong here is
 * exactly how the two would drift.
 *
 * Until #81 these forwarded to `BookMetadataValidation` instead, and this KDoc carried an apology
 * for it: reaching into a book-named object from TV code was odd, and the honest fix was to give
 * the media-agnostic rules a media-neutral home. That is now done, and this object is a peer of
 * the book one rather than its debtor.
 */
public object TVMetadataValidation {
    /** A title must not be blank. Media-agnostic -- see this object's KDoc. */
    public fun validateTitle(title: String): String? = MediaMetadataValidation.validateTitle(title)

    /** A known price must be finite and `>= 0`. Media-agnostic -- see this object's KDoc. */
    public fun validatePurchasePrice(purchasePrice: Double?): String? =
        MediaMetadataValidation.validatePurchasePrice(purchasePrice)

    /**
     * A known [releaseYear] must fall within [MIN_RELEASE_YEAR]..[MAX_RELEASE_YEAR]; `null`
     * ("unknown") always passes.
     */
    public fun validateReleaseYear(releaseYear: Int?): String? =
        MediaMetadataValidation.validateReleaseYear(releaseYear, MIN_RELEASE_YEAR, MAX_RELEASE_YEAR)

    /** A known [totalSeasons] must be `> 0`; `null` ("unknown") always passes. */
    public fun validateTotalSeasons(totalSeasons: Int?): String? =
        if (totalSeasons != null && totalSeasons <= 0) "Total seasons must be a positive number" else null

    /**
     * A [seasonNumber] must be `>= 0`. Season `0` is legal and means specials --
     * [com.hub.media.core.database.entities.EpisodeEntity]'s KDoc says so explicitly, so this must
     * not reject it the way [validateTotalSeasons]/[validateEpisodeCount] reject zero.
     */
    public fun validateSeasonNumber(seasonNumber: Int): String? =
        if (seasonNumber < 0) "Season number must not be negative" else null

    /**
     * The quick-fill episode count for one season. Must be `> 0` and no greater than
     * [MAX_EPISODE_COUNT] -- this value directly drives a loop that generates one
     * [com.hub.media.core.database.entities.EpisodeEntity] row per episode
     * ([com.hub.media.features.tv.data.TVShowRepository.addShow]/[com.hub.media.features.tv.data.TVShowRepository.setSeasonLength]),
     * so an unbounded value lets a typo (`1000000` for `10`) attempt a million-row insert.
     */
    public fun validateEpisodeCount(episodeCount: Int): String? =
        when {
            episodeCount <= 0 -> "Episode count must be a positive number"
            episodeCount > MAX_EPISODE_COUNT -> "Episode count must not exceed $MAX_EPISODE_COUNT"
            else -> null
        }

    /**
     * Lower bound for a TV show's release year. Deliberately **not** the movie bound of 1888 or
     * the book bound of 1450: television did not exist as a broadcast medium until decades after
     * film. 1928 is the year General Electric's experimental station W2XB (Schenectady, NY) began
     * the first *regularly scheduled* television broadcasts, making it a real historical floor
     * rather than a round number -- consistent with how [MIN_RELEASE_YEAR] in the movie/book
     * validators was chosen.
     */
    public const val MIN_RELEASE_YEAR: Int = 1928

    /**
     * Upper bound. Shared with every other media type and documented on
     * [MediaMetadataValidation.MAX_RELEASE_YEAR]; re-exported here so a caller validating a show
     * reads one pair of bounds from one place.
     */
    public const val MAX_RELEASE_YEAR: Int = MediaMetadataValidation.MAX_RELEASE_YEAR

    /**
     * Upper bound for a single quick-fill's episode count. 500 comfortably covers any realistic
     * single season -- even the longest-running annual anime/soap "seasons" as commonly catalogued
     * top out in the low hundreds -- while still rejecting an obvious typo (`1000000`) before it
     * reaches the row-generating loop described on [validateEpisodeCount].
     */
    public const val MAX_EPISODE_COUNT: Int = 500
}
