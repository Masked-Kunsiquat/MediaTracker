package com.hub.media.features.media.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.MediaRepository
import com.hub.media.core.database.entities.AiringStatus
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.network.tmdbImagePacer
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.storage.cleanupTestTempDir
import com.hub.media.core.storage.createTestTempDir
import com.hub.media.core.util.Resource
import com.hub.media.features.books.network.CoverImageDownloader
import com.hub.media.features.movies.data.MovieRepository
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.getTmdbBackfillState
import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.data.TVShowRepository
import com.hub.media.features.tv.domain.BackfillShowEpisodesUseCase
import com.hub.media.features.tv.domain.FetchPosterUseCase
import com.hub.media.features.tv.network.TmdbClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
 * Covers [BulkTmdbBackfillUseCase] against a real in-memory [AppDatabase], real image storage and a
 * [MockEngine]-backed [TmdbClient].
 *
 * As with [com.hub.media.features.tv.domain.BackfillShowEpisodesUseCaseTest], the tests that matter
 * most are the ones asserting what this pass **cannot** do. It runs unattended over a library
 * someone has already been ticking off, so "it never overwrote anything" and "it never moved a watch
 * date" are properties worth failing a build over rather than trusting to review.
 */
class BulkTmdbBackfillUseCaseTest {
    private lateinit var db: AppDatabase
    private lateinit var tempDir: String
    private lateinit var storage: LocalImageStorageManager
    private lateinit var settings: SettingsRepository
    private lateinit var movies: MovieRepository
    private lateinit var shows: TVShowRepository

    @BeforeTest
    fun setUp() {
        db = testAppDatabase()
        tempDir = runBlocking { createTestTempDir() }
        storage = LocalImageStorageManager(tempDir)
        settings = SettingsRepository(db.appSettingsDao())
        movies = MovieRepository(db)
        shows = TVShowRepository(db)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        runBlocking { cleanupTestTempDir(tempDir) }
    }

    /** Every request this pass makes, in order, so a test can assert it spent exactly one per title. */
    private val requestedPaths = mutableListOf<String>()

    /**
     * A pass wired to a mock engine that answers by path: the credential check, the film record, the
     * show record, and the image CDN.
     *
     * @param credentialOk `false` makes `/authentication` answer 401, which is how a user with no
     *   usable key reaches this code.
     */
    private fun useCase(
        credentialOk: Boolean = true,
        showBody: String = CHERNOBYL,
    ): BulkTmdbBackfillUseCase {
        val engine =
            MockEngine { request ->
                val url = request.url
                requestedPaths += url.encodedPath
                when {
                    url.host == "image.tmdb.org" -> respond(byteArrayOf(1, 2, 3, 4))
                    url.encodedPath.endsWith("/authentication") ->
                        if (credentialOk) {
                            respond(
                                "{}",
                                HttpStatusCode.OK,
                                JSON,
                            )
                        } else {
                            respondError(HttpStatusCode.Unauthorized)
                        }
                    url.encodedPath.startsWith("/3/movie/") -> respond(MATRIX, HttpStatusCode.OK, JSON)
                    url.encodedPath.endsWith("/season/2") -> respond(LATE_SEASON, HttpStatusCode.OK, JSON)
                    url.encodedPath.startsWith("/3/tv/") -> respond(showBody, HttpStatusCode.OK, JSON)
                    else -> respondError(HttpStatusCode.NotFound)
                }
            }
        return useCaseWith(engine)
    }

    /** The wiring, given an engine — so a test needing its own responder does not restate all of it. */
    private fun useCaseWith(engine: MockEngine): BulkTmdbBackfillUseCase {
        val httpClient = createHttpClient(engine)
        val tmdbClient = TmdbClient(httpClient, credentialProvider = { TOKEN })
        return BulkTmdbBackfillUseCase(
            db = db,
            tmdbClient = tmdbClient,
            showEpisodes =
                BackfillShowEpisodesUseCase(
                    db = db,
                    tmdbClient = tmdbClient,
                    tvShowRepository = shows,
                ),
            fetchPoster =
                FetchPosterUseCase(
                    coverDownloader = CoverImageDownloader(httpClient),
                    imageStorage = storage,
                    mediaRepository = MediaRepository(db),
                    scope = CoroutineScope(Dispatchers.Default),
                ),
            // A real pacer with its sleep stubbed out: the pacing arithmetic still runs, but the
            // suite does not spend the intervals. Pacing itself is RequestPacer's own test.
            posterPacer = tmdbImagePacer(sleep = {}),
            settingsRepository = settings,
        )
    }

    /** A film with nothing but a title, mapped to TMDB 603 — the shape a CSV import leaves behind. */
    private suspend fun bareFilm(title: String = "The Matrix"): String {
        val result =
            movies.addMovie(
                title = title,
                externalIdentifiers = listOf(IdentifierProvider.TMDB to "603"),
            )
        assertIs<Resource.Success<String>>(result)
        return result.data
    }

    /** A quick-filled show holding one blank episode in each of two seasons, mapped to TMDB 87108. */
    private suspend fun twoSeasonShow(): String {
        val result =
            shows.addShow(
                title = "A Long Show",
                seasons =
                    listOf(
                        SeasonQuickFill(seasonNumber = 1, episodeCount = 1),
                        SeasonQuickFill(seasonNumber = 2, episodeCount = 1),
                    ),
                externalIdentifiers = listOf(IdentifierProvider.TMDB to "87108"),
            )
        assertIs<Resource.Success<String>>(result)
        return result.data
    }

    /** A quick-filled show with five blank episodes in season 1, mapped to TMDB 87108. */
    private suspend fun quickFilledShow(): String {
        val result =
            shows.addShow(
                title = "Chernobyl",
                seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 5)),
                externalIdentifiers = listOf(IdentifierProvider.TMDB to "87108"),
            )
        assertIs<Resource.Success<String>>(result)
        return result.data
    }

    // ---- what it fills ---------------------------------------------------------------------------

    @Test
    fun fillsAFilmsPosterYearRuntimeAndRatingFromOneRequest() =
        runTest {
            val mediaId = bareFilm()

            val progress = useCase().execute()

            assertEquals(1, progress.updated)
            assertEquals(0, progress.remaining)
            val item = assertNotNull(db.mediaItemDao().getById(mediaId))
            assertEquals(1999, item.releaseYear)
            assertEquals(8.2, item.communityRating)
            assertNotNull(item.coverImageHash, "the poster must be stored and recorded")
            assertEquals(136, db.movieDetailsDao().getByMediaId(mediaId)?.runtimeMinutes)

            // One metadata request and one poster download -- not one request per column, and not a
            // second lookup to discover the poster path the first response already carried.
            assertEquals(
                listOf("/3/authentication", "/3/movie/603", "/t/p/w500/matrix.jpg"),
                requestedPaths,
            )
        }

    @Test
    fun fillsAShowsEpisodesOwnColumnsAndPosterFromOneRequest() =
        runTest {
            val mediaId = quickFilledShow()

            val progress = useCase().execute()

            assertEquals(1, progress.updated)
            val episodes = db.episodeDao().getByMediaId(mediaId).associateBy { it.episodeNumber }
            assertEquals("1:23:45", episodes.getValue(1).title)
            assertEquals(61, episodes.getValue(1).runtimeMinutes)

            val details = assertNotNull(db.tvDetailsDao().getByMediaId(mediaId))
            assertEquals(AiringStatus.ENDED, details.airingStatus)
            assertEquals("An explosion at a nuclear plant.", details.overview)
            assertEquals(
                Instant.parse("2019-05-06T00:00:00Z").toEpochMilliseconds(),
                details.firstAirDate,
            )
            assertNotNull(db.mediaItemDao().getById(mediaId)?.coverImageHash)

            assertEquals(
                listOf("/3/authentication", "/3/tv/87108", "/t/p/w500/chernobyl.jpg"),
                requestedPaths,
                "a show's record, its seasons and its poster path all arrive in one response",
            )
        }

    // ---- what it must never do -------------------------------------------------------------------

    @Test
    fun neverOverwritesAValueThatIsAlreadyThere() =
        runTest {
            // A year and runtime the user corrected by hand, disagreeing with TMDB on both.
            val result =
                movies.addMovie(
                    title = "The Matrix",
                    releaseYear = 1998,
                    runtimeMinutes = 999,
                    externalIdentifiers = listOf(IdentifierProvider.TMDB to "603"),
                )
            assertIs<Resource.Success<String>>(result)
            val mediaId = result.data

            useCase().execute()

            assertEquals(1998, db.mediaItemDao().getById(mediaId)?.releaseYear, "a corrected year wins")
            assertEquals(999, db.movieDetailsDao().getByMediaId(mediaId)?.runtimeMinutes, "a corrected runtime wins")
            // The gap it *did* have is still filled -- the guarantee is per column, not per row.
            assertEquals(8.2, db.mediaItemDao().getById(mediaId)?.communityRating)
        }

    @Test
    fun neverChangesWhatTheLibrarySaysYouHaveWatched() =
        runTest {
            val mediaId = quickFilledShow()
            val episode = db.episodeDao().getByMediaId(mediaId).first { it.episodeNumber == 1 }
            val watchedAt = Instant.parse("2020-01-01T00:00:00Z")
            db.tvWriteDao().markEpisodeWatched(episode.id, watchedAt)
            shows.updateWatchStatus(mediaId, WatchStatus.ABANDONED)

            useCase().execute()

            val after = db.episodeDao().getByMediaId(mediaId).first { it.episodeNumber == 1 }
            assertEquals(watchedAt, after.watchedAt, "a watch date must survive a background pass")
            assertEquals("1:23:45", after.title, "and the row must still have been enriched")
            assertEquals(
                WatchStatus.ABANDONED,
                db.tvDetailsDao().getByMediaId(mediaId)?.status,
                "giving up on a show is a decision no pass may revisit",
            )
        }

    @Test
    fun neverCreatesEpisodeRowsTheProviderKnowsAboutAndTheLibraryDoesNot() =
        runTest {
            // Two local episodes against TMDB's five. Creating the missing three would move the
            // denominator and turn a finished show unfinished; #123 owns what to offer instead.
            val result =
                shows.addShow(
                    title = "Chernobyl",
                    seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 2)),
                    externalIdentifiers = listOf(IdentifierProvider.TMDB to "87108"),
                )
            assertIs<Resource.Success<String>>(result)

            useCase().execute()

            assertEquals(2, db.episodeDao().getByMediaId(result.data).size)
        }

    @Test
    fun countsAShowTmdbHasNothingForAsNothingToFillRatherThanUpdated() =
        runTest {
            // Blank columns at both ends. Entering the write on "this column is null" and then
            // reading the affected-row count would call this updated -- the count is 1 whenever the
            // row exists, not whenever a column changed.
            val mediaId = quickFilledShow()

            val progress = useCase(showBody = BARE_SHOW).execute()

            assertEquals(0, progress.updated, "nothing was written, so nothing was updated")
            assertEquals(1, progress.nothingToFill)
            assertNull(db.tvDetailsDao().getByMediaId(mediaId)?.overview, "and the column is still blank")
        }

    // ---- seasons beyond the append ceiling -------------------------------------------------------

    @Test
    fun fetchesASeasonTheShowResponseCouldNotCarry() =
        runTest {
            // LONG_SHOW declares seasons 1 and 2 but appends only season 1 -- the shape a show past
            // MAX_APPENDED_SEASONS comes back in. Season 2's episodes are held locally, so leaving
            // them would mean reporting this show complete every run while they stayed blank.
            val mediaId = twoSeasonShow()

            val progress = useCase(showBody = LONG_SHOW).execute()

            assertEquals(1, progress.updated)
            val episodes = db.episodeDao().getByMediaId(mediaId).associateBy { it.seasonNumber to it.episodeNumber }
            assertEquals("Opening", episodes.getValue(1 to 1).title, "the appended season still fills")
            assertEquals("Late Arrival", episodes.getValue(2 to 1).title, "and so does the one that was not")
            assertTrue(
                requestedPaths.contains("/3/tv/87108/season/2"),
                "the missing season costs its own request: $requestedPaths",
            )
            assertTrue(
                requestedPaths.none { it.endsWith("/season/1") },
                "the season that did arrive must not be re-fetched: $requestedPaths",
            )
        }

    @Test
    fun defersTheTitleWhenAMissingSeasonCannotBeFetched() =
        runTest {
            val mediaId = twoSeasonShow()
            val engine =
                MockEngine { request ->
                    requestedPaths += request.url.encodedPath
                    when {
                        request.url.encodedPath.endsWith("/authentication") -> respond("{}", HttpStatusCode.OK, JSON)
                        request.url.encodedPath.endsWith(
                            "/season/2",
                        ) -> respondError(HttpStatusCode.InternalServerError)
                        else -> respond(LONG_SHOW, HttpStatusCode.OK, JSON)
                    }
                }
            val progress = useCaseWith(engine).execute()

            assertEquals(1, progress.remaining, "a show with episodes still unfilled must not be reported done")
            assertNull(
                db
                    .episodeDao()
                    .getByMediaId(mediaId)
                    .first { it.seasonNumber == 2 }
                    .title,
                "season 2 is genuinely still blank",
            )
        }

    // ---- candidate selection ---------------------------------------------------------------------

    @Test
    fun reportsTitlesWithNoTmdbIdRatherThanQueueingThemForever() =
        runTest {
            val handEntered = movies.addMovie(title = "A Film Someone Typed In")
            assertIs<Resource.Success<String>>(handEntered)

            val progress = useCase().execute()

            assertEquals(1, progress.noTmdbIdSkipped)
            assertEquals(0, progress.totalCandidates, "a hand-entered film is not a candidate")
            assertTrue(
                requestedPaths.none { it.startsWith("/3/movie/") },
                "there is nothing to ask about a film that came from nowhere",
            )
        }

    @Test
    fun spendsNoRequestOnATitleThatIsAlreadyComplete() =
        runTest {
            val result =
                movies.addMovie(
                    title = "The Matrix",
                    releaseYear = 1999,
                    runtimeMinutes = 136,
                    communityRating = 8.2,
                    coverImageHash = "already-stored",
                    externalIdentifiers = listOf(IdentifierProvider.TMDB to "603"),
                )
            assertIs<Resource.Success<String>>(result)

            val progress = useCase().execute()

            assertEquals(0, progress.totalCandidates)
            assertTrue(requestedPaths.isEmpty(), "a complete library must not even check the credential")
        }

    // ---- stopping and resuming -------------------------------------------------------------------

    @Test
    fun stopsWithTmdbsOwnSentenceWhenTheCredentialIsRefused() =
        runTest {
            val mediaId = bareFilm()

            val progress = useCase(credentialOk = false).execute()

            assertTrue(progress.isBlocked)
            assertNotNull(progress.blockedMessage)
            assertEquals(1, progress.remaining, "nothing was processed, so everything is still pending")
            assertNull(
                db.mediaItemDao().getById(mediaId)?.releaseYear,
                "a blocked run must not have written anything",
            )
            assertEquals(
                listOf("/3/authentication"),
                requestedPaths,
                "one request establishes the answer, rather than one failure per title",
            )
        }

    @Test
    fun clearsItsResumeStateOnceEverythingIsResolved() =
        runTest {
            bareFilm()

            val progress = useCase().execute()

            assertTrue(progress.isComplete)
            assertNull(settings.getTmdbBackfillState(), "a resolved run leaves nothing to resume")
        }

    @Test
    fun keepsResumeStateWhenATitleCouldNotBeResolved() =
        runTest {
            bareFilm()
            // The film record answers, the poster download does not -- a transient failure, so the
            // title stays queued rather than being reported as done.
            val engine =
                MockEngine { request ->
                    when {
                        request.url.host == "image.tmdb.org" -> respondError(HttpStatusCode.InternalServerError)
                        request.url.encodedPath.endsWith("/authentication") -> respond("{}", HttpStatusCode.OK, JSON)
                        else -> respond(MATRIX, HttpStatusCode.OK, JSON)
                    }
                }
            val httpClient = createHttpClient(engine)
            val tmdbClient = TmdbClient(httpClient, credentialProvider = { TOKEN })
            val useCase =
                BulkTmdbBackfillUseCase(
                    db = db,
                    tmdbClient = tmdbClient,
                    showEpisodes = BackfillShowEpisodesUseCase(db, tmdbClient, shows),
                    fetchPoster =
                        FetchPosterUseCase(
                            coverDownloader = CoverImageDownloader(httpClient),
                            imageStorage = storage,
                            mediaRepository = MediaRepository(db),
                            scope = CoroutineScope(Dispatchers.Default),
                        ),
                    posterPacer = tmdbImagePacer(sleep = {}),
                    settingsRepository = settings,
                )

            val progress = useCase.execute()

            assertEquals(1, progress.remaining)
            val state = assertNotNull(settings.getTmdbBackfillState(), "a run with work left must be resumable")
            assertEquals(1, state.pendingMediaIds.size)
        }

    @Test
    fun reportsProgressAfterEveryTitleRatherThanOnlyAtTheEnd() =
        runTest {
            bareFilm("The Matrix")
            bareFilm("The Matrix Reloaded")

            val seen = mutableListOf<Int>()
            useCase().execute { seen += it.processed }

            assertEquals(listOf(1, 2), seen, "a checkpoint per title is what makes a run resumable")
        }

    private companion object {
        const val TOKEN = "eyJ-fake-tmdb-read-access-token-for-tests"
        val JSON = headersOf(HttpHeaders.ContentType, "application/json")

        const val MATRIX = """
            {"id":603,"title":"The Matrix","release_date":"1999-03-30","runtime":136,
             "poster_path":"/matrix.jpg","vote_average":8.2,"vote_count":24000,
             "overview":"A hacker learns the truth."}
        """

        /**
         * A show declaring two seasons but appending only the first — what a show past
         * `MAX_APPENDED_SEASONS` comes back as, without needing a 21-season fixture to provoke it.
         */
        const val LONG_SHOW = """
            {"id":87108,"name":"A Long Show","number_of_seasons":2,"number_of_episodes":2,
             "status":"Ended","in_production":false,"poster_path":"/long.jpg",
             "first_air_date":"2019-05-06","last_air_date":"2020-06-03",
             "overview":"A show with more seasons than one response carries.",
             "vote_average":8.0,"vote_count":100,
             "seasons":[{"season_number":1,"episode_count":1,"name":"Season 1"},
                        {"season_number":2,"episode_count":1,"name":"Season 2"}],
             "season/1":{"season_number":1,"name":"Season 1","episodes":[
               {"episode_number":1,"season_number":1,"name":"Opening","air_date":"2019-05-06",
                "runtime":61,"vote_average":8.6,"vote_count":300,"overview":"It begins."}
             ]}}
        """

        /** The follow-up `/season/2` payload the pass has to go back for. */
        const val LATE_SEASON = """
            {"season_number":2,"name":"Season 2","episodes":[
              {"episode_number":1,"season_number":2,"name":"Late Arrival","air_date":"2020-06-03",
               "runtime":55,"vote_average":8.1,"vote_count":120,"overview":"It continues."}
            ]}
        """

        /** A show TMDB has nothing extra for: every field this pass could fill comes back null. */
        const val BARE_SHOW = """
            {"id":87108,"name":"Chernobyl","seasons":[],"season/1":null}
        """

        /** Chernobyl's real shape, with the show-level fields #140 added filling for. */
        const val CHERNOBYL = """
            {"id":87108,"name":"Chernobyl","number_of_seasons":1,"number_of_episodes":5,
             "status":"Ended","in_production":false,"poster_path":"/chernobyl.jpg",
             "first_air_date":"2019-05-06","last_air_date":"2019-06-03",
             "overview":"An explosion at a nuclear plant.",
             "vote_average":8.7,"vote_count":4000,
             "seasons":[{"season_number":1,"episode_count":5,"name":"Miniseries"}],
             "season/1":{"season_number":1,"name":"Miniseries","episodes":[
               {"episode_number":1,"season_number":1,"name":"1:23:45","air_date":"2019-05-06",
                "runtime":61,"vote_average":8.6,"vote_count":300,"overview":"An explosion."},
               {"episode_number":2,"season_number":1,"name":"Please Remain Calm",
                "air_date":"2019-05-13","runtime":65,"vote_average":8.7,"vote_count":280,
                "overview":"The fire burns."},
               {"episode_number":3,"season_number":1,"name":"Open Wide, O Earth",
                "air_date":"2019-05-20","runtime":72,"vote_average":8.8,"vote_count":270,
                "overview":"The city empties."},
               {"episode_number":4,"season_number":1,"name":"The Happiness of All Mankind",
                "air_date":"2019-05-27","runtime":72,"vote_average":8.8,"vote_count":260,
                "overview":"The liquidators arrive."},
               {"episode_number":5,"season_number":1,"name":"Vichnaya Pamyat",
                "air_date":"2019-06-03","runtime":72,"vote_average":9.1,"vote_count":300,
                "overview":"The trial."}
             ]}}
        """
    }
}
