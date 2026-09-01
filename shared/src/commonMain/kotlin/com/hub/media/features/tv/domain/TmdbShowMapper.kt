package com.hub.media.features.tv.domain

import com.hub.media.core.database.entities.AiringStatus
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.features.tv.data.NewEpisode
import com.hub.media.features.tv.data.NewSeason
import com.hub.media.features.tv.data.SeasonEpisodes
import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.data.TVMetadataValidation
import com.hub.media.features.tv.network.TmdbShowWithSeasons
import com.hub.media.features.tv.network.dto.TmdbEpisodeDto
import kotlin.time.Instant

/**
 * Everything [com.hub.media.features.tv.data.TVShowRepository.addShow] needs, translated from one
 * TMDB response (ROADMAP Task 13 Phase D).
 *
 * A named result rather than a call straight into `addShow` because this is where every decision
 * about *what TMDB actually meant* is taken, and those decisions deserve to be testable without a
 * database. The ViewModel spreads this into the repository; nothing here writes.
 *
 * @property posterPath TMDB's relative path (`/abc123.jpg`), not a URL and not a local hash. Carried
 *   so the poster download has it without a second request; it is not stored on any row as-is --
 *   [com.hub.media.core.database.entities.MediaItemEntity.coverImageHash] holds the hash of a
 *   *locally saved* file, per AGENTS.md §4.
 */
public data class TmdbShowMapping(
    public val title: String,
    public val releaseYear: Int? = null,
    public val totalSeasons: Int? = null,
    public val seasons: List<NewSeason> = emptyList(),
    public val externalIdentifiers: List<Pair<IdentifierProvider, String>> = emptyList(),
    public val airingStatus: AiringStatus? = null,
    public val overview: String? = null,
    public val firstAirDate: Instant? = null,
    public val lastAirDate: Instant? = null,
    public val communityRating: Double? = null,
    public val posterPath: String? = null,
)

/**
 * Turns one [TmdbShowWithSeasons] into the arguments that create the show locally, or `null` if the
 * response carries no usable title.
 *
 * ### Specials are excluded here, and this is the place that decision lives
 * #75 settled that add-by-search creates `seasonNumber >= 1` only, and #122 tracks revisiting it.
 * Neither [com.hub.media.features.tv.network.TmdbClient] nor
 * [com.hub.media.features.tv.data.TVShowRepository] filters season 0 -- both deliberately return or
 * accept whatever they are given, so that the policy is applied in exactly one place and is visible
 * when someone goes looking for it. This function is that place. Changing #122's answer means
 * changing this line and nothing else.
 *
 * ### A season TMDB described but did not send becomes a quick-fill
 * [TmdbShowWithSeasons.missingSeasonNumbers] reports seasons the show declares that the response
 * does not carry -- past `append_to_response`'s 20-item ceiling, or dropped because their payload
 * would not decode. Their episode *titles* are unavailable, but their episode *count* is: it is in
 * the show's own season list. So they are created as [SeasonQuickFill] rather than skipped.
 *
 * That is the honest outcome rather than a consolation prize. The rows exist with the right numbers
 * and can be ticked off immediately, exactly as a hand-quick-filled season can, and the enrichment
 * backfill fills their titles later precisely because it matches on `(seasonNumber, episodeNumber)`.
 * Skipping them would leave a 22-season show holding 20 seasons with nothing recording that two are
 * missing.
 *
 * The count is bounded by [TVMetadataValidation.MAX_EPISODE_COUNT], because a quick-fill is checked
 * against it and a rejection fails the *entire* `addShow`, not just its own season. That bound does
 * not apply to a [SeasonEpisodes] list -- this PR argues at length that a continuous-season
 * catalogue legitimately runs past 500 -- so a season large enough to hit it is exactly the case
 * that cannot be quick-filled. It is skipped rather than allowed to cost the whole show, which is
 * the same trade [com.hub.media.features.tv.network.TmdbClient] makes when one season's payload will
 * not decode.
 *
 * ### Empty seasons are dropped
 * A season with `episode_count: 0` and no episodes -- Severance's announced season 3 -- creates
 * nothing either way. It is dropped rather than passed through as an empty [SeasonEpisodes], because
 * [com.hub.media.features.tv.data.TVMetadataValidation.validateEpisodeCount] rejects a zero-length
 * quick-fill outright, and having the two paths disagree about the same season would be a difference
 * with no meaning behind it.
 */
public fun TmdbShowWithSeasons.toShowMapping(): TmdbShowMapping? {
    val title = show.name?.takeIf { it.isNotBlank() } ?: return null

    val fetched =
        seasons
            .filterKeys { it >= REGULAR_SEASON_FLOOR }
            .mapNotNull { (number, season) ->
                season.episodes
                    .takeIf { it.isNotEmpty() }
                    ?.let { SeasonEpisodes(seasonNumber = number, episodes = it.map(TmdbEpisodeDto::toNewEpisode)) }
            }

    val declaredCounts = show.seasons.associateBy { it.seasonNumber }
    val quickFilled =
        missingSeasonNumbers.mapNotNull { number ->
            declaredCounts[number]
                ?.episodeCount
                ?.takeIf { it in 1..TVMetadataValidation.MAX_EPISODE_COUNT }
                ?.let { SeasonQuickFill(seasonNumber = number, episodeCount = it) }
        }

    return TmdbShowMapping(
        title = title,
        releaseYear = show.firstAirDate.toYearOrNull()?.takeIf { it in RELEASE_YEAR_RANGE },
        // TMDB's own count, not the number of seasons created. It excludes specials (#88), which is
        // the same set this creates, and it stays right for a show whose later seasons arrived as
        // quick-fills. TVDetailsEntity.totalSeasons is advisory anyway -- the episode rows are the
        // truth about what exists.
        totalSeasons = show.numberOfSeasons,
        seasons = (fetched + quickFilled).sortedBy { it.seasonNumber },
        externalIdentifiers = listOf(IdentifierProvider.TMDB to show.id.toString()),
        airingStatus = airingStatusOf(show.status, show.inProduction),
        overview = show.overview,
        firstAirDate = show.firstAirDate.toInstantOrNull(),
        lastAirDate = show.lastAirDate.toInstantOrNull(),
        communityRating = ratingOf(show.voteAverage, show.voteCount),
        posterPath = show.posterPath,
    )
}

/** Season 0 is specials; everything this app's add-by-search creates starts at 1 (#75, #122). */
private const val REGULAR_SEASON_FLOOR = 1

/**
 * The window [com.hub.media.features.tv.data.TVShowRepository.addShow] will accept a release year in.
 *
 * A year outside it is not a release year, it is the first four characters of a date this app could
 * not read -- `"0001-01-01"` yields `1`. Dropping it costs one display field; passing it through
 * costs the **whole show**, because `validateReleaseYear` rejects it and one rejected field fails the
 * entire insert. That is the wrong trade for a value nobody typed, and the same trade the quick-fill
 * ceiling above already makes.
 */
private val RELEASE_YEAR_RANGE =
    TVMetadataValidation.MIN_RELEASE_YEAR..TVMetadataValidation.MAX_RELEASE_YEAR

private fun TmdbEpisodeDto.toNewEpisode(): NewEpisode =
    NewEpisode(
        episodeNumber = episodeNumber,
        title = name,
        airDate = airDate.toInstantOrNull(),
        // 0 is not a runtime. TMDB reports it for episodes it has no duration for, and
        // EpisodeEntity documents null as the only representation of "not known yet" -- a stored 0
        // would also be rejected by validateEpisodeRuntimeMinutes, failing the whole show.
        runtimeMinutes = runtime?.takeIf { it > 0 },
        overview = overview,
        communityRating = ratingOf(voteAverage, voteCount),
    )

/**
 * A provider score, or `null` when nothing has actually been rated.
 *
 * TMDB answers an unrated title with `vote_average: 0.0`, not `null` -- the mean of an empty set.
 * Passing that through would record "everybody scored this zero" for every obscure episode, which is
 * worse than recording nothing: it is a number, so it survives into averages and comparisons looking
 * like data. [voteCount] is the only thing that distinguishes the two, which is why the DTOs model a
 * field nothing else reads.
 *
 * The range is *not* clamped here. A value outside 0-10 means TMDB changed its scale, and
 * [com.hub.media.features.media.domain.MediaMetadataValidation.validateCommunityRating] refusing the
 * write is the correct outcome -- silently clamping would store a wrong number rather than surface a
 * broken assumption.
 */
private fun ratingOf(
    voteAverage: Double?,
    voteCount: Int?,
): Double? = voteAverage?.takeIf { (voteCount ?: 0) > 0 }

/**
 * Maps TMDB's `status` string onto [AiringStatus], falling back to `in_production` and then to
 * `null` for "unknown".
 *
 * The strings are TMDB's documented set. Note the spelling: TMDB writes **`Canceled`** with one `l`,
 * and matching is case-insensitive rather than exact because a status is display text that has been
 * recased before. An unrecognised status yields `null` rather than a guess -- [AiringStatus] is read
 * by `LibraryStatusFilter.ofShow` to decide whether a fully-watched show is finished, and a wrong
 * answer there mislabels the show. Unknown is a state that column already documents.
 *
 * `in_production` is only consulted when the status is unrecognised, and only in the direction it
 * can be trusted: `true` means more episodes are coming, so [AiringStatus.CONTINUING]. `false` does
 * *not* distinguish [AiringStatus.ENDED] from [AiringStatus.CANCELLED], and that distinction is the
 * entire reason those are separate values, so it stays `null`.
 */
private fun airingStatusOf(
    status: String?,
    inProduction: Boolean?,
): AiringStatus? =
    when (status?.trim()?.lowercase()) {
        "returning series", "in production", "planned", "pilot" -> AiringStatus.CONTINUING
        "ended" -> AiringStatus.ENDED
        "canceled", "cancelled" -> AiringStatus.CANCELLED
        else -> if (inProduction == true) AiringStatus.CONTINUING else null
    }

/**
 * TMDB's `YYYY-MM-DD` as an [Instant] at midnight UTC, or `null`.
 *
 * A date with no time and no zone has to be given one to become an instant, and UTC midnight is the
 * convention already used elsewhere in this codebase for provider dates. The alternative -- the
 * device's zone -- would make the same TMDB response produce different stored values on two phones,
 * and make a CSV round-trip between them lossy.
 *
 * Anything unparseable yields `null` rather than throwing. TMDB sends `""` for unknown often enough
 * that it is ordinary, not exceptional, and one bad date must not cost the whole show.
 */
internal fun String?.toInstantOrNull(): Instant? {
    val raw = this?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return runCatching { Instant.parse("${raw}T00:00:00Z") }.getOrNull()
}

/** The four-digit year of a TMDB `YYYY-MM-DD` date, or `null`. */
internal fun String?.toYearOrNull(): Int? = this?.trim()?.take(4)?.toIntOrNull()
