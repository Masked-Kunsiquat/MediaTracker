package com.hub.media.features.tv.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for the TMDB v3 endpoints this app calls.
 *
 * Every field is nullable and every list defaults to empty. That is not defensive habit -- it is
 * what the live API actually returns: an announced-but-unaired season has `air_date: null` and
 * `episode_count: 0`, an episode with no still has `still_path: null`, and a show with no specials
 * simply has no season 0 entry rather than an empty one. Parsing is configured with
 * `ignoreUnknownKeys`, so fields this app has no use for (crew, guest stars, vote counts, production
 * companies) cost nothing but bandwidth and are deliberately not modelled.
 */

/** One page of search results. TMDB paginates everything at 20 per page. */
@Serializable
public data class TmdbSearchResponseDto(
    val page: Int? = null,
    val results: List<TmdbSearchResultDto> = emptyList(),
    @SerialName("total_pages") val totalPages: Int? = null,
    @SerialName("total_results") val totalResults: Int? = null,
)

/**
 * One search hit, for either a show or a film.
 *
 * Shows carry `name`/`first_air_date`; films carry `title`/`release_date`. One class covers both
 * because the two endpoints are otherwise identical in shape and the caller already knows which it
 * asked for -- see [displayTitle] and [displayDate].
 */
@Serializable
public data class TmdbSearchResultDto(
    val id: Int,
    val name: String? = null,
    val title: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    val overview: String? = null,
) {
    /** The show's `name` or the film's `title`, whichever this result carries. */
    public val displayTitle: String? get() = name ?: title

    /** The show's `first_air_date` or the film's `release_date`, as TMDB's `YYYY-MM-DD` string. */
    public val displayDate: String? get() = firstAirDate ?: releaseDate
}

/**
 * A show's top-level record.
 *
 * ### `numberOfSeasons`/`numberOfEpisodes` exclude specials; [seasons] does not
 * Confirmed against live data rather than the documentation (#88): Breaking Bad reports
 * `number_of_seasons: 5` and `number_of_episodes: 62`, while [seasons] holds **six** entries --
 * the extra being season 0, "Specials", with 9 episodes. Summing [seasons] naively gives 71/6 and
 * contradicts the show's own totals.
 *
 * Nothing here filters, deliberately: this is a faithful mapping of what TMDB said, and which
 * seasons an operation acts on is that operation's policy rather than the wire format's. Callers
 * that compare counts must scope to `seasonNumber >= 1` or read [TmdbSeasonSummaryDto.episodeCount]
 * per season -- see #88, which requires this either way.
 */
@Serializable
public data class TmdbShowDetailsDto(
    val id: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("last_air_date") val lastAirDate: String? = null,
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    val status: String? = null,
    @SerialName("in_production") val inProduction: Boolean? = null,
    val seasons: List<TmdbSeasonSummaryDto> = emptyList(),
)

/**
 * One season as it appears inside a show record.
 *
 * @property seasonNumber `0` means specials. There is no separate flag -- season 0 *is* the
 *   definition, as #88 records, so nothing should introduce a second notion of specialness.
 * @property episodeCount Can legitimately be `0`: Severance's announced season 3 has no episodes and
 *   a null [airDate] while still counting toward the show's `number_of_seasons`.
 * @property name Not derivable as `"Season $seasonNumber"`. Chernobyl's only season is named
 *   "Miniseries", and season 0 is named "Specials".
 */
@Serializable
public data class TmdbSeasonSummaryDto(
    val id: Int? = null,
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("episode_count") val episodeCount: Int? = null,
    val name: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
)

/** A single season's full record, which is the only place episode-level detail is available. */
@Serializable
public data class TmdbSeasonDetailsDto(
    val id: Int? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    val name: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    val episodes: List<TmdbEpisodeDto> = emptyList(),
)

/**
 * One episode.
 *
 * @property name The episode title, and it is ordinary for one to contain punctuation that matters
 *   elsewhere -- Chernobyl's first episode is titled `1:23:45`. Anything that serialises these
 *   (the episode CSV round-trip) has to handle that; nothing here escapes it.
 * @property runtime Minutes, and frequently `null` for unaired episodes.
 */
@Serializable
public data class TmdbEpisodeDto(
    val id: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int,
    @SerialName("season_number") val seasonNumber: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
)

/** A film's top-level record. */
@Serializable
public data class TmdbMovieDetailsDto(
    val id: Int,
    val title: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val runtime: Int? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    val status: String? = null,
)

/**
 * `GET /authentication`'s envelope.
 *
 * Only [success] is modelled. TMDB also returns `status_code`/`status_message` on failure, but a
 * failure never reaches this type -- the client turns a non-2xx into a [com.hub.media.core.util.Resource.Error]
 * before decoding, so the only body this ever parses is the success one.
 */
@Serializable
public data class TmdbAuthenticationDto(
    val success: Boolean = false,
)
