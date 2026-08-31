package com.hub.media.features.tv.network

import com.hub.media.core.network.RequestPacer
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.util.LogLevel
import com.hub.media.core.util.RecordingLogger
import com.hub.media.core.util.Resource
import com.hub.media.features.tv.network.dto.TmdbSearchResponseDto
import com.hub.media.features.tv.network.dto.TmdbSeasonDetailsDto
import com.hub.media.features.tv.network.dto.TmdbShowDetailsDto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** A [Clock] frozen at the epoch, so pacing is decided by grants rather than by machine speed. */
private object FrozenClock : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(0)
}

class TmdbClientTest {
    private val jwt = "eyJ-fake-tmdb-read-access-token-for-tests"
    private val v3Key = "fake-v3-api-key-for-tests"

    private fun MockRequestHandleScope.jsonResponse(
        json: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = json,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    /**
     * Breaking Bad's real season list, trimmed to the fields this app models.
     *
     * Kept verbatim from a live response rather than invented, because the property under test is
     * precisely the one a hand-written fixture would smooth over: `number_of_seasons`/
     * `number_of_episodes` report 5/62 while `seasons` holds six entries totalling 71, the extra
     * being season 0. See #88.
     */
    private val breakingBadJson =
        """
        {
          "id": 1396,
          "name": "Breaking Bad",
          "first_air_date": "2008-01-20",
          "last_air_date": "2013-09-29",
          "number_of_seasons": 5,
          "number_of_episodes": 62,
          "status": "Ended",
          "in_production": false,
          "poster_path": "/anFx9aTOOYqgS3v7x3R84Kz67ly.jpg",
          "seasons": [
            {"id": 3577, "season_number": 0, "episode_count": 9,  "name": "Specials", "air_date": "2009-02-17"},
            {"id": 3572, "season_number": 1, "episode_count": 7,  "name": "Season 1", "air_date": "2008-01-20"},
            {"id": 3573, "season_number": 2, "episode_count": 13, "name": "Season 2", "air_date": "2009-03-08"},
            {"id": 3575, "season_number": 3, "episode_count": 13, "name": "Season 3", "air_date": "2010-03-21"},
            {"id": 3576, "season_number": 4, "episode_count": 13, "name": "Season 4", "air_date": "2011-07-17"},
            {"id": 3578, "season_number": 5, "episode_count": 16, "name": "Season 5", "air_date": "2012-07-15"}
          ]
        }
        """.trimIndent()

    /**
     * Severance's announced-but-unaired third season, again from a live response: `episode_count` is
     * `0` and `air_date` is `null`, while the show still reports `number_of_seasons: 3`.
     */
    private val severanceJson =
        """
        {
          "id": 95396,
          "name": "Severance",
          "first_air_date": "2022-02-17",
          "number_of_seasons": 3,
          "number_of_episodes": 19,
          "status": "Returning Series",
          "in_production": true,
          "seasons": [
            {"season_number": 0, "episode_count": 1,  "name": "Specials", "air_date": "2021-12-15"},
            {"season_number": 1, "episode_count": 9,  "name": "Season 1", "air_date": "2022-02-17"},
            {"season_number": 2, "episode_count": 10, "name": "Season 2", "air_date": "2025-01-16"},
            {"season_number": 3, "episode_count": 0,  "name": "Season 3", "air_date": null}
          ]
        }
        """.trimIndent()

    /** Chernobyl season 1: a colon in an episode title, and a season not named "Season 1". */
    private val chernobylSeasonJson =
        """
        {
          "id": 108546,
          "season_number": 1,
          "name": "Miniseries",
          "air_date": "2019-05-06",
          "episodes": [
            {
              "id": 1725580, "episode_number": 1, "season_number": 1, "name": "1:23:45",
              "air_date": "2019-05-06", "runtime": 61,
              "still_path": "/thaMHLz5l6TVL8R4EzaBkjn2EZA.jpg", "vote_average": 8.1,
              "crew": [{"id": 1, "name": "ignored"}], "guest_stars": []
            },
            {
              "id": 1725581, "episode_number": 2, "season_number": 1, "name": "Please Remain Calm",
              "air_date": "2019-05-13", "runtime": 65, "still_path": null, "vote_average": 8.2
            }
          ]
        }
        """.trimIndent()

    @Test
    fun showDetails_reportsSeasonsFaithfullyIncludingSpecials() =
        runTest {
            val engine = MockEngine { jsonResponse(breakingBadJson) }
            val client = TmdbClient(createHttpClient(engine), credentialProvider = { jwt })

            val result = client.showDetails(1396)

            assertIs<Resource.Success<TmdbShowDetailsDto>>(result)
            val show = result.data
            assertEquals(5, show.numberOfSeasons)
            assertEquals(62, show.numberOfEpisodes)
            // The client does not filter. This is the discrepancy #88 documents, and the whole point
            // of leaving it visible: six seasons summing to 71 against a headline 5/62.
            assertEquals(6, show.seasons.size, "season 0 must survive into the parsed result")
            assertEquals(71, show.seasons.sumOf { it.episodeCount ?: 0 })
            assertEquals(listOf(0, 1, 2, 3, 4, 5), show.seasons.map { it.seasonNumber })
            assertEquals("Specials", show.seasons.first().name)
        }

    /**
     * An announced season with no episodes must parse, not throw. A `null` air date on a season is
     * ordinary rather than exceptional, and a DTO that required it would fail on any returning show.
     */
    @Test
    fun showDetails_parsesAnAnnouncedSeasonWithNoEpisodesAndNoAirDate() =
        runTest {
            val engine = MockEngine { jsonResponse(severanceJson) }
            val client = TmdbClient(createHttpClient(engine), credentialProvider = { jwt })

            val result = client.showDetails(95396)

            assertIs<Resource.Success<TmdbShowDetailsDto>>(result)
            val announced = result.data.seasons.single { it.seasonNumber == 3 }
            assertEquals(0, announced.episodeCount)
            assertNull(announced.airDate)
        }

    @Test
    fun seasonDetails_parsesEpisodesIncludingAColonInATitle() =
        runTest {
            val engine = MockEngine { jsonResponse(chernobylSeasonJson) }
            val client = TmdbClient(createHttpClient(engine), credentialProvider = { jwt })

            val result = client.seasonDetails(87108, 1)

            assertIs<Resource.Success<TmdbSeasonDetailsDto>>(result)
            val season = result.data
            assertEquals("Miniseries", season.name, "a season name is not derivable as \"Season n\"")
            assertEquals(2, season.episodes.size)
            assertEquals("1:23:45", season.episodes.first().name)
            assertEquals(61, season.episodes.first().runtime)
            assertNull(season.episodes[1].stillPath, "a missing still is null, not an error")
        }

    @Test
    fun noCredential_failsWithoutIssuingARequest() =
        runTest {
            val engine = MockEngine { jsonResponse(breakingBadJson) }
            val client = TmdbClient(createHttpClient(engine), credentialProvider = { null })

            val result = client.showDetails(1396)

            assertIs<Resource.Error>(result)
            assertTrue(
                engine.requestHistory.isEmpty(),
                "with no credential the request must not be attempted at all -- TMDB refuses anonymous calls",
            )
        }

    /**
     * A 401 has to be distinguishable from an ordinary failure, because the remedy is different: the
     * user must go and fix something in Settings rather than retry.
     */
    @Test
    fun aRejectedCredentialSaysSoAndLogsWhichShapeWasSent() =
        runTest {
            val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
            val recorder = RecordingLogger()
            val client =
                TmdbClient(createHttpClient(engine), credentialProvider = { v3Key }, logger = recorder)

            val result = client.showDetails(1396)

            assertIs<Resource.Error>(result)
            assertTrue(result.message.contains("Settings"), "the message must point at the remedy")
            val warning = recorder.entries.single { it.level == LogLevel.WARN }
            assertTrue(
                "ApiKey" in warning.message,
                "which of TMDB's two credential shapes was sent is the most useful fact here",
            )
            assertFalse(v3Key in warning.message, "the credential itself must never be logged")
        }

    /**
     * The no-URL-logging rule from this client's KDoc, enforced rather than described. With a v3
     * credential the secret is a query parameter, so any logged URL leaks it into the in-app log
     * viewer and its export.
     */
    @Test
    fun noLogLineEverContainsTheCredentialOrARequestUrl() =
        runTest {
            val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
            val recorder = RecordingLogger()
            val client =
                TmdbClient(createHttpClient(engine), credentialProvider = { v3Key }, logger = recorder)

            client.showDetails(1396)
            client.searchShows("breaking bad")

            assertTrue(recorder.entries.isNotEmpty(), "this test proves nothing if nothing was logged")
            for (entry in recorder.entries) {
                assertFalse(v3Key in entry.message, "a credential reached the log: ${entry.message}")
                assertFalse("api_key" in entry.message, "a query string reached the log: ${entry.message}")
                assertFalse("https://" in entry.message, "a URL reached the log: ${entry.message}")
            }
        }

    @Test
    fun anEmptyQuerySpendsNoRequest() =
        runTest {
            val engine = MockEngine { jsonResponse("""{"results":[]}""") }
            val client = TmdbClient(createHttpClient(engine), credentialProvider = { jwt })

            val result = client.searchShows("   ")

            assertIs<Resource.Success<TmdbSearchResponseDto>>(result)
            assertTrue(engine.requestHistory.isEmpty(), "an empty query must not cost a request")
        }

    /**
     * Every request goes through the pacer when one is supplied -- asserted against the engine's own
     * history rather than a literal, the same shape `OpenLibraryClientTest` settled on in #42, so it
     * keeps holding if the number of requests per operation changes.
     */
    @Test
    fun everyRequestIsPacedWhenAPacerIsSupplied() =
        runTest {
            val engine = MockEngine { jsonResponse(chernobylSeasonJson) }
            var sleeps = 0
            val client =
                TmdbClient(
                    createHttpClient(engine),
                    credentialProvider = { jwt },
                    pacer =
                        RequestPacer(
                            minInterval = 50.milliseconds,
                            clock = FrozenClock,
                            sleep = { sleeps++ },
                        ),
                )

            client.seasonDetails(1396, 1)
            client.seasonDetails(1396, 2)
            client.seasonDetails(1396, 3)

            assertEquals(3, engine.requestHistory.size)
            assertEquals(
                engine.requestHistory.size - 1,
                sleeps,
                "every request after the first must be paced",
            )
        }

    @Test
    fun noPacerMeansNoWaiting() =
        runTest {
            val engine = MockEngine { jsonResponse(chernobylSeasonJson) }
            val client = TmdbClient(createHttpClient(engine), credentialProvider = { jwt })

            client.seasonDetails(1396, 1)
            client.seasonDetails(1396, 2)

            assertEquals(2, engine.requestHistory.size, "the interactive default must still issue requests")
        }

    /**
     * The credential is read on every request, not captured once. This client is built at
     * AppContainer construction and outlives every Settings visit, so a captured value would keep
     * sending a credential the user had just cleared.
     */
    @Test
    fun theCredentialIsReadPerRequestNotCapturedAtConstruction() =
        runTest {
            val engine = MockEngine { jsonResponse(breakingBadJson) }
            var current: String? = jwt
            val client = TmdbClient(createHttpClient(engine), credentialProvider = { current })

            assertIs<Resource.Success<TmdbShowDetailsDto>>(client.showDetails(1396))
            current = null
            val afterClearing = client.showDetails(1396)

            assertIs<Resource.Error>(afterClearing)
            assertEquals(1, engine.requestHistory.size, "the second call must not have been sent")
        }
}
