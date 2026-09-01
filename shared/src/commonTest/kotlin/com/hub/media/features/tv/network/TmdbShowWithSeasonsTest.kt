package com.hub.media.features.tv.network

import com.hub.media.core.network.createHttpClient
import com.hub.media.core.util.Resource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `append_to_response`, which is what makes adding a show one round trip instead of `1 + n`.
 *
 * The interesting cases are all about what TMDB *omits* rather than what it returns: seasons that do
 * not exist, seasons beyond the append ceiling, and a season whose payload will not decode. Each is
 * silent in the response, so each is asserted here.
 */
class TmdbShowWithSeasonsTest {
    private val jwt = "eyJ-fake-tmdb-read-access-token-for-tests"

    private fun engineReturning(json: String) =
        MockEngine {
            respond(
                content = json,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

    private fun client(engine: MockEngine) = TmdbClient(createHttpClient(engine), credentialProvider = { jwt })

    /** Two declared seasons, both appended -- the ordinary case. */
    private val twoSeasons =
        """
        {
          "id": 1396, "name": "Breaking Bad", "number_of_seasons": 2, "number_of_episodes": 20,
          "seasons": [
            {"season_number": 0, "episode_count": 9, "name": "Specials"},
            {"season_number": 1, "episode_count": 7, "name": "Season 1"},
            {"season_number": 2, "episode_count": 13, "name": "Season 2"}
          ],
          "season/1": {
            "season_number": 1, "name": "Season 1",
            "episodes": [{"episode_number": 1, "name": "Pilot", "air_date": "2008-01-20", "runtime": 58}]
          },
          "season/2": {
            "season_number": 2, "name": "Season 2",
            "episodes": [{"episode_number": 1, "name": "Seven Thirty-Seven", "air_date": "2009-03-08"}]
          }
        }
        """.trimIndent()

    @Test
    fun oneRequestCarriesTheShowAndEveryAppendedSeason() =
        runTest {
            val engine = engineReturning(twoSeasons)

            val result = client(engine).showWithSeasons(1396)

            assertIs<Resource.Success<TmdbShowWithSeasons>>(result)
            assertEquals(1, engine.requestHistory.size, "the whole point is that this is one round trip")
            assertEquals("Breaking Bad", result.data.show.name)
            assertEquals(setOf(1, 2), result.data.seasons.keys)
            val firstEpisode =
                result.data.seasons
                    .getValue(1)
                    .episodes
                    .single()
            assertEquals("Pilot", firstEpisode.name)
            assertEquals(58, firstEpisode.runtime)
        }

    /**
     * Season 0 is declared by the show but never requested, so it must not appear among the seasons
     * and must not be reported missing either -- it was never wanted. #75 creates regular seasons
     * only; #122 revisits that.
     */
    @Test
    fun specialsAreNeitherFetchedNorReportedMissing() =
        runTest {
            val result = client(engineReturning(twoSeasons)).showWithSeasons(1396)

            assertIs<Resource.Success<TmdbShowWithSeasons>>(result)
            assertTrue(0 !in result.data.seasons.keys, "season 0 must not be fetched")
            assertTrue(0 !in result.data.missingSeasonNumbers, "season 0 is not missing; it was never asked for")
            assertEquals(emptyList(), result.data.missingSeasonNumbers)
        }

    /** The blind request asks for 20 seasons; a one-season show simply comes back with one. */
    @Test
    fun requestingSeasonsThatDoNotExistIsHarmless() =
        runTest {
            val oneSeason =
                """
                {
                  "id": 87108, "name": "Chernobyl", "number_of_seasons": 1,
                  "seasons": [{"season_number": 1, "episode_count": 5, "name": "Miniseries"}],
                  "season/1": {
                    "season_number": 1, "name": "Miniseries",
                    "episodes": [{"episode_number": 1, "name": "1:23:45", "air_date": "2019-05-06"}]
                  }
                }
                """.trimIndent()

            val result = client(engineReturning(oneSeason)).showWithSeasons(87108)

            assertIs<Resource.Success<TmdbShowWithSeasons>>(result)
            assertEquals(setOf(1), result.data.seasons.keys)
            assertEquals(emptyList(), result.data.missingSeasonNumbers)
        }

    /**
     * The append ceiling, which is the case a caller must not have to detect for itself: TMDB
     * returns 200 with the extra seasons simply absent, so nothing about the response says it is
     * incomplete.
     */
    @Test
    fun seasonsBeyondTheAppendCeilingAreReportedMissing() =
        runTest {
            val declared = (1..22).joinToString(",") { """{"season_number": $it, "episode_count": 10}""" }
            val appended =
                (1..MAX_APPENDED_SEASONS).joinToString(",") {
                    """"season/$it": {"season_number": $it, "episodes": []}"""
                }
            val bigShow =
                """{"id": 1, "name": "Long Runner", "number_of_seasons": 22, """ +
                    """"seasons": [$declared], $appended}"""

            val result = client(engineReturning(bigShow)).showWithSeasons(1)

            assertIs<Resource.Success<TmdbShowWithSeasons>>(result)
            assertEquals(MAX_APPENDED_SEASONS, result.data.seasons.size)
            assertEquals(
                listOf(21, 22),
                result.data.missingSeasonNumbers,
                "seasons past the ceiling must be named, not left for the caller to work out",
            )
        }

    /**
     * One unreadable season must not cost the whole show. Trading a usable show plus one gap for a
     * total failure is the wrong direction, and the gap is reported rather than hidden.
     */
    @Test
    fun aSeasonThatCannotBeDecodedIsDroppedRatherThanFailingTheShow() =
        runTest {
            val brokenSeason =
                """
                {
                  "id": 1396, "name": "Breaking Bad", "number_of_seasons": 2,
                  "seasons": [{"season_number": 1, "episode_count": 7}, {"season_number": 2, "episode_count": 13}],
                  "season/1": {"season_number": 1, "episodes": [{"episode_number": 1, "name": "Pilot"}]},
                  "season/2": "this is not a season object"
                }
                """.trimIndent()

            val result = client(engineReturning(brokenSeason)).showWithSeasons(1396)

            assertIs<Resource.Success<TmdbShowWithSeasons>>(result)
            assertEquals(setOf(1), result.data.seasons.keys, "the good season must survive")
            assertEquals(listOf(2), result.data.missingSeasonNumbers, "the bad one must be reported")
        }

    @Test
    fun aFailedRequestStaysAFailure() =
        runTest {
            val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }

            val result = client(engine).showWithSeasons(1396)

            assertIs<Resource.Error>(result)
        }
}
