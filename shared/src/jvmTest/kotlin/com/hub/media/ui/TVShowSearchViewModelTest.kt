package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.AiringStatus
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.network.createHttpClient
import com.hub.media.features.tv.data.TVShowRepository
import com.hub.media.features.tv.network.TmdbClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TVShowSearchViewModel] against a real in-memory [AppDatabase] and a [MockEngine]-backed
 * [TmdbClient], mirroring [AddTVShowViewModelTest]: both dependencies are concrete classes with no
 * seam to fake, so the test supplies real ones over controlled inputs.
 *
 * Room-backed, so it lives in `jvmTest` (#81) -- `:shared:jvmTest` is the authoritative gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TVShowSearchViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: TVShowRepository
    private val viewModels = ViewModelRegistry()

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
        db = testAppDatabase()
        repository = TVShowRepository(db)
    }

    @AfterTest
    fun tearDown() {
        viewModels.clearAll()
        db.close()
    }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    /** Answers `/search/tv` and `/tv/{id}` from the two bodies given, and 404s anything else. */
    private fun viewModel(
        searchBody: String = EMPTY_SEARCH,
        showBody: String = CHERNOBYL,
        showStatus: HttpStatusCode = HttpStatusCode.OK,
    ): TVShowSearchViewModel {
        val engine =
            MockEngine { request ->
                when {
                    request.url.encodedPath.startsWith("/3/search/tv") ->
                        respond(searchBody, HttpStatusCode.OK, jsonHeaders())
                    request.url.encodedPath.startsWith("/3/tv/") ->
                        if (showStatus == HttpStatusCode.OK) {
                            respond(showBody, HttpStatusCode.OK, jsonHeaders())
                        } else {
                            respondError(showStatus)
                        }
                    else -> respondError(HttpStatusCode.NotFound)
                }
            }
        return viewModels.track(
            TVShowSearchViewModel(
                tmdbClient = TmdbClient(createHttpClient(engine), credentialProvider = { TOKEN }),
                tvShowRepository = repository,
            ),
        )
    }

    // ---- searching -----------------------------------------------------------------------------

    @Test
    fun search_populatesResults() =
        runTest {
            val vm = viewModel(searchBody = CHERNOBYL_SEARCH)
            vm.onQueryChange("chernobyl")
            vm.search()

            val state = vm.uiState.first { !it.isSearching && it.hasSearched }
            assertEquals(1, state.results.size)
            val hit = state.results.single()
            assertEquals(87108, hit.tmdbId)
            assertEquals("Chernobyl", hit.title)
            assertEquals("2019", hit.year, "the year is derived once here, not in the composable")
            assertNull(state.searchError)
        }

    @Test
    fun search_blankQuery_doesNotSearchAtAll() =
        runTest {
            val vm = viewModel()
            vm.onQueryChange("   ")
            vm.search()

            // hasSearched stays false: showing "nothing matched" for a question the user never asked
            // is worse than doing nothing.
            assertFalse(vm.uiState.value.hasSearched)
            assertFalse(vm.uiState.value.isSearching)
        }

    @Test
    fun search_resultWithNoUsableName_isDroppedRatherThanShownUntitled() =
        runTest {
            // An untitled row cannot be added -- addShow rejects a blank title -- so offering it
            // would be offering a tap that always fails.
            val vm = viewModel(searchBody = SEARCH_WITH_NAMELESS_HIT)
            vm.onQueryChange("anything")
            vm.search()

            val state = vm.uiState.first { it.hasSearched }
            assertEquals(listOf("Real Show"), state.results.map { it.title })
        }

    @Test
    fun search_failure_reportsItAndClearsResults() =
        runTest {
            val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
            val vm =
                viewModels.track(
                    TVShowSearchViewModel(
                        TmdbClient(createHttpClient(engine), credentialProvider = { TOKEN }),
                        repository,
                    ),
                )
            vm.onQueryChange("chernobyl")
            vm.search()

            val state = vm.uiState.first { it.hasSearched }
            assertTrue(state.results.isEmpty())
            assertTrue(
                state.searchError?.contains("credential", ignoreCase = true) == true,
                "a 401 is a credential problem and must say so: ${state.searchError}",
            )
        }

    @Test
    fun search_withNoCredential_reportsTheSettingsSentenceRatherThanFailingSilently() =
        runTest {
            val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
            val vm =
                viewModels.track(
                    TVShowSearchViewModel(
                        TmdbClient(createHttpClient(engine), credentialProvider = { null }),
                        repository,
                    ),
                )
            vm.onQueryChange("chernobyl")
            vm.search()

            val state = vm.uiState.first { it.hasSearched }
            assertTrue(
                state.searchError?.contains("Settings") == true,
                "the app stays usable without a credential, so the screen must name the remedy: " +
                    "${state.searchError}",
            )
        }

    // ---- adding --------------------------------------------------------------------------------

    @Test
    fun addShow_writesTheShowItsEpisodesAndItsProviderId() =
        runTest {
            val vm = viewModel(showBody = CHERNOBYL)
            vm.addShow(87108)

            val state = vm.uiState.first { it.savedMediaId != null }
            val mediaId = state.savedMediaId!!
            assertNull(state.addError)
            assertNull(state.addingTmdbId, "the row must stop showing progress once it is done")

            val item = db.mediaItemDao().getById(mediaId)
            assertEquals("Chernobyl", item?.title)
            assertEquals(2019, item?.releaseYear)

            val details = db.tvDetailsDao().getByMediaId(mediaId)
            assertEquals(AiringStatus.ENDED, details?.airingStatus)
            assertEquals(1, details?.totalSeasons)

            val episodes = db.episodeDao().getByMediaId(mediaId)
            assertEquals(2, episodes.size)
            assertEquals("1:23:45", episodes.first { it.episodeNumber == 1 }.title)
            assertTrue(episodes.all { it.watchedAt == null }, "a show just added has been watched by nobody")

            assertEquals(
                "87108",
                db.externalIdentifierDao().getByKey(mediaId, IdentifierProvider.TMDB)?.externalId,
                "without this the backfill cannot match the row back to its TMDB record",
            )
        }

    @Test
    fun addShow_fetchFailure_reportsItAndWritesNothing() =
        runTest {
            val vm = viewModel(showStatus = HttpStatusCode.InternalServerError)
            vm.addShow(87108)

            val state = vm.uiState.first { it.addError != null }
            assertNull(state.savedMediaId)
            assertNull(state.addingTmdbId)
            assertTrue(
                db
                    .mediaItemDao()
                    .observeAll()
                    .first()
                    .isEmpty(),
                "a failed lookup must leave no half-made show",
            )
        }

    @Test
    fun addShow_recordWithoutATitle_isReportedRatherThanSilentlySkipped() =
        runTest {
            // The user tapped a row and is owed an answer, even though nothing could be created.
            val vm = viewModel(showBody = SHOW_WITHOUT_NAME)
            vm.addShow(87108)

            val state = vm.uiState.first { it.addError != null }
            assertTrue(state.addError!!.contains("title", ignoreCase = true))
            assertNull(state.savedMediaId)
        }

    @Test
    fun addShow_whileOneIsAlreadyInFlight_isRefused() =
        runTest {
            // Two taps would otherwise produce two shows.
            val vm = viewModel(showBody = CHERNOBYL)
            vm.addShow(87108)
            vm.addShow(87108)

            vm.uiState.first { it.savedMediaId != null }
            assertEquals(
                1,
                db
                    .mediaItemDao()
                    .observeAll()
                    .first()
                    .size,
            )
        }

    @Test
    fun reset_clearsTheSavedIdSoReturningDoesNotNavigateAgain() =
        runTest {
            val vm = viewModel(showBody = CHERNOBYL)
            vm.addShow(87108)
            vm.uiState.first { it.savedMediaId != null }

            vm.reset()

            assertNull(vm.uiState.value.savedMediaId)
        }

    private companion object {
        /** Shape only -- `TmdbCredential.of` classifies on the `eyJ` prefix; nothing here is real. */
        const val TOKEN = "eyJ-fake-tmdb-read-access-token-for-tests"

        const val EMPTY_SEARCH = """{"page":1,"results":[],"total_pages":0,"total_results":0}"""

        const val CHERNOBYL_SEARCH = """
            {"page":1,"results":[
              {"id":87108,"name":"Chernobyl","first_air_date":"2019-05-06",
               "overview":"The 1986 disaster.","poster_path":"/p.jpg","vote_average":8.7,"vote_count":4000}
            ],"total_pages":1,"total_results":1}
        """

        const val SEARCH_WITH_NAMELESS_HIT = """
            {"page":1,"results":[
              {"id":1,"name":"   ","first_air_date":"2019-05-06"},
              {"id":2,"name":"Real Show","first_air_date":"2020-01-01"}
            ]}
        """

        /**
         * Chernobyl's real shape: one season named "Miniseries" rather than "Season 1", and a first
         * episode titled `1:23:45`. Trimmed to the fields this app models.
         */
        const val CHERNOBYL = """
            {"id":87108,"name":"Chernobyl","overview":"The 1986 disaster.",
             "first_air_date":"2019-05-06","last_air_date":"2019-06-03",
             "number_of_seasons":1,"number_of_episodes":5,"poster_path":"/p.jpg",
             "status":"Ended","in_production":false,"vote_average":8.7,"vote_count":4000,
             "seasons":[{"season_number":1,"episode_count":2,"name":"Miniseries"}],
             "season/1":{"season_number":1,"name":"Miniseries","episodes":[
               {"episode_number":1,"season_number":1,"name":"1:23:45","air_date":"2019-05-06",
                "runtime":61,"vote_average":8.6,"vote_count":300},
               {"episode_number":2,"season_number":1,"name":"Please Remain Calm",
                "air_date":"2019-05-13","runtime":65,"vote_average":8.7,"vote_count":280}
             ]}}
        """

        const val SHOW_WITHOUT_NAME = """
            {"id":87108,"number_of_seasons":1,"status":"Ended","seasons":[]}
        """
    }
}
