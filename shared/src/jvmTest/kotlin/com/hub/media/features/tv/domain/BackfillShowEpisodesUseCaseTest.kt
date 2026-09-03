package com.hub.media.features.tv.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.util.Resource
import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.data.TVShowRepository
import com.hub.media.features.tv.network.TmdbClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Covers [BackfillShowEpisodesUseCase] against a real in-memory [AppDatabase] and a
 * [MockEngine]-backed [TmdbClient].
 *
 * The tests that matter most here are the ones asserting what the pass **cannot** do. #75 settled
 * that a background pass must never change what the library says you have watched, and that
 * guarantee is worth failing a build over rather than trusting to review.
 */
class BackfillShowEpisodesUseCaseTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: TVShowRepository

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        repo = TVShowRepository(db)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun useCase(
        body: String = CHERNOBYL,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): BackfillShowEpisodesUseCase {
        val engine =
            MockEngine {
                if (status == HttpStatusCode.OK) {
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    respondError(status)
                }
            }
        return BackfillShowEpisodesUseCase(
            db = db,
            tmdbClient = TmdbClient(createHttpClient(engine), credentialProvider = { TOKEN }),
            tvShowRepository = repo,
        )
    }

    /** A quick-filled show with [episodeCount] blank episodes in season 1, mapped to TMDB 87108. */
    private suspend fun quickFilledShow(episodeCount: Int = 5): String {
        val result =
            repo.addShow(
                title = "Chernobyl",
                seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = episodeCount)),
                externalIdentifiers = listOf(IdentifierProvider.TMDB to "87108"),
            )
        assertIs<Resource.Success<String>>(result)
        return result.data
    }

    // ---- what it fills -------------------------------------------------------------------------

    @Test
    fun fillsTitlesAndMetadataOntoRowsThatAlreadyExist() =
        runTest {
            val mediaId = quickFilledShow()

            val result = useCase().execute(mediaId)

            assertIs<Resource.Success<EpisodeBackfillReport>>(result)
            assertEquals(5, result.data.episodesFilled)

            val episodes = db.episodeDao().getByMediaId(mediaId).associateBy { it.episodeNumber }
            assertEquals("1:23:45", episodes.getValue(1).title)
            assertEquals(Instant.parse("2019-05-06T00:00:00Z"), episodes.getValue(1).airDate)
            assertEquals(61, episodes.getValue(1).runtimeMinutes)
            assertEquals(8.6, episodes.getValue(1).communityRating)
            assertEquals("Please Remain Calm", episodes.getValue(2).title)
        }

    @Test
    fun theRowsAreTheSameRowsRatherThanRecreatedOnes() =
        runTest {
            // The entity's KDoc is explicit that Phase D must fill in place: a quick-filled episode
            // is a normal row, not a placeholder, and deleting one destroys the watchedAt that is
            // the whole point of it existing.
            val mediaId = quickFilledShow()
            val idsBefore =
                db
                    .episodeDao()
                    .getByMediaId(mediaId)
                    .map { it.id }
                    .toSet()

            useCase().execute(mediaId)

            assertEquals(
                idsBefore,
                db
                    .episodeDao()
                    .getByMediaId(mediaId)
                    .map { it.id }
                    .toSet(),
            )
        }

    // ---- what it cannot do ---------------------------------------------------------------------

    @Test
    fun aWatchedEpisodeKeepsItsWatchedDate() =
        runTest {
            // The guarantee this whole design exists for. watchedAt is not in the update's SET list
            // at all, so this is enforced by the statement rather than by the caller being careful.
            val mediaId = quickFilledShow()
            val first = db.episodeDao().getByMediaId(mediaId).first { it.episodeNumber == 1 }
            repo.setEpisodeWatched(first.id, true)
            val watchedAt = db.episodeDao().getById(first.id)?.watchedAt
            assertNotNull(watchedAt)

            useCase().execute(mediaId)

            assertEquals(
                watchedAt,
                db.episodeDao().getById(first.id)?.watchedAt,
                "a background pass must never change what the library says you have watched",
            )
        }

    @Test
    fun aTitleTheUserAlreadyHasIsNotOverwritten() =
        runTest {
            // COALESCE keeps what is there. A correction the user typed survives every later pass.
            val mediaId = quickFilledShow()
            val first = db.episodeDao().getByMediaId(mediaId).first { it.episodeNumber == 1 }
            db.tvWriteDao().fillEpisodeMetadata(
                mediaId = mediaId,
                seasonNumber = 1,
                episodeNumber = 1,
                title = "A Title I Typed Myself",
                airDate = null,
                runtimeMinutes = null,
                overview = null,
                communityRating = null,
            )

            useCase().execute(mediaId)

            assertEquals("A Title I Typed Myself", db.episodeDao().getById(first.id)?.title)
            assertEquals(
                Instant.parse("2019-05-06T00:00:00Z"),
                db.episodeDao().getById(first.id)?.airDate,
                "the columns that were still empty are filled even when a sibling column was not",
            )
        }

    @Test
    fun anEpisodeTheLibraryDoesNotHoldIsNotCreated() =
        runTest {
            // TMDB describes five; the user quick-filled three. The pass must report, never create --
            // creating moves the denominator and can turn a finished show unfinished.
            val mediaId = quickFilledShow(episodeCount = 3)

            val result = useCase().execute(mediaId)

            assertIs<Resource.Success<EpisodeBackfillReport>>(result)
            assertEquals(3, db.episodeDao().getByMediaId(mediaId).size, "no row may be created")
            assertEquals(
                listOf(SeasonCountMismatch(seasonNumber = 1, localEpisodes = 3, providerEpisodes = 5)),
                result.data.mismatches,
            )
        }

    @Test
    fun anExtraLocalEpisodeIsReportedAndKept() =
        runTest {
            // The other direction: the user quick-filled more than the provider lists. Deleting one
            // would destroy its watch date, so it is reported and left alone.
            val mediaId = quickFilledShow(episodeCount = 7)

            val result = useCase().execute(mediaId)

            assertIs<Resource.Success<EpisodeBackfillReport>>(result)
            assertEquals(7, db.episodeDao().getByMediaId(mediaId).size)
            assertEquals(
                listOf(SeasonCountMismatch(seasonNumber = 1, localEpisodes = 7, providerEpisodes = 5)),
                result.data.mismatches,
            )
            assertEquals(5, result.data.episodesFilled, "only the five the provider described")
        }

    @Test
    fun handEnteredSpecialsAreLeftAloneAndNotReportedAsAMismatch() =
        runTest {
            // #88: TMDB's own totals exclude season 0, and showWithSeasons never requests it. A pass
            // that compared specials would report a phantom mismatch on every show where the user
            // tracks any -- the app looking wrong when it is right.
            val mediaId = quickFilledShow()
            repo.setSeasonLength(mediaId, seasonNumber = 0, episodeCount = 2)
            val specials = db.episodeDao().getByMediaIdAndSeason(mediaId, 0)
            assertEquals(2, specials.size)

            val result = useCase().execute(mediaId)

            assertIs<Resource.Success<EpisodeBackfillReport>>(result)
            assertTrue(
                result.data.mismatches.none { it.seasonNumber == 0 },
                "specials must not be compared: ${result.data.mismatches}",
            )
            assertTrue(
                db.episodeDao().getByMediaIdAndSeason(mediaId, 0).all { it.title == null },
                "and they must be left untouched",
            )
        }

    // ---- failure paths -------------------------------------------------------------------------

    @Test
    fun aShowWithNoTmdbMappingIsRefusedRatherThanGuessedAt() =
        runTest {
            val result = repo.addShow(title = "Typed In By Hand", seasons = listOf(SeasonQuickFill(1, 3)))
            assertIs<Resource.Success<String>>(result)

            val backfill = useCase().execute(result.data)

            assertIs<Resource.Error>(backfill)
            assertTrue(backfill.message.contains("TMDB", ignoreCase = true))
        }

    @Test
    fun aNonNumericStoredIdIsRefusedWithoutSpendingARequest() =
        runTest {
            // Reachable rather than theoretical: the CSV importer validates the *provider* against
            // the enum but accepts any non-blank string as the id, so "TMDB:not-a-number" imports
            // cleanly. Coercing it would spend a request on /tv/-1 and report a 404 -- an answer
            // describing neither the cause nor the remedy.
            val show =
                repo.addShow(
                    title = "Imported With A Bad Id",
                    seasons = listOf(SeasonQuickFill(1, 3)),
                    externalIdentifiers = listOf(IdentifierProvider.TMDB to "not-a-number"),
                )
            assertIs<Resource.Success<String>>(show)

            var requests = 0
            val engine =
                MockEngine {
                    requests++
                    respond(CHERNOBYL, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val backfill =
                BackfillShowEpisodesUseCase(
                    db = db,
                    tmdbClient = TmdbClient(createHttpClient(engine), credentialProvider = { TOKEN }),
                    tvShowRepository = repo,
                )

            val result = backfill.execute(show.data)

            assertIs<Resource.Error>(result)
            assertEquals(0, requests, "a malformed id must not cost a request")
            assertTrue(result.message.contains("not a number"))
        }

    @Test
    fun aFailedRequestChangesNothing() =
        runTest {
            val mediaId = quickFilledShow()

            val result = useCase(status = HttpStatusCode.InternalServerError).execute(mediaId)

            assertIs<Resource.Error>(result)
            assertTrue(
                db.episodeDao().getByMediaId(mediaId).all { it.title == null },
                "a failed pass must leave the rows exactly as they were",
            )
        }

    @Test
    fun aShowWithNothingLeftToFillSucceedsWithAnEmptyReport() =
        runTest {
            // Complete metadata is not a failure. Running twice must be safe and quiet.
            val mediaId = quickFilledShow()
            useCase().execute(mediaId)

            val second = useCase().execute(mediaId)

            assertIs<Resource.Success<EpisodeBackfillReport>>(second)
            assertTrue(second.data.mismatches.isEmpty())
            assertNull(db.episodeDao().getByMediaId(mediaId).firstOrNull { it.title == null })
        }

    private companion object {
        const val TOKEN = "eyJ-fake-tmdb-read-access-token-for-tests"

        /** Chernobyl's real shape: five episodes, the first titled with colons. */
        const val CHERNOBYL = """
            {"id":87108,"name":"Chernobyl","number_of_seasons":1,"number_of_episodes":5,
             "status":"Ended","in_production":false,
             "seasons":[{"season_number":1,"episode_count":5,"name":"Miniseries"}],
             "season/1":{"season_number":1,"name":"Miniseries","episodes":[
               {"episode_number":1,"season_number":1,"name":"1:23:45","air_date":"2019-05-06",
                "runtime":61,"vote_average":8.6,"vote_count":300,"overview":"An explosion."},
               {"episode_number":2,"season_number":1,"name":"Please Remain Calm",
                "air_date":"2019-05-13","runtime":65,"vote_average":8.7,"vote_count":280},
               {"episode_number":3,"season_number":1,"name":"Open Wide, O Earth",
                "air_date":"2019-05-20","runtime":72,"vote_average":8.8,"vote_count":270},
               {"episode_number":4,"season_number":1,"name":"The Happiness of All Mankind",
                "air_date":"2019-05-27","runtime":72,"vote_average":8.8,"vote_count":260},
               {"episode_number":5,"season_number":1,"name":"Vichnaya Pamyat",
                "air_date":"2019-06-03","runtime":72,"vote_average":9.1,"vote_count":300}
             ]}}
        """
    }
}
