package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.MediaRepository
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.storage.cleanupTestTempDir
import com.hub.media.core.storage.createTestTempDir
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
    private lateinit var posterDir: String
    private val viewModels = ViewModelRegistry()

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
        db = testAppDatabase()
        repository = MovieRepository(db)
        posterDir = runBlocking { createTestTempDir() }
    }

    @AfterTest
    fun tearDown() {
        viewModels.clearAll()
        db.close()
        runBlocking { cleanupTestTempDir(posterDir) }
    }

    /**
     * A poster fetcher whose download always fails, so an accidental fetch is loud rather than a
     * silent network call in a unit test. None of these tests assert on artwork.
     */
    private var movieRequests = 0
    private var posterRequests = 0

    private fun noPosters() =
        FetchPosterUseCase(
            coverDownloader =
                CoverImageDownloader(
                    createHttpClient(
                        MockEngine {
                            posterRequests++
                            respondError(HttpStatusCode.NotFound)
                        },
                    ),
                ),
            imageStorage = LocalImageStorageManager("unused-in-these-tests"),
            mediaRepository = MediaRepository(db),
            scope = CoroutineScope(Dispatchers.Default),
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
                        if (movieRequests++ >= 0 && movieStatus == HttpStatusCode.OK) {
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

    // ---- reviewing and adding (#214) -------------------------------------------------------------

    private suspend fun MovieSearchViewModel.awaitReview(): FilmReview = uiState.first { it.review != null }.review!!

    private suspend fun filmCount(): Int =
        db
            .mediaItemDao()
            .observeAll()
            .first()
            .size

    @Test
    fun reviewMovie_opensTheFormFilledFromTmdb_andWritesNothing() =
        runTest {
            val vm = viewModel(movieBody = MATRIX)
            vm.reviewMovie(603)

            val review = vm.awaitReview()
            assertEquals("The Matrix", review.form.title)
            assertEquals("1999", review.form.releaseYear)
            assertEquals("136", review.form.runtimeMinutes)
            assertEquals("", review.form.purchasePrice)
            assertNull(vm.uiState.value.addingTmdbId)
            assertEquals(0, filmCount(), "tapping a result must not add the film before Save")
            withContext(Dispatchers.Default) { delay(100) }
            assertEquals(0, posterRequests, "no poster may be fetched for a film that is not saved")
        }

    @Test
    fun saveReview_storesTheEditedFieldsAndWhatTmdbKnew() =
        runTest {
            val vm = viewModel(movieBody = MATRIX)
            vm.reviewMovie(603)
            vm.awaitReview()
            vm.onReviewTitleChange("The Matrix (Director's Cut)")
            vm.onReviewRuntimeChange("")
            vm.onReviewPurchasePriceChange("4.99")
            vm.saveReview()

            val state = vm.uiState.first { it.savedMediaId != null }
            val mediaId = state.savedMediaId!!
            assertNull(state.review, "the review closes once the film is saved")
            val item = db.mediaItemDao().getById(mediaId)
            assertEquals("The Matrix (Director's Cut)", item?.title)
            assertEquals(1999, item?.releaseYear)
            assertEquals(4.99, item?.purchasePrice)
            assertEquals(8.2, item?.communityRating)
            assertEquals("A hacker learns the truth.", item?.synopsis)
            assertNull(db.movieDetailsDao().getByMediaId(mediaId)?.runtimeMinutes, "a cleared runtime saves as unknown")
            assertEquals(
                "603",
                db.externalIdentifierDao().getByKey(mediaId, IdentifierProvider.TMDB)?.externalId,
            )
        }

    @Test
    fun saveReview_anUnreadableField_isReportedAndNothingIsWritten() =
        runTest {
            val vm = viewModel(movieBody = MATRIX)
            vm.reviewMovie(603)
            vm.awaitReview()
            vm.onReviewReleaseYearChange("19x9")
            vm.saveReview()

            val review = vm.uiState.first { it.review?.saveError != null }.review!!
            assertEquals("Enter a valid release year, or clear the field", review.saveError)
            assertEquals(0, filmCount())
        }

    @Test
    fun cancelReview_keepsTheResultsAndWritesNothing() =
        runTest {
            val vm = viewModel(searchBody = MATRIX_SEARCH, movieBody = MATRIX)
            vm.onQueryChange("matrix")
            vm.search()
            vm.uiState.first { it.hasSearched }
            vm.reviewMovie(603)
            vm.awaitReview()

            vm.cancelReview()

            val state = vm.uiState.first { it.review == null }
            assertEquals("matrix", state.query)
            assertEquals(listOf(603), state.results.map { it.tmdbId })
            assertEquals(0, filmCount())
        }

    @Test
    fun cancelReview_whileSaving_isIgnored_andTheSaveLands() =
        runTest {
            val vm = viewModel(movieBody = MATRIX)
            vm.reviewMovie(603)
            vm.awaitReview()

            vm.saveReview()
            // isSaving is set before the write is launched, so this lands inside the save.
            assertTrue(
                vm.uiState.value.review
                    ?.isSaving == true ||
                    vm.uiState.value.savedMediaId != null,
            )
            vm.cancelReview()
            // cancelReview is synchronous, so its effect is readable at once: the review must still
            // be showing (or already saved), never back at the results while the film is written.
            assertTrue(
                vm.uiState.value.review != null || vm.uiState.value.savedMediaId != null,
                "Back during a save must not close the review under a write that is still landing",
            )

            vm.uiState.first { it.savedMediaId != null }
            assertEquals(1, filmCount(), "Back during a save must not lose the film the save is writing")
        }

    @Test
    fun saveReview_tappedTwice_createsOneFilm() =
        runTest {
            val vm = viewModel(movieBody = MATRIX)
            vm.reviewMovie(603)
            vm.awaitReview()

            vm.saveReview()
            vm.saveReview()

            vm.uiState.first { it.savedMediaId != null }
            assertEquals(1, filmCount(), "a second tap must never produce a second film")
        }

    @Test
    fun reviewMovie_tappedTwice_looksUpOnce() =
        runTest {
            val vm = viewModel(movieBody = MATRIX)
            vm.reviewMovie(603)
            vm.reviewMovie(603)

            vm.awaitReview()
            assertEquals(1, movieRequests, "a second tap while one lookup runs must not start another")
        }

    @Test
    fun saveReview_theArtworkFetchOutlivesTheScreenThatStartedIt() =
        runTest {
            // The reason FetchPosterUseCase takes an app-owned scope. Adding a title navigates with
            // popUpTo(inclusive = true), which removes the search destination and clears its
            // ViewModel -- so a download started in viewModelScope is cancelled within moments, and
            // whether a poster arrives becomes a race with the navigation animation. It won on a
            // fast connection during testing, which is how this would have shipped unnoticed.
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val poster =
                    FetchPosterUseCase(
                        coverDownloader =
                            CoverImageDownloader(
                                createHttpClient(MockEngine { respond(byteArrayOf(1, 2, 3)) }),
                            ),
                        imageStorage = LocalImageStorageManager(posterDir),
                        mediaRepository = MediaRepository(db),
                        scope = appScope,
                    )
                val engine =
                    MockEngine { request ->
                        if (request.url.encodedPath.startsWith("/3/movie/")) {
                            respond(MATRIX, HttpStatusCode.OK, jsonHeaders())
                        } else {
                            respondError(HttpStatusCode.NotFound)
                        }
                    }
                val vm =
                    MovieSearchViewModel(
                        TmdbClient(createHttpClient(engine), credentialProvider = { TOKEN }),
                        repository,
                        poster,
                    )

                vm.reviewMovie(603)
                val mediaId =
                    withContext(Dispatchers.Default) {
                        withTimeout(5.seconds) {
                            vm.uiState.first { it.review != null }
                            vm.saveReview()
                            vm.uiState.first { it.savedMediaId != null }.savedMediaId!!
                        }
                    }
                // Clear the ViewModel exactly as navigating away does.
                ViewModelRegistry().also { it.track(vm) }.clearAll()

                val cover =
                    withContext(Dispatchers.Default) {
                        withTimeout(5.seconds) {
                            // delay() rather than a tight loop: without it a regression starves
                            // the dispatcher and the suite *hangs* instead of failing, which is a
                            // worse outcome than the bug being pinned. Learned by writing it wrong.
                            var hash: String? = null
                            while (hash == null) {
                                delay(25)
                                hash = db.mediaItemDao().getById(mediaId)?.coverImageHash
                            }
                            hash
                        }
                    }
                assertNotNull(cover, "the poster must land even though the screen is gone")
            } finally {
                appScope.cancel()
            }
        }

    @Test
    fun reviewMovie_fetchFailure_reportsItAndWritesNothing() =
        runTest {
            val vm = viewModel(movieStatus = HttpStatusCode.InternalServerError)
            vm.reviewMovie(603)

            val state = vm.uiState.first { it.addError != null }
            assertNull(state.review)
            assertEquals(0, filmCount(), "a failed lookup must leave no half-made film")
        }

    @Test
    fun reviewMovie_alreadyInTheLibrary_isRefusedByName() =
        runTest {
            val vm = viewModel(searchBody = MATRIX_SEARCH, movieBody = MATRIX)
            vm.reviewMovie(603)
            vm.awaitReview()
            vm.saveReview()
            vm.uiState.first { it.savedMediaId != null }

            val second = viewModel(searchBody = MATRIX_SEARCH, movieBody = MATRIX)
            second.onQueryChange("matrix")
            second.search()
            second.uiState.first { it.hasSearched }
            val requestsBefore = movieRequests
            second.reviewMovie(603)

            val state = second.uiState.first { it.addError != null }
            assertTrue(state.addError!!.contains("The Matrix") && state.addError!!.contains("already"))
            assertNull(state.review)
            assertEquals(requestsBefore, movieRequests, "a film already held is refused before the lookup")
            assertEquals(1, filmCount())
        }

    @Test
    fun reviewMovie_whenAShowSharesTheTmdbNumber_isNotBlockedByIt() =
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
            vm.reviewMovie(603)

            val state = vm.uiState.first { it.review != null || it.addError != null }
            assertNull(state.addError, "a show sharing the number must not block the film")
            assertIs<FilmReview>(state.review)
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
