package com.hub.media.features.tv.data

import com.hub.media.features.books.domain.BookMetadataValidation

/**
 * Pure TV-metadata validation (ROADMAP Task 13 Phase C). Same contract as
 * [com.hub.media.features.movies.data.MovieMetadataValidation]: each function returns a
 * human-readable rejection message, or `null` when the value is valid, and callers short-circuit
 * on the first non-null.
 *
 * ### Two rules are delegated rather than copied
 * [validateTitle] and [validatePurchasePrice] forward to [BookMetadataValidation], exactly as
 * [com.hub.media.features.movies.data.MovieMetadataValidation] does. Those rules are
 * media-agnostic despite that object's name, and its price check in particular encodes a
 * non-obvious lesson -- rejecting non-finite values, because `NaN < 0.0` is `false` under IEEE 754
 * and `"Infinity"` typed into a form parses cleanly. Forking that would mean re-deriving it, and
 * getting it subtly wrong here is exactly how the two would drift.
 *
 * Reaching into a book-named object from TV code is admittedly odd, and the honest fix is to
 * rename it to something media-neutral. That is deliberately not done here, for the same reason
 * the movie validation object leaves it alone: it touches `ImportDataUseCase` and `BookRepository`,
 * neither of which this phase is otherwise changing.
 */
public object TVMetadataValidation {
    /** A title must not be blank. Media-agnostic -- see this object's KDoc. */
    public fun validateTitle(title: String): String? = BookMetadataValidation.validateTitle(title)

    /** A known price must be finite and `>= 0`. Media-agnostic -- see this object's KDoc. */
    public fun validatePurchasePrice(purchasePrice: Double?): String? =
        BookMetadataValidation.validatePurchasePrice(purchasePrice)

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
     * ([com.hub.media.features.tv.data.TVShowRepository.addShow]/[com.hub.media.features.tv.data.TVShowRepository.addSeason]),
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
     * Upper bound, matching the movie/book rules' reasoning: a static far-future year keeps the
     * bound deterministic for tests rather than deriving "current year + N" from a clock, while
     * still covering announced future releases for decades.
     */
    public const val MAX_RELEASE_YEAR: Int = 2100

    /**
     * Upper bound for a single quick-fill's episode count. 500 comfortably covers any realistic
     * single season -- even the longest-running annual anime/soap "seasons" as commonly catalogued
     * top out in the low hundreds -- while still rejecting an obvious typo (`1000000`) before it
     * reaches the row-generating loop described on [validateEpisodeCount].
     */
    public const val MAX_EPISODE_COUNT: Int = 500
}
