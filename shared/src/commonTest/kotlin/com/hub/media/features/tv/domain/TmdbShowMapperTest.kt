package com.hub.media.features.tv.domain

import com.hub.media.core.database.entities.AiringStatus
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.features.tv.data.SeasonEpisodes
import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.data.TVMetadataValidation
import com.hub.media.features.tv.network.TmdbShowWithSeasons
import com.hub.media.features.tv.network.dto.TmdbEpisodeDto
import com.hub.media.features.tv.network.dto.TmdbSeasonDetailsDto
import com.hub.media.features.tv.network.dto.TmdbSeasonSummaryDto
import com.hub.media.features.tv.network.dto.TmdbShowDetailsDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Covers [toShowMapping] -- the one place TMDB's answers are interpreted before they reach the
 * database. Pure, so it lives in `commonTest` and runs on every target; nothing here touches Room.
 *
 * The values are taken from the live responses recorded on #75 and #88 rather than invented, because
 * every case below is one TMDB actually produces: Breaking Bad's specials, Severance's announced
 * empty season, Chernobyl's colon-bearing episode title and non-"Season 1" season name.
 */
class TmdbShowMapperTest {
    private fun show(
        id: Int = 1399,
        name: String? = "Breaking Bad",
        firstAirDate: String? = "2008-01-20",
        lastAirDate: String? = "2013-09-29",
        numberOfSeasons: Int? = 5,
        status: String? = "Ended",
        inProduction: Boolean? = false,
        voteAverage: Double? = 8.9,
        voteCount: Int? = 12000,
        overview: String? = "A chemistry teacher turns to crime.",
        posterPath: String? = "/poster.jpg",
        seasons: List<TmdbSeasonSummaryDto> = emptyList(),
    ) = TmdbShowDetailsDto(
        id = id,
        name = name,
        overview = overview,
        firstAirDate = firstAirDate,
        lastAirDate = lastAirDate,
        numberOfSeasons = numberOfSeasons,
        posterPath = posterPath,
        status = status,
        inProduction = inProduction,
        voteAverage = voteAverage,
        voteCount = voteCount,
        seasons = seasons,
    )

    private fun episode(
        number: Int,
        name: String? = "Episode $number",
        airDate: String? = null,
        runtime: Int? = null,
        voteAverage: Double? = null,
        voteCount: Int? = null,
    ) = TmdbEpisodeDto(
        episodeNumber = number,
        name = name,
        airDate = airDate,
        runtime = runtime,
        voteAverage = voteAverage,
        voteCount = voteCount,
    )

    private fun seasonDetails(
        number: Int,
        episodes: List<TmdbEpisodeDto>,
    ) = TmdbSeasonDetailsDto(seasonNumber = number, episodes = episodes)

    // ---- the show record -----------------------------------------------------------------------

    @Test
    fun mapsTheShowRecordOntoTheAddShowArguments() {
        val mapping =
            TmdbShowWithSeasons(
                show = show(),
                seasons = mapOf(1 to seasonDetails(1, listOf(episode(1)))),
            ).toShowMapping()

        assertTrue(mapping != null)
        assertEquals("Breaking Bad", mapping.title)
        assertEquals(2008, mapping.releaseYear, "the release year is the first air date's year")
        assertEquals(5, mapping.totalSeasons)
        assertEquals(AiringStatus.ENDED, mapping.airingStatus)
        assertEquals("A chemistry teacher turns to crime.", mapping.overview)
        assertEquals(Instant.parse("2008-01-20T00:00:00Z"), mapping.firstAirDate)
        assertEquals(Instant.parse("2013-09-29T00:00:00Z"), mapping.lastAirDate)
        assertEquals(8.9, mapping.communityRating)
        assertEquals("/poster.jpg", mapping.posterPath)
        assertEquals(
            listOf(IdentifierProvider.TMDB to "1399"),
            mapping.externalIdentifiers,
            "without this the backfill cannot match the row back to its TMDB record",
        )
    }

    @Test
    fun aShowWithNoUsableTitleMapsToNull() {
        // Nothing downstream can create a media item without a title, and validateTitle would reject
        // it anyway -- refusing here means the caller gets one clear "no" instead of a Resource.Error
        // about a field the user never typed.
        assertNull(TmdbShowWithSeasons(show(name = null), emptyMap()).toShowMapping())
        assertNull(TmdbShowWithSeasons(show(name = "   "), emptyMap()).toShowMapping())
    }

    // ---- seasons -------------------------------------------------------------------------------

    @Test
    fun specialsAreExcluded() {
        // #75: add-by-search creates regular seasons only. Breaking Bad's real shape -- season 0
        // holds 9 specials, which is why summing TMDB's seasons naively gives 71 against its own
        // stated 62.
        val mapping =
            TmdbShowWithSeasons(
                show = show(),
                seasons =
                    mapOf(
                        0 to seasonDetails(0, listOf(episode(1), episode(2))),
                        1 to seasonDetails(1, listOf(episode(1))),
                    ),
            ).toShowMapping()

        assertEquals(listOf(1), mapping!!.seasons.map { it.seasonNumber })
    }

    @Test
    fun aSeasonTmdbDescribedButDidNotSendBecomesAQuickFill() {
        // Past append_to_response's 20-item ceiling, or dropped for a decode failure. The titles are
        // unavailable but the count is not, so the rows exist and can be ticked off, and the
        // enrichment backfill fills their titles later by matching on (season, episode).
        val mapping =
            TmdbShowWithSeasons(
                show = show(seasons = listOf(TmdbSeasonSummaryDto(seasonNumber = 21, episodeCount = 12))),
                seasons = mapOf(1 to seasonDetails(1, listOf(episode(1)))),
            ).toShowMapping()

        val quickFilled = mapping!!.seasons.single { it.seasonNumber == 21 }
        assertIs<SeasonQuickFill>(quickFilled)
        assertEquals(12, quickFilled.episodeCount)
    }

    @Test
    fun aMissingSeasonTooLargeToQuickFillIsSkippedRatherThanFailingTheWholeShow() {
        // validateEpisodeCount caps a quick-fill at MAX_EPISODE_COUNT, and that rejection fails the
        // entire addShow rather than just its own season. A continuous-season catalogue past 500 is
        // precisely the case this mapper cannot quick-fill -- so it is skipped, and the other
        // seasons still arrive. Before this bound, one such season lost the user the whole show.
        val oversized = TVMetadataValidation.MAX_EPISODE_COUNT + 1
        val mapping =
            TmdbShowWithSeasons(
                show =
                    show(
                        seasons =
                            listOf(
                                TmdbSeasonSummaryDto(seasonNumber = 21, episodeCount = oversized),
                                TmdbSeasonSummaryDto(seasonNumber = 22, episodeCount = 8),
                            ),
                    ),
                seasons = mapOf(1 to seasonDetails(1, listOf(episode(1)))),
            ).toShowMapping()

        assertEquals(
            listOf(1, 22),
            mapping!!.seasons.map { it.seasonNumber },
            "the oversized season is skipped; season 22 and the fetched season still arrive",
        )
    }

    @Test
    fun aMissingSeasonExactlyAtTheCapIsStillQuickFilled() {
        // The boundary is inclusive -- a positive control for the skip above, so the bound cannot
        // silently tighten to something that drops ordinary seasons.
        val mapping =
            TmdbShowWithSeasons(
                show =
                    show(
                        seasons =
                            listOf(
                                TmdbSeasonSummaryDto(
                                    seasonNumber = 21,
                                    episodeCount = TVMetadataValidation.MAX_EPISODE_COUNT,
                                ),
                            ),
                    ),
                seasons = mapOf(1 to seasonDetails(1, listOf(episode(1)))),
            ).toShowMapping()

        val quickFilled = mapping!!.seasons.single { it.seasonNumber == 21 }
        assertIs<SeasonQuickFill>(quickFilled)
        assertEquals(TVMetadataValidation.MAX_EPISODE_COUNT, quickFilled.episodeCount)
    }

    @Test
    fun anAnnouncedSeasonWithNoEpisodesIsDropped() {
        // Severance's season 3: episode_count 0, air_date null, still counted in number_of_seasons.
        val mapping =
            TmdbShowWithSeasons(
                show = show(seasons = listOf(TmdbSeasonSummaryDto(seasonNumber = 3, episodeCount = 0))),
                seasons =
                    mapOf(
                        1 to seasonDetails(1, listOf(episode(1))),
                        2 to seasonDetails(2, emptyList()),
                    ),
            ).toShowMapping()

        assertEquals(listOf(1), mapping!!.seasons.map { it.seasonNumber })
    }

    @Test
    fun seasonsComeBackInOrderRegardlessOfHowTheyArrived() {
        val mapping =
            TmdbShowWithSeasons(
                show = show(seasons = listOf(TmdbSeasonSummaryDto(seasonNumber = 21, episodeCount = 3))),
                seasons =
                    mapOf(
                        2 to seasonDetails(2, listOf(episode(1))),
                        1 to seasonDetails(1, listOf(episode(1))),
                    ),
            ).toShowMapping()

        assertEquals(listOf(1, 2, 21), mapping!!.seasons.map { it.seasonNumber })
    }

    // ---- episodes ------------------------------------------------------------------------------

    @Test
    fun episodeMetadataCarriesThrough() {
        val mapping =
            TmdbShowWithSeasons(
                show = show(),
                seasons =
                    mapOf(
                        1 to
                            seasonDetails(
                                1,
                                // Chernobyl's real first episode: a colon in the title, which is the
                                // character the episode CSV round-trip has to survive.
                                listOf(
                                    episode(
                                        number = 1,
                                        name = "1:23:45",
                                        airDate = "2019-05-06",
                                        runtime = 61,
                                        voteAverage = 8.6,
                                        voteCount = 400,
                                    ),
                                ),
                            ),
                    ),
            ).toShowMapping()

        val season = mapping!!.seasons.single()
        assertIs<SeasonEpisodes>(season)
        val ep = season.episodes.single()
        assertEquals("1:23:45", ep.title)
        assertEquals(Instant.parse("2019-05-06T00:00:00Z"), ep.airDate)
        assertEquals(61, ep.runtimeMinutes)
        assertEquals(8.6, ep.communityRating)
    }

    @Test
    fun aZeroRuntimeBecomesUnknownRatherThanZero() {
        // 0 is not a runtime -- EpisodeEntity documents null as the only "not known yet", and
        // validateEpisodeRuntimeMinutes would reject a stored 0 and fail the whole show.
        val mapping =
            TmdbShowWithSeasons(
                show(),
                mapOf(1 to seasonDetails(1, listOf(episode(1, runtime = 0)))),
            ).toShowMapping()

        assertNull((mapping!!.seasons.single() as SeasonEpisodes).episodes.single().runtimeMinutes)
    }

    // ---- ratings -------------------------------------------------------------------------------

    @Test
    fun anUnratedTitleIsUnknownRatherThanZeroOutOfTen() {
        // TMDB answers an unrated title with vote_average 0.0 -- the mean of an empty set, not a
        // score. Stored naively it marks every obscure show 0/10, and unlike null that is a number
        // which survives into averages looking like data.
        val mapping =
            TmdbShowWithSeasons(
                show = show(voteAverage = 0.0, voteCount = 0),
                seasons = mapOf(1 to seasonDetails(1, listOf(episode(1, voteAverage = 0.0, voteCount = 0)))),
            ).toShowMapping()

        assertNull(mapping!!.communityRating, "no votes means unknown, not zero")
        assertNull((mapping.seasons.single() as SeasonEpisodes).episodes.single().communityRating)
    }

    @Test
    fun aGenuineZeroWithVotesIsKept() {
        // The counterpart to the case above, and why voteCount is modelled at all rather than
        // treating 0.0 as unrated by convention.
        val mapping = TmdbShowWithSeasons(show(voteAverage = 0.0, voteCount = 5), emptyMap()).toShowMapping()

        assertEquals(0.0, mapping!!.communityRating)
    }

    // ---- airing status -------------------------------------------------------------------------

    @Test
    fun airingStatusMapsTmdbsDocumentedStrings() {
        fun statusOf(
            raw: String?,
            inProduction: Boolean? = false,
        ) = TmdbShowWithSeasons(show(status = raw, inProduction = inProduction), emptyMap())
            .toShowMapping()!!
            .airingStatus

        assertEquals(AiringStatus.CONTINUING, statusOf("Returning Series"))
        assertEquals(AiringStatus.CONTINUING, statusOf("In Production"))
        assertEquals(AiringStatus.CONTINUING, statusOf("Planned"))
        assertEquals(AiringStatus.ENDED, statusOf("Ended"))
        // TMDB spells it with one l; both are accepted so a corrected upstream spelling is not a
        // silent regression to "unknown".
        assertEquals(AiringStatus.CANCELLED, statusOf("Canceled"))
        assertEquals(AiringStatus.CANCELLED, statusOf("Cancelled"))
        assertEquals(AiringStatus.ENDED, statusOf("ended"), "matching is case-insensitive")
    }

    @Test
    fun anUnrecognisedStatusFallsBackToInProductionAndThenToUnknown() {
        fun statusOf(
            raw: String?,
            inProduction: Boolean?,
        ) = TmdbShowWithSeasons(show(status = raw, inProduction = inProduction), emptyMap())
            .toShowMapping()!!
            .airingStatus

        assertEquals(AiringStatus.CONTINUING, statusOf("Something New", true))
        // in_production=false cannot tell ENDED from CANCELLED, and that distinction is the whole
        // reason they are separate values -- so it stays unknown rather than guessing.
        assertNull(statusOf("Something New", false))
        assertNull(statusOf(null, null))
    }

    // ---- dates ---------------------------------------------------------------------------------

    @Test
    fun unparseableDatesBecomeNullRatherThanFailingTheShow() {
        val mapping =
            TmdbShowWithSeasons(
                show(firstAirDate = "", lastAirDate = "not-a-date"),
                emptyMap(),
            ).toShowMapping()

        assertTrue(mapping != null, "one bad date must not cost the whole show")
        assertNull(mapping.firstAirDate)
        assertNull(mapping.lastAirDate)
        assertNull(mapping.releaseYear)
    }

    @Test
    fun aReleaseYearOutsideTheAcceptedWindowIsDroppedRatherThanFailingTheShow() {
        // "0001-01-01" yields year 1, which validateReleaseYear rejects -- and one rejected field
        // fails the entire addShow. A year nobody typed must not be able to cost the whole show.
        val mapping = TmdbShowWithSeasons(show(firstAirDate = "0001-01-01"), emptyMap()).toShowMapping()

        assertTrue(mapping != null, "a nonsense date must not lose the show")
        assertNull(mapping.releaseYear)
        assertEquals(
            Instant.parse("0001-01-01T00:00:00Z"),
            mapping.firstAirDate,
            "the date column has no such bound, so the value it can hold is still kept",
        )
    }

    @Test
    fun aReleaseYearAtEitherBoundaryIsKept() {
        // Positive controls, so the window cannot silently narrow onto real shows.
        fun yearFor(date: String) =
            TmdbShowWithSeasons(show(firstAirDate = date), emptyMap()).toShowMapping()!!.releaseYear

        assertEquals(TVMetadataValidation.MIN_RELEASE_YEAR, yearFor("1928-07-02"))
        assertEquals(TVMetadataValidation.MAX_RELEASE_YEAR, yearFor("2100-01-01"))
    }

    @Test
    fun datesAreAnchoredToUtcSoTwoDevicesAgree() {
        // The device's zone would make the same response produce different stored values on two
        // phones, and a CSV round-trip between them lossy.
        val mapping = TmdbShowWithSeasons(show(firstAirDate = "2019-05-06"), emptyMap()).toShowMapping()

        assertEquals(Instant.parse("2019-05-06T00:00:00Z"), mapping!!.firstAirDate)
    }
}
