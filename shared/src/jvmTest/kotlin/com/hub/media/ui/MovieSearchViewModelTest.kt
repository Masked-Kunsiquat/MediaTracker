package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.MediaRepository
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.features.books.network.CoverImageDownloader
import com.hub.media.features.movies.data.MovieRepository
import com.hub.media.features.tv.data.TVShowRepository
import com.hub.media.features.tv.domain.FetchPosterUseCase
import com.hub.media.features.tv.network.TmdbClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [MovieSearchViewModel] against a real in-memory [AppDatabase] and a [MockEngine]-backed
 * [TmdbClient] — the film counterpart of [TVShowSearchViewModelTest], mirroring its cases so a rule
 * changed on one side and not the other shows up here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MovieSearchViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: MovieRepository
    private val viewModels = ViewModelRegistry()

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
        db = testAppDatabase()
        repository = MovieRepository(db)
    }

    @AfterTest
    fun tearDown() {
        viewModels.clearAll()
        db.close()
    }

    /**
     * A poster fetcher whose download always fails, so an accidental fetch is loud rather than a
     * silent network call in a unit test. None of these tests assert on artwork.
     */
    private fun noPosters() =
        FetchPosterUseCase(
            coverDownloader =
                CoverImageDownloader(
                    createHttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }),
                ),
            imageStorage = LocalImageStorageManager("unused-in-these-tests"),
            mediaRepository = MediaRepository(db),
        )

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun viewModel(
        searchBody: String = EMPTY_SEARCH,
        movieBody: String = MATRIX,
        movieStatus: HttpStatusCode = HttpStatusCode.OK,
    ): MovieSearchViewModel {
        val engine =
            MockEngine { request ->
                when {
                    request.url.encodedPath.startsWith("/3/search/movie") ->
                        respond(searchBody, HttpStatusCode.OK, jsonHeaders())
                    request.url.encodedPath.startsWith("/3/movie/") ->
                        if (movieStatus == HttpStatusCode.OK) {
                            respond(movieBody, HttpStatusCode.OK, jsonHeaders())
                        } else {
                            respondError(movieStatus)
                        }
                    else -> respondError(HttpStatusCode.NotFound)
                }
            }
        return viewModels.track(
            MovieSearchViewModel(
                tmdbClient = TmdbClient(createHttpClient(engine), credentialProvider = { TOKEN }),
                movieRepository = repository,
                fetchPosterUseCase = noPosters(),
            ),
        )
    }

    // ---- searching -----------------------------------------------------------------------------

    @Test
    fun search_populatesResults() =
        runTest {
            val vm = viewModel(searchBody = MATRIX_SEARCH)
            vm.onQueryChange("matrix")
            vm.search()

            val state = vm.uiState.first { !it.isSearching && it.hasSearched }
            val hit = state.results.single()
            assertEquals(603, hit.tmdbId)
            assertEquals("The Matrix", hit.title)
            assertEquals("1999", hit.year)
            assertNull(state.searchError)
        }

    @Test
    fun search_blankQuery_doesNotSearchAtAll() =
        runTest {
            val vm = viewModel()
            vm.onQueryChange("   ")
            vm.search()

            assertFalse(vm.uiState.value.hasSearched)
        }

    @Test
    fun search_withNoCredential_reportsTheSettingsSentence() =
        runTest {
            val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
            val vm =
                viewModels.track(
                    MovieSearchViewModel(
                        TmdbClient(createHttpClient(engine), credentialProvider = { null }),
                        repository,
                        noPosters(),
                    ),
                )
            vm.onQueryChange("matrix")
            vm.search()

            val state = vm.uiState.first { it.hasSearched }
            assertTrue(
                state.searchError?.contains("Settings") == true,
                "the app stays usable without a credential, so the screen must name the remedy",
            )
        }

    // ---- adding --------------------------------------------------------------------------------

    @Test
    fun addMovie_writesTheFilmAndItsProviderId() =
        runTest {
            val vm = viewModel(movieBody = MATRIX)
            vm.addMovie(603)

            val state = vm.uiState.first { it.savedMediaId != null }
            val mediaId = state.savedMediaId!!
            assertNull(state.addError)
            assertNull(state.addingTmdbId)

            val item = db.mediaItemDao().getById(mediaId)
            assertEquals("The Matrix", item?.title)
            assertEquals(1999, item?.releaseYear)
            assertEquals(8.2, item?.communityRating)
            assertEquals(136, db.movieDetailsDao().getByMediaId(mediaId)?.runtimeMinutes)
            assertEquals(
                "603",
                db.externalIdentifierDao().getByKey(mediaId, IdentifierProvider.TMDB)?.externalId,
            )
        }

    @Test
    fun addMovie_reportsTheSaveBeforeWaitingOnTheArtwork() =
        runTest {
            // Found on a device: fetching the poster before publishing savedMediaId made a download
            // a precondition of *reaching* the film. savedMediaId is what the route navigates on and
            // addingTmdbId is what keeps every row unresponsive, so a slow one left the user on a
            // dead list looking at a film that had already been added.
            //
            // The poster fetcher here never completes. If the ordering regresses, this test hangs
            // rather than failing loudly -- which is itself the symptom being pinned.
            val neverAnswers =
                FetchPosterUseCase(
                    coverDownloader =
                        CoverImageDownloader(
                            createHttpClient(MockEngine { awaitCancellation() }),
                        ),
                    imageStorage = LocalImageStorageManager("unused-in-these-tests"),
                    mediaRepository = MediaRepository(db),
                )
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.startsWith("/3/movie/") ->
                            respond(MATRIX, HttpStatusCode.OK, jsonHeaders())
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                }
            val vm =
                viewModels.track(
                    MovieSearchViewModel(
                        TmdbClient(createHttpClient(engine), credentialProvider = { TOKEN }),
                        repository,
                        neverAnswers,
                    ),
                )

            vm.addMovie(603)

            // Waited for in *real* time rather than virtual. runTest's clock only advances while
            // every coroutine is idle, and this test deliberately holds one suspended forever, so a
            // virtual-time timeout does not measure what it looks like it measures. Unconfined does
            // not help either: Room and Ktor suspend for real, so the state is not published by the
            // time addMovie returns.
            val state =
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) { vm.uiState.first { it.savedMediaId != null } }
                }
            assertNotNull(
                state.savedMediaId,
                "the save must be published before the artwork is awaited, or a slow download " +
                    "leaves the user on a dead list looking at a film already added",
            )
            assertNull(state.addingTmdbId, "the list must be responsive again before the artwork lands")
        }

    @Test
    fun addMovie_fetchFailure_reportsItAndWritesNothing() =
        runTest {
            val vm = viewModel(movieStatus = HttpStatusCode.InternalServerError)
            vm.addMovie(603)

            vm.uiState.first { it.addError != null }
            assertTrue(
                db
                    .mediaItemDao()
                    .observeAll()
                    .first()
                    .isEmpty(),
                "a failed lookup must leave no half-made film",
            )
        }

    @Test
    fun addMovie_alreadyInTheLibrary_isRefusedByName() =
        runTest {
            val vm = viewModel(searchBody = MATRIX_SEARCH, movieBody = MATRIX)
            vm.onQueryChange("matrix")
            vm.search()
            vm.uiState.first { it.hasSearched }
            vm.addMovie(603)
            vm.uiState.first { it.savedMediaId != null }

            val second = viewModel(searchBody = MATRIX_SEARCH, movieBody = MATRIX)
            second.onQueryChange("matrix")
            second.search()
            second.uiState.first { it.hasSearched }
            second.addMovie(603)

            val state = second.uiState.first { it.addError != null }
            assertTrue(state.addError!!.contains("The Matrix") && state.addError!!.contains("already"))
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
    fun addMovie_whenAShowSharesTheTmdbNumber_isNotBlockedByIt() =
        runTest {
            // TMDB numbers films and shows in separate sequences, so /tv/603 and /movie/603 are
            // unrelated records. Without the media-type predicate on the lookup, owning the show
            // would refuse the film -- and the user would be told they already have something they
            // do not. The mirror of the show-side test on #129.
            TVShowRepository(db).addShow(
                title = "Some Unrelated Show",
                externalIdentifiers = listOf(IdentifierProvider.TMDB to "603"),
            )

            val vm = viewModel(movieBody = MATRIX)
            vm.addMovie(603)

            val state = vm.uiState.first { it.savedMediaId != null || it.addError != null }
            assertNull(state.addError, "a show sharing the number must not block the film")
            assertEquals("The Matrix", db.mediaItemDao().getById(state.savedMediaId!!)?.title)
        }

    @Test
    fun addMovie_tappedTwice_createsOneFilm() =
        runTest {
            val vm = viewModel(movieBody = MATRIX)
            vm.addMovie(603)
            vm.addMovie(603)

            vm.uiState.first { it.savedMediaId != null }
            assertEquals(
                1,
                db
                    .mediaItemDao()
                    .observeAll()
                    .first()
                    .size,
                "a second tap must never produce a second film, whichever guard catches it",
            )
        }

    private companion object {
        /** Shape only — `TmdbCredential.of` classifies on the `eyJ` prefix; nothing here is real. */
        const val TOKEN = "eyJ-fake-tmdb-read-access-token-for-tests"

        const val EMPTY_SEARCH = """{"page":1,"results":[],"total_pages":0,"total_results":0}"""

        const val MATRIX_SEARCH = """
            {"page":1,"results":[
              {"id":603,"title":"The Matrix","release_date":"1999-03-30",
               "overview":"A hacker learns the truth.","poster_path":"/p.jpg",
               "vote_average":8.2,"vote_count":24000}
            ],"total_pages":1,"total_results":1}
        """

        const val MATRIX = """
            {"id":603,"title":"The Matrix","overview":"A hacker learns the truth.",
             "release_date":"1999-03-30","runtime":136,"poster_path":"/p.jpg",
             "status":"Released","vote_average":8.2,"vote_count":24000}
        """
    }
}
