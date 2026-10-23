package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.MediaRepository
import com.hub.media.core.database.entities.AiringStatus
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.features.books.network.CoverImageDownloader
import com.hub.media.features.movies.data.MovieRepository
import com.hub.media.features.tv.data.TVShowRepository
import com.hub.media.features.tv.domain.FetchPosterUseCase
import com.hub.media.features.tv.domain.SeasonSummary
import com.hub.media.features.tv.domain.seasonSummary
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

    /**
     * A poster fetcher whose download always fails, so an accidental fetch is loud rather than a
     * silent network call in a unit test. None of these tests assert on artwork.
     */
    private var showRequests = 0
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
                        if (showRequests++ >= 0 && showStatus == HttpStatusCode.OK) {
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
                fetchPosterUseCase = noPosters(),
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
                        noPosters(),
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
                        noPosters(),
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

    // ---- reviewing and adding (#214) -------------------------------------------------------------

    private suspend fun TVShowSearchViewModel.awaitReview(): ShowReview = uiState.first { it.review != null }.review!!

    private suspend fun showCount(): Int =
        db
            .mediaItemDao()
            .observeAll()
            .first()
            .size

    @Test
    fun reviewShow_opensTheFormFilledFromTmdb_andWritesNothing() =
        runTest {
            val vm = viewModel(showBody = CHERNOBYL)
            vm.reviewShow(87108)

            val review = vm.awaitReview()
            assertEquals("Chernobyl", review.form.title)
            assertEquals("2019", review.form.releaseYear)
            assertEquals("1", review.form.totalSeasons)
            assertTrue(review.form.seasonRows.isEmpty(), "TMDB's seasons are not quick-fill rows")
            assertEquals(SeasonSummary(seasons = 1, episodes = 2), review.mapping.seasonSummary())
            assertNull(vm.uiState.value.addingTmdbId)
            assertEquals(0, showCount(), "tapping a result must not add the show before Save")
            withContext(Dispatchers.Default) { delay(100) }
            assertEquals(0, posterRequests, "no poster may be fetched for a show that is not saved")
        }

    @Test
    fun saveReview_storesTheEditedFieldsAndEveryTmdbSeasonAndEpisode() =
        runTest {
            val vm = viewModel(showBody = CHERNOBYL)
            vm.reviewShow(87108)
            vm.awaitReview()
            vm.onReviewTitleChange("Chernobyl (HBO)")
            vm.onReviewPurchasePriceChange("24.99")
            vm.saveReview()

            val state = vm.uiState.first { it.savedMediaId != null }
            val mediaId = state.savedMediaId!!
            assertNull(state.review, "the review closes once the show is saved")
            val item = db.mediaItemDao().getById(mediaId)
            assertEquals("Chernobyl (HBO)", item?.title)
            assertEquals(2019, item?.releaseYear)
            assertEquals(24.99, item?.purchasePrice)
            assertEquals(8.7, item?.communityRating)
            assertEquals("The 1986 disaster.", item?.synopsis)

            val details = db.tvDetailsDao().getByMediaId(mediaId)
            assertEquals(AiringStatus.ENDED, details?.airingStatus)
            assertEquals(1, details?.totalSeasons)

            val episodes = db.episodeDao().getByMediaId(mediaId)
            assertEquals(2, episodes.size)
            assertEquals("1:23:45", episodes.first { it.episodeNumber == 1 }.title)
            assertEquals(
                "87108",
                db.externalIdentifierDao().getByKey(mediaId, IdentifierProvider.TMDB)?.externalId,
                "without this the backfill cannot match the row back to its TMDB record",
            )
        }

    @Test
    fun saveReview_anUnreadableField_isReportedAndNothingIsWritten() =
        runTest {
            val vm = viewModel(showBody = CHERNOBYL)
            vm.reviewShow(87108)
            vm.awaitReview()
            vm.onReviewTotalSeasonsChange("1.5")
            vm.saveReview()

            val review = vm.uiState.first { it.review?.saveError != null }.review!!
            assertEquals("Enter a valid total seasons, or clear the field", review.saveError)
            assertEquals(0, showCount())
        }

    @Test
    fun cancelReview_keepsTheResultsAndWritesNothing() =
        runTest {
            val vm = viewModel(searchBody = CHERNOBYL_SEARCH, showBody = CHERNOBYL)
            vm.onQueryChange("chernobyl")
            vm.search()
            vm.uiState.first { it.hasSearched }
            vm.reviewShow(87108)
            vm.awaitReview()

            vm.cancelReview()

            val state = vm.uiState.first { it.review == null }
            assertEquals("chernobyl", state.query)
            assertEquals(listOf(87108), state.results.map { it.tmdbId })
            assertEquals(0, showCount())
        }

    @Test
    fun cancelReview_whileSaving_isIgnored_andTheSaveLands() =
        runTest {
            val vm = viewModel(showBody = CHERNOBYL)
            vm.reviewShow(87108)
            vm.awaitReview()

            vm.saveReview()
            vm.cancelReview()
            // cancelReview is synchronous, so its effect is readable at once: the review must still
            // be showing (or already saved), never back at the results while the show is written.
            assertTrue(
                vm.uiState.value.review != null || vm.uiState.value.savedMediaId != null,
                "Back during a save must not close the review under a write that is still landing",
            )

            vm.uiState.first { it.savedMediaId != null }
            assertEquals(1, showCount())
        }

    @Test
    fun saveReview_tappedTwice_createsOneShow() =
        runTest {
            val vm = viewModel(showBody = CHERNOBYL)
            vm.reviewShow(87108)
            vm.awaitReview()

            vm.saveReview()
            vm.saveReview()

            vm.uiState.first { it.savedMediaId != null }
            assertEquals(1, showCount(), "a second tap must never produce a second show")
        }

    @Test
    fun reviewShow_tappedTwice_looksUpOnce() =
        runTest {
            val vm = viewModel(showBody = CHERNOBYL)
            vm.reviewShow(87108)
            vm.reviewShow(87108)

            vm.awaitReview()
            assertEquals(1, showRequests, "a second tap while one lookup runs must not start another")
        }

    @Test
    fun reviewShow_fetchFailure_reportsItAndWritesNothing() =
        runTest {
            val vm = viewModel(showStatus = HttpStatusCode.InternalServerError)
            vm.reviewShow(87108)

            val state = vm.uiState.first { it.addError != null }
            assertNull(state.review)
            assertNull(state.addingTmdbId)
            assertEquals(0, showCount(), "a failed lookup must leave no half-made show")
        }

    @Test
    fun reviewShow_recordWithoutATitle_isReportedRatherThanSilentlySkipped() =
        runTest {
            // The user tapped a row and is owed an answer, even though nothing could be reviewed.
            val vm = viewModel(showBody = SHOW_WITHOUT_NAME)
            vm.reviewShow(87108)

            val state = vm.uiState.first { it.addError != null }
            assertTrue(state.addError!!.contains("title", ignoreCase = true))
            assertNull(state.review)
        }

    @Test
    fun reviewShow_alreadyInTheLibrary_isRefusedByNameBeforeTheLookup() =
        runTest {
            val vm = viewModel(showBody = CHERNOBYL)
            vm.reviewShow(87108)
            vm.awaitReview()
            vm.saveReview()
            vm.uiState.first { it.savedMediaId != null }

            // A fresh ViewModel, as if the user searched again later.
            val second = viewModel(searchBody = CHERNOBYL_SEARCH, showBody = CHERNOBYL)
            second.onQueryChange("chernobyl")
            second.search()
            second.uiState.first { it.hasSearched }
            val requestsBefore = showRequests
            second.reviewShow(87108)

            val state = second.uiState.first { it.addError != null }
            assertTrue(
                state.addError!!.contains("Chernobyl") && state.addError!!.contains("already"),
                "the refusal must name the show rather than fail vaguely: ${state.addError}",
            )
            assertNull(state.review)
            assertEquals(requestsBefore, showRequests, "a show already held is refused before the lookup")
            assertEquals(1, showCount())
        }

    @Test
    fun reviewShow_whenAFilmSharesTheTmdbNumber_isNotBlockedByIt() =
        runTest {
            // TMDB numbers films and shows in separate sequences, so /tv/87108 and /movie/87108 are
            // unrelated records. Without the media-type predicate on the lookup, owning the film
            // would refuse the show -- and the user would be told they already have something they
            // do not.
            MovieRepository(db).addMovie(
                title = "Some Unrelated Film",
                externalIdentifiers = listOf(IdentifierProvider.TMDB to "87108"),
            )

            val vm = viewModel(showBody = CHERNOBYL)
            vm.reviewShow(87108)

            val state = vm.uiState.first { it.review != null || it.addError != null }
            assertNull(state.addError, "a film sharing the number must not block the show")
            assertEquals("Chernobyl", state.review?.form?.title)
        }

    @Test
    fun reset_clearsTheSavedIdSoReturningDoesNotNavigateAgain() =
        runTest {
            val vm = viewModel(showBody = CHERNOBYL)
            vm.reviewShow(87108)
            vm.awaitReview()
            vm.saveReview()
            vm.uiState.first { it.savedMediaId != null }

            vm.reset()

            assertNull(vm.uiState.value.savedMediaId)
        }

    private companion object {
        /**
         * Shape only. `TmdbCredential.of` classifies on the `eyJ` prefix alone, so this is enough to
         * exercise the read-access-token path without resembling a credential.
         *
         * Spelled the way [com.hub.media.core.network.TmdbCredentialTest] spells its own, and for a
         * reason worth keeping: a fixture built from real base64 segments decodes to a valid JWT
         * header, which the secret-scan gate flags -- correctly, because a scanner that can tell a
         * test's fake credential from a real one could tell a real one from a test's.
         */
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
