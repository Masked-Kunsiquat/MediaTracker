package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.network.createHttpClient
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.storage.cleanupTestTempDir
import com.hub.media.core.storage.createTestTempDir
import com.hub.media.core.util.Resource
import com.hub.media.core.util.newId
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.media.domain.BulkDeleteUseCase
import com.hub.media.features.media.domain.DeleteMediaUseCase
import com.hub.media.features.tv.data.SeasonQuickFill
import com.hub.media.features.tv.data.TVShowRepository
import com.hub.media.features.tv.domain.BackfillShowEpisodesUseCase
import com.hub.media.features.tv.domain.SeasonCountMismatch
import com.hub.media.features.tv.network.TmdbClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TVShowDetailViewModel] tests against a real in-memory [AppDatabase], mirroring
 * [EditMovieViewModelTest]'s style: [TVShowDetailViewModel] takes a concrete [TVShowRepository], so
 * there is no seam to fake. Room-backed, so in `jvmTest`, the only source set where `testAppDatabase()` is visible (#81) — `:shared:jvmTest` is the authoritative gate.
 *
 * Deletion is exercised through a real [DeleteMediaUseCase] (real storage directory, like
 * [LibraryViewModelTest]) rather than a fake, so these tests fail if the two ever stop fitting
 * together.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TVShowDetailViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var tvShowRepository: TVShowRepository
    private lateinit var deleteMediaUseCase: BulkDeleteUseCase
    private lateinit var backfillUseCase: BackfillShowEpisodesUseCase
    private lateinit var tempDir: String
    private val viewModels = ViewModelRegistry()

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
        db = testAppDatabase()
        tvShowRepository = TVShowRepository(db)
        tempDir = runBlocking { createTestTempDir() }
        deleteMediaUseCase = DeleteMediaUseCase(db, LocalImageStorageManager(tempDir))
        // Never reached by these tests -- none of them refresh -- but the ViewModel needs one, and a
        // client whose engine always fails makes an accidental call loud rather than silent.
        backfillUseCase =
            BackfillShowEpisodesUseCase(
                db = db,
                tmdbClient =
                    TmdbClient(
                        createHttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }),
                        { null },
                    ),
                tvShowRepository = tvShowRepository,
            )
    }

    @AfterTest
    fun tearDown() {
        viewModels.clearAll()
        db.close()
        runBlocking { cleanupTestTempDir(tempDir) }
        Dispatchers.resetMain()
    }

    private suspend fun insertShow(
        title: String = "Show",
        seasons: List<SeasonQuickFill> = emptyList(),
        externalIdentifiers: List<Pair<IdentifierProvider, String>> = emptyList(),
    ): String {
        val result =
            tvShowRepository.addShow(
                title = title,
                seasons = seasons,
                externalIdentifiers = externalIdentifiers,
            )
        assertIs<Resource.Success<String>>(result)
        return result.data
    }

    private suspend fun insertBook(title: String = "Some Book"): String {
        val result = BookRepository(db).addBook(title = title, format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(result)
        return result.data
    }

    private suspend fun readyViewModel(
        showId: String,
        backfill: BackfillShowEpisodesUseCase = backfillUseCase,
    ): TVShowDetailViewModel {
        val viewModel =
            viewModels.track(
                TVShowDetailViewModel(showId, tvShowRepository, deleteMediaUseCase, backfill),
            )
        viewModel.uiState.first { it is TVShowDetailUiState.Ready }
        return viewModel
    }

    /**
     * A [BackfillShowEpisodesUseCase] backed by a [MockEngine] returning [body], mirroring
     * [com.hub.media.features.tv.domain.BackfillShowEpisodesUseCaseTest]'s own helper -- these
     * tests need a real TMDB response to derive [SeasonCountMismatch]es from, which the
     * always-failing [backfillUseCase] built in [setUp] deliberately cannot provide.
     */
    private fun mockBackfillUseCase(
        body: String = TWO_SEASON_SHOW,
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
            tmdbClient = TmdbClient(createHttpClient(engine), credentialProvider = { "test-token" }),
            tvShowRepository = tvShowRepository,
        )
    }

    @Test
    fun uiState_ready_groupsEpisodesBySeasonAndDerivesWatchedTotalCounts() =
        runTest {
            val showId =
                insertShow(
                    title = "Show",
                    seasons =
                        listOf(
                            SeasonQuickFill(seasonNumber = 1, episodeCount = 2),
                            SeasonQuickFill(seasonNumber = 2, episodeCount = 3),
                        ),
                )
            val season1Episodes = db.episodeDao().getByMediaIdAndSeason(showId, 1)
            assertIs<Resource.Success<Unit>>(
                tvShowRepository.setEpisodeWatched(season1Episodes.first().id, watched = true),
            )

            val viewModel = readyViewModel(showId)
            val state =
                viewModel.uiState.first {
                    it is TVShowDetailUiState.Ready && (it as TVShowDetailUiState.Ready).watchedEpisodes == 1
                } as TVShowDetailUiState.Ready

            assertEquals(listOf(1, 2), state.seasons.map { it.seasonNumber }, "seasons must be ascending")
            assertEquals(2, state.seasons[0].episodes.size)
            assertEquals(3, state.seasons[1].episodes.size)
            assertEquals(1, state.seasons[0].watchedCount)
            assertEquals(0, state.seasons[1].watchedCount)
            assertEquals(1, state.watchedEpisodes)
            assertEquals(5, state.totalEpisodes)
            assertEquals(false, state.isAbandoned)
        }

    @Test
    fun setEpisodeWatched_ticksAndUnticks_reachesTheDatabaseAndUpdatesDerivedCounts() =
        runTest {
            val showId = insertShow(seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 2)))
            val episodeId =
                db
                    .episodeDao()
                    .getByMediaIdAndSeason(showId, 1)
                    .first()
                    .id
            val viewModel = readyViewModel(showId)

            viewModel.setEpisodeWatched(episodeId, watched = true)
            viewModel.uiState.first {
                it is TVShowDetailUiState.Ready && (it as TVShowDetailUiState.Ready).watchedEpisodes == 1
            }
            assertNotNull(db.episodeDao().getById(episodeId)?.watchedAt, "the tick must reach the database")

            viewModel.setEpisodeWatched(episodeId, watched = false)
            viewModel.uiState.first {
                it is TVShowDetailUiState.Ready && (it as TVShowDetailUiState.Ready).watchedEpisodes == 0
            }
            assertNull(db.episodeDao().getById(episodeId)?.watchedAt, "the untick must clear it in the database")
        }

    @Test
    fun setSeasonWatched_bulkMark_leavesAnAlreadyWatchedEpisodesTimestampAlone() =
        runTest {
            // The repository guarantees this (see TVShowRepository.setSeasonWatched's KDoc); this
            // test asserts the ViewModel does not undo that guarantee on its way through.
            val showId = insertShow(seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 3)))
            val episodes = db.episodeDao().getByMediaIdAndSeason(showId, 1)
            val preWatched = episodes.first()
            assertIs<Resource.Success<Unit>>(tvShowRepository.setEpisodeWatched(preWatched.id, watched = true))
            val preWatchedAt = db.episodeDao().getById(preWatched.id)?.watchedAt
            assertNotNull(preWatchedAt)

            val viewModel = readyViewModel(showId)
            viewModel.setSeasonWatched(seasonNumber = 1, watched = true)

            viewModel.uiState.first {
                it is TVShowDetailUiState.Ready && (it as TVShowDetailUiState.Ready).watchedEpisodes == 3
            }

            assertEquals(
                preWatchedAt,
                db.episodeDao().getById(preWatched.id)?.watchedAt,
                "bulk-marking the season must not restamp an episode that was already watched",
            )
            for (episode in episodes.drop(1)) {
                assertNotNull(
                    db.episodeDao().getById(episode.id)?.watchedAt,
                    "every previously-unwatched episode of the season must now be watched",
                )
            }
        }

    @Test
    fun setSeasonLength_quickFillsNewEpisodesAndTheyAppearGroupedInReadyState() =
        runTest {
            val showId = insertShow(seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 2)))
            val viewModel = readyViewModel(showId)

            viewModel.setSeasonLength(seasonNumber = 2, episodeCount = 4)

            val state =
                viewModel.uiState.first {
                    it is TVShowDetailUiState.Ready && (it as TVShowDetailUiState.Ready).totalEpisodes == 6
                } as TVShowDetailUiState.Ready

            assertEquals(listOf(1, 2), state.seasons.map { it.seasonNumber })
            assertEquals(4, state.seasons[1].episodes.size)
            assertEquals(0, state.watchedEpisodes)
        }

    @Test
    fun setAbandoned_trueThenFalse_roundTripsThroughTheDatabase() =
        runTest {
            val showId = insertShow()
            val viewModel = readyViewModel(showId)

            viewModel.setAbandoned(true)
            viewModel.uiState.first {
                it is TVShowDetailUiState.Ready && (it as TVShowDetailUiState.Ready).isAbandoned
            }
            assertEquals(WatchStatus.ABANDONED, db.tvDetailsDao().getByMediaId(showId)?.status)

            viewModel.setAbandoned(false)
            viewModel.uiState.first {
                it is TVShowDetailUiState.Ready && !(it as TVShowDetailUiState.Ready).isAbandoned
            }
            assertEquals(WatchStatus.WATCHLIST, db.tvDetailsDao().getByMediaId(showId)?.status)
        }

    @Test
    fun uiState_unknownId_isNotFound() =
        runTest {
            val viewModel =
                viewModels.track(
                    TVShowDetailViewModel(newId(), tvShowRepository, deleteMediaUseCase, backfillUseCase),
                )

            val state = viewModel.uiState.first { it !is TVShowDetailUiState.Loading }

            assertIs<TVShowDetailUiState.NotFound>(state)
        }

    @Test
    fun uiState_bookId_isNotFound() =
        runTest {
            // TVShowRepository.observeShowDetail gates on MediaType.TV_SHOW, so a book id routed
            // here must read as "not found" rather than a mislabelled row.
            val bookId = insertBook()
            val viewModel =
                viewModels.track(
                    TVShowDetailViewModel(bookId, tvShowRepository, deleteMediaUseCase, backfillUseCase),
                )

            val state = viewModel.uiState.first { it !is TVShowDetailUiState.Loading }

            assertIs<TVShowDetailUiState.NotFound>(state)
        }

    @Test
    fun deleteShow_removesTheShowFromTheDatabase() =
        runTest {
            val showId = insertShow()
            val viewModel = readyViewModel(showId)

            viewModel.deleteShow()

            val state = viewModel.uiState.first { it is TVShowDetailUiState.NotFound }
            assertIs<TVShowDetailUiState.NotFound>(state)
            assertNull(db.mediaItemDao().getById(showId), "the row itself must be gone")
        }

    // ---- canRefreshMetadata ---------------------------------------------------------------------

    @Test
    fun canRefreshMetadata_isFalseForAShowTypedInByHand() =
        runTest {
            val showId = insertShow(seasons = listOf(SeasonQuickFill(1, 3)))
            val viewModel =
                viewModels.track(
                    TVShowDetailViewModel(showId, tvShowRepository, deleteMediaUseCase, backfillUseCase),
                )

            val state = viewModel.uiState.first { it is TVShowDetailUiState.Ready }
            assertFalse((state as TVShowDetailUiState.Ready).canRefreshMetadata)
        }

    @Test
    fun canRefreshMetadata_isTrueForAShowWithANumericTmdbId() =
        runTest {
            val showId =
                insertShow(
                    seasons = listOf(SeasonQuickFill(1, 3)),
                    externalIdentifiers = listOf(IdentifierProvider.TMDB to "87108"),
                )
            val viewModel =
                viewModels.track(
                    TVShowDetailViewModel(showId, tvShowRepository, deleteMediaUseCase, backfillUseCase),
                )

            val state = viewModel.uiState.first { it is TVShowDetailUiState.Ready && it.canRefreshMetadata }
            assertTrue((state as TVShowDetailUiState.Ready).canRefreshMetadata)
        }

    @Test
    fun canRefreshMetadata_isFalseWhenTheStoredIdIsNotANumber() =
        runTest {
            // Reachable rather than theoretical: the CSV importer validates the *provider* against
            // the enum but accepts any non-blank string as the id, so "TMDB:abc" imports cleanly.
            // The use case refuses such an id before spending a request, which is right -- but a
            // button that is always refused is exactly what hiding this control exists to avoid.
            val showId =
                insertShow(
                    seasons = listOf(SeasonQuickFill(1, 3)),
                    externalIdentifiers = listOf(IdentifierProvider.TMDB to "not-a-number"),
                )
            val viewModel =
                viewModels.track(
                    TVShowDetailViewModel(showId, tvShowRepository, deleteMediaUseCase, backfillUseCase),
                )

            val state = viewModel.uiState.first { it is TVShowDetailUiState.Ready }
            assertFalse((state as TVShowDetailUiState.Ready).canRefreshMetadata)
        }

    // ---- seasonFindings / addMissingEpisodes / addAllMissingEpisodes (#167) ---------------------

    @Test
    fun seasonFindings_isEmptyBeforeAnyRefresh() =
        runTest {
            val showId = insertShow(externalIdentifiers = listOf(IdentifierProvider.TMDB to "12345"))
            val viewModel = readyViewModel(showId, mockBackfillUseCase())

            val state = viewModel.uiState.first { it is TVShowDetailUiState.Ready } as TVShowDetailUiState.Ready

            assertTrue(state.seasonFindings.isEmpty())
        }

    @Test
    fun refreshMetadata_populatesSeasonFindings_fromTheReport() =
        runTest {
            // Mirrors #167's The Expanse exactly: no local episodes at all, so both of TMDB's
            // seasons come back as "you have 0."
            val showId = insertShow(externalIdentifiers = listOf(IdentifierProvider.TMDB to "12345"))
            val viewModel = readyViewModel(showId, mockBackfillUseCase())

            viewModel.refreshMetadata()

            val state =
                viewModel.uiState.first {
                    it is TVShowDetailUiState.Ready && it.seasonFindings.isNotEmpty()
                } as TVShowDetailUiState.Ready
            assertEquals(
                listOf(
                    SeasonCountMismatch(seasonNumber = 1, localEpisodes = 0, providerEpisodes = 3),
                    SeasonCountMismatch(seasonNumber = 2, localEpisodes = 0, providerEpisodes = 2),
                ),
                state.seasonFindings,
            )
        }

    @Test
    fun addMissingEpisodes_callsTheRepositoryWithTheProviderCount_andDropsTheRow() =
        runTest {
            val showId = insertShow(externalIdentifiers = listOf(IdentifierProvider.TMDB to "12345"))
            val viewModel = readyViewModel(showId, mockBackfillUseCase())
            viewModel.refreshMetadata()
            viewModel.uiState.first { it is TVShowDetailUiState.Ready && it.seasonFindings.size == 2 }

            viewModel.addMissingEpisodes(seasonNumber = 1)

            val state =
                viewModel.uiState.first {
                    it is TVShowDetailUiState.Ready && it.seasonFindings.size == 1
                } as TVShowDetailUiState.Ready
            assertEquals(listOf(2), state.seasonFindings.map { it.seasonNumber }, "only season 1 must drop out")
            assertEquals(
                3,
                db.episodeDao().getByMediaIdAndSeason(showId, 1).size,
                "season 1 must be grown to TMDB's own count, the same call ReconcileMismatchesUseCase makes",
            )
            assertEquals(0, db.episodeDao().getByMediaIdAndSeason(showId, 2).size, "season 2 must be untouched")
        }

    @Test
    fun addAllMissingEpisodes_appliesEverySeasonInOrder() =
        runTest {
            val showId = insertShow(externalIdentifiers = listOf(IdentifierProvider.TMDB to "12345"))
            val viewModel = readyViewModel(showId, mockBackfillUseCase())
            viewModel.refreshMetadata()
            viewModel.uiState.first { it is TVShowDetailUiState.Ready && it.seasonFindings.size == 2 }

            viewModel.addAllMissingEpisodes()

            val state =
                viewModel.uiState.first {
                    it is TVShowDetailUiState.Ready && it.seasonFindings.isEmpty()
                } as TVShowDetailUiState.Ready
            assertTrue(state.addingSeasonNumbers.isEmpty())
            assertEquals(3, db.episodeDao().getByMediaIdAndSeason(showId, 1).size)
            assertEquals(2, db.episodeDao().getByMediaIdAndSeason(showId, 2).size)
        }

    @Test
    fun addMissingEpisodes_whenTheRepositoryErrors_surfacesTheErrorAndLeavesTheFinding() =
        runTest {
            // A season TMDB lists beyond TVMetadataValidation.MAX_EPISODE_COUNT (500) is the one real
            // way to make TVShowRepository.addMissingEpisodes itself refuse -- there is no seam to
            // fake this repository through (see this class's own KDoc), so the failure has to be a
            // genuine one the repository's own validation produces.
            val showId = insertShow(externalIdentifiers = listOf(IdentifierProvider.TMDB to "99999"))
            val viewModel = readyViewModel(showId, mockBackfillUseCase(body = tooManyEpisodesShow()))
            viewModel.refreshMetadata()
            viewModel.uiState.first { it is TVShowDetailUiState.Ready && it.seasonFindings.isNotEmpty() }

            viewModel.addMissingEpisodes(seasonNumber = 1)

            val state =
                viewModel.uiState.first {
                    it is TVShowDetailUiState.Ready && it.errorMessage != null
                } as TVShowDetailUiState.Ready
            assertTrue(state.errorMessage!!.contains("500"), state.errorMessage!!)
            assertEquals(
                listOf(1),
                state.seasonFindings.map { it.seasonNumber },
                "a failed add must leave the finding in place rather than dropping it",
            )
        }

    /**
     * The whole batch is reserved before the first season is applied, so no row is left tappable
     * while "add all" is walking toward it -- an add-all that marked seasons busy one at a time
     * invited a per-row tap on a season it was about to take itself.
     */
    @Test
    fun addAllMissingEpisodes_marksEverySeasonBusyBeforeApplyingAnyOfThem() =
        runTest {
            val showId = insertShow(externalIdentifiers = listOf(IdentifierProvider.TMDB to "12345"))
            val viewModel = readyViewModel(showId, mockBackfillUseCase())
            viewModel.refreshMetadata()
            viewModel.uiState.first { it is TVShowDetailUiState.Ready && it.seasonFindings.size == 2 }

            // Paused Main, so the loop only enqueues: see the double-tap test below for why.
            viewModels.installMain(StandardTestDispatcher(testScheduler))
            viewModel.addAllMissingEpisodes()
            runCurrent()

            assertEquals(
                setOf(1, 2),
                addingSeasonNumbersOf(viewModel),
                "every season in the batch must be busy before the first one is applied",
            )

            viewModels.installMain(UnconfinedTestDispatcher(testScheduler))
            val state =
                viewModel.uiState.first {
                    it is TVShowDetailUiState.Ready && it.seasonFindings.isEmpty()
                } as TVShowDetailUiState.Ready
            assertTrue(state.addingSeasonNumbers.isEmpty(), "the busy set must be empty once the batch ends")
        }

    @Test
    fun addMissingEpisodes_secondTapWhileTheFirstIsStillEnqueued_isIgnored() =
        runTest {
            val showId = insertShow(externalIdentifiers = listOf(IdentifierProvider.TMDB to "12345"))
            val viewModel = readyViewModel(showId, mockBackfillUseCase())
            viewModel.refreshMetadata()
            viewModel.uiState.first { it is TVShowDetailUiState.Ready && it.seasonFindings.size == 2 }

            // Dispatchers.Main becomes a StandardTestDispatcher for the two addMissingEpisodes calls
            // below, so the first one's launch only *enqueues* rather than running to completion
            // inside the call that started it -- same technique and reason as
            // EditBookViewModelTest.save_doubleTapBeforeCompletion. Under the default eager
            // dispatcher the first launch can finish before the second call is even made, which would
            // make "call this twice while the first is in flight" a race the test could not actually
            // guarantee. Installed only now, after reaching Ready and refreshing under the default
            // dispatcher those steps already rely on elsewhere in this file.
            viewModels.installMain(StandardTestDispatcher(testScheduler))

            viewModel.addMissingEpisodes(seasonNumber = 1)
            // addingSeasonNumbers.value is set synchronously before the launch, but it only reaches
            // the *public* uiState once combine()'s own collecting coroutine (dispatched via Main)
            // runs -- see AGENTS.md §7 on never reading .value straight after an action. runCurrent()
            // drains exactly that virtual-scheduler work; the repository call itself needs a real
            // dispatch (Room's query context) that runCurrent() cannot cross, so the launch is left
            // genuinely pending here rather than completed.
            runCurrent()
            assertEquals(setOf(1), addingSeasonNumbersOf(viewModel))

            // The second tap, while the first is still pending on that real dispatch.
            viewModel.addMissingEpisodes(seasonNumber = 1)
            runCurrent()
            assertEquals(
                setOf(1),
                addingSeasonNumbersOf(viewModel),
                "a second tap on a season already being added must not grow the busy set",
            )

            // Back to the eager dispatcher for the wait below. The pending launch finishes on Room's
            // own context, which virtual time cannot advance: left on StandardTestDispatcher the
            // await never resumes and runTest fails after a minute with "did not run to completion".
            viewModels.installMain(UnconfinedTestDispatcher(testScheduler))

            val state =
                viewModel.uiState.first {
                    it is TVShowDetailUiState.Ready && it.seasonFindings.none { f -> f.seasonNumber == 1 }
                } as TVShowDetailUiState.Ready
            assertTrue(state.addingSeasonNumbers.isEmpty())
            assertEquals(
                3,
                db.episodeDao().getByMediaIdAndSeason(showId, 1).size,
                "season 1 must end up complete exactly once, not partially or doubly applied",
            )
        }

    /** [TVShowDetailUiState.Ready.addingSeasonNumbers] as it stands right now, with no dispatch. */
    private fun addingSeasonNumbersOf(viewModel: TVShowDetailViewModel): Set<Int> =
        (viewModel.uiState.value as TVShowDetailUiState.Ready).addingSeasonNumbers

    private companion object {
        /**
         * A show with two seasons and no episode payload trimmed out -- season 1 has 3 episodes,
         * season 2 has 2. Paired with a show inserted with no local episodes at all, so both come
         * back as findings: (1, 0, 3) and (2, 0, 2).
         */
        const val TWO_SEASON_SHOW = """
            {"id":12345,"name":"Sample Show","number_of_seasons":2,"number_of_episodes":5,
             "status":"Ended","in_production":false,
             "seasons":[{"season_number":1,"episode_count":3,"name":"Season One"},
                        {"season_number":2,"episode_count":2,"name":"Season Two"}],
             "season/1":{"season_number":1,"name":"Season One","episodes":[
               {"episode_number":1,"season_number":1,"name":"S1E1"},
               {"episode_number":2,"season_number":1,"name":"S1E2"},
               {"episode_number":3,"season_number":1,"name":"S1E3"}
             ]},
             "season/2":{"season_number":2,"name":"Season Two","episodes":[
               {"episode_number":1,"season_number":2,"name":"S2E1"},
               {"episode_number":2,"season_number":2,"name":"S2E2"}
             ]}}
        """

        /**
         * A show whose one season lists one more episode than
         * [com.hub.media.features.tv.data.TVMetadataValidation.MAX_EPISODE_COUNT] allows -- the one
         * real way to make [TVShowRepository.addMissingEpisodes] itself return a [Resource.Error].
         * Built rather than written literally: 501 episode objects is not something to hand-type.
         */
        fun tooManyEpisodesShow(episodeCount: Int = 501): String {
            val episodes =
                (1..episodeCount).joinToString(",") { n ->
                    """{"episode_number":$n,"season_number":1,"name":"Ep$n"}"""
                }
            return """
                {"id":99999,"name":"Big Show","number_of_seasons":1,"number_of_episodes":$episodeCount,
                 "status":"Ended","in_production":false,
                 "seasons":[{"season_number":1,"episode_count":$episodeCount,"name":"Season One"}],
                 "season/1":{"season_number":1,"name":"Season One","episodes":[$episodes]}}
            """
        }
    }
}
