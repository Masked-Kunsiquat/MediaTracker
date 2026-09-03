package com.hub.media.features.movies.domain

import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.network.tmdbRatingOf
import com.hub.media.core.network.toYearOrNull
import com.hub.media.features.movies.data.MovieMetadataValidation
import com.hub.media.features.tv.network.dto.TmdbMovieDetailsDto

/**
 * Everything [com.hub.media.features.movies.data.MovieRepository.addMovie] needs, translated from
 * one TMDB film record (ROADMAP Task 13 Phase D).
 *
 * The film counterpart of [com.hub.media.features.tv.domain.TmdbShowMapping], and far smaller: a
 * film has no seasons, so there is no structure to reconcile and no per-season policy to apply. What
 * remains is the handful of judgements that are the same either way, which is why they now live in
 * `core.network` rather than in one mapper and be copied into the other.
 *
 * @property posterPath TMDB's relative path, not a URL and not a local hash — see
 *   [com.hub.media.features.tv.domain.TmdbShowMapping.posterPath].
 */
public data class TmdbMovieMapping(
    public val title: String,
    public val releaseYear: Int? = null,
    public val runtimeMinutes: Int? = null,
    public val communityRating: Double? = null,
    public val externalIdentifiers: List<Pair<IdentifierProvider, String>> = emptyList(),
    public val posterPath: String? = null,
)

/**
 * Turns one TMDB film record into the arguments that create it locally, or `null` if the response
 * carries no usable title.
 *
 * ### The synopsis is deliberately dropped
 * TMDB returns an `overview` for films and this does not carry it, which looks like an oversight and
 * is not: **`movie_details` has no column for one.** `tv_details` gained `overview` in #86 while
 * schema v6 was still editable; its film counterpart did not, and v6 froze at `v0.15.0`. Storing a
 * film's synopsis therefore costs a v7 with a tested migration, which is a decision rather than a
 * field. Mapping it to nowhere would only hide that.
 *
 * ### The release year is bounded, for the reason the show mapper learned
 * `validateReleaseYear` rejects a year outside
 * [MovieMetadataValidation.MIN_RELEASE_YEAR]..[MovieMetadataValidation.MAX_RELEASE_YEAR], and one
 * rejected field fails the entire `addMovie` — so a nonsense `release_date` would cost the user the
 * whole film, with an error naming a field they never typed. The bound here is 1888 rather than the
 * show's 1928: film predates broadcast television by decades, and each medium keeps its own floor.
 */
public fun TmdbMovieDetailsDto.toMovieMapping(): TmdbMovieMapping? {
    val name = title?.takeIf { it.isNotBlank() } ?: return null
    return TmdbMovieMapping(
        title = name,
        releaseYear = releaseDate.toYearOrNull()?.takeIf { it in RELEASE_YEAR_RANGE },
        // 0 is not a runtime. TMDB reports it for films it has no duration for, and
        // MovieDetailsEntity documents null as the only representation of "unknown" -- a stored 0
        // would also be rejected by validateRuntimeMinutes, failing the whole film.
        runtimeMinutes = runtime?.takeIf { it > 0 },
        communityRating = tmdbRatingOf(voteAverage, voteCount),
        externalIdentifiers = listOf(IdentifierProvider.TMDB to id.toString()),
        posterPath = posterPath,
    )
}

private val RELEASE_YEAR_RANGE =
    MovieMetadataValidation.MIN_RELEASE_YEAR..MovieMetadataValidation.MAX_RELEASE_YEAR
