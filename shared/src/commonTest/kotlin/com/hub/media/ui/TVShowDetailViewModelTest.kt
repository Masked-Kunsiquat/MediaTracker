package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.database.testAppDatabase
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [TVShowDetailViewModel] tests against a real in-memory [AppDatabase], mirroring
 * [EditMovieViewModelTest]'s style: [TVShowDetailViewModel] takes a concrete [TVShowRepository], so
 * there is no seam to fake. Room-backed, so excluded from the android unit-test variant by exact
 * class name in `shared/build.gradle.kts` — `:shared:jvmTest` is the authoritative gate.
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
    private lateinit var tempDir: String
    private val viewModels = ViewModelRegistry()

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
        db = testAppDatabase()
        tvShowRepository = TVShowRepository(db)
        tempDir = runBlocking { createTestTempDir() }
        deleteMediaUseCase = DeleteMediaUseCase(db, LocalImageStorageManager(tempDir))
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
    ): String {
        val result = tvShowRepository.addShow(title = title, seasons = seasons)
        assertIs<Resource.Success<String>>(result)
        return result.data
    }

    private suspend fun insertBook(title: String = "Some Book"): String {
        val result = BookRepository(db).addBook(title = title, format = BookFormat.PHYSICAL)
        assertIs<Resource.Success<String>>(result)
        return result.data
    }

    private suspend fun readyViewModel(showId: String): TVShowDetailViewModel {
        val viewModel = viewModels.track(TVShowDetailViewModel(showId, tvShowRepository, deleteMediaUseCase))
        viewModel.uiState.first { it is TVShowDetailUiState.Ready }
        return viewModel
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
    fun addSeason_quickFillsNewEpisodesAndTheyAppearGroupedInReadyState() =
        runTest {
            val showId = insertShow(seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 2)))
            val viewModel = readyViewModel(showId)

            viewModel.addSeason(seasonNumber = 2, episodeCount = 4)

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
            val viewModel = viewModels.track(TVShowDetailViewModel(newId(), tvShowRepository, deleteMediaUseCase))

            val state = viewModel.uiState.first { it !is TVShowDetailUiState.Loading }

            assertIs<TVShowDetailUiState.NotFound>(state)
        }

    @Test
    fun uiState_bookId_isNotFound() =
        runTest {
            // TVShowRepository.observeShowDetail gates on MediaType.TV_SHOW, so a book id routed
            // here must read as "not found" rather than a mislabelled row.
            val bookId = insertBook()
            val viewModel = viewModels.track(TVShowDetailViewModel(bookId, tvShowRepository, deleteMediaUseCase))

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
}
