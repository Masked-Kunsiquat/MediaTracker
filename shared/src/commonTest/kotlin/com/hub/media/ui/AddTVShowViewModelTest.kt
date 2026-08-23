package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.testAppDatabase
import com.hub.media.features.tv.data.TVShowRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AddTVShowViewModel] tests against a real in-memory [AppDatabase], mirroring
 * [EditMovieViewModelTest]'s style: [AddTVShowViewModel] takes a concrete [TVShowRepository], so
 * there is no seam to fake. Room-backed, so excluded from the android unit-test variant by exact
 * class name in `shared/build.gradle.kts` — `:shared:jvmTest` is the authoritative gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddTVShowViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var tvShowRepository: TVShowRepository
    private val viewModels = ViewModelRegistry()

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
        db = testAppDatabase()
        tvShowRepository = TVShowRepository(db)
    }

    @AfterTest
    fun tearDown() {
        viewModels.clearAll()
        db.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(): AddTVShowViewModel = viewModels.track(AddTVShowViewModel(tvShowRepository))

    @Test
    fun save_quickFill_createsTheShowAndItsEpisodeRows() =
        runTest {
            val viewModel = newViewModel()
            viewModel.onTitleChange("Breaking Bad")
            viewModel.onReleaseYearChange("2008")
            viewModel.onTotalSeasonsChange("5")
            viewModel.onPurchasePriceChange("9.99")
            viewModel.addSeasonRow()
            viewModel.onSeasonNumberChange(0, "1")
            viewModel.onEpisodeCountChange(0, "7")
            viewModel.addSeasonRow()
            viewModel.onEpisodeCountChange(1, "13")

            viewModel.save()

            val state = viewModel.uiState.first { it.savedMediaId != null }
            val mediaId = requireNotNull(state.savedMediaId)

            val mediaItem = db.mediaItemDao().getById(mediaId)
            assertEquals("Breaking Bad", mediaItem?.title)
            assertEquals(MediaType.TV_SHOW, mediaItem?.type)
            assertEquals(2008, mediaItem?.releaseYear)
            assertEquals(9.99, mediaItem?.purchasePrice)
            assertEquals(5, db.tvDetailsDao().getByMediaId(mediaId)?.totalSeasons)

            val episodes = db.episodeDao().getByMediaId(mediaId)
            assertEquals(20, episodes.size, "season 1 (7 episodes) + season 2 (13 episodes)")
            assertEquals(7, episodes.count { it.seasonNumber == 1 })
            assertEquals(13, episodes.count { it.seasonNumber == 2 })
            assertTrue(episodes.all { it.watchedAt == null }, "quick-filled episodes start unwatched")
        }

    @Test
    fun addSeasonRow_defaultsToTheNextUnusedSeasonNumber() =
        runTest {
            val viewModel = newViewModel()

            viewModel.addSeasonRow()
            assertEquals(
                "1",
                viewModel.uiState.value.seasons[0]
                    .seasonNumber,
            )

            viewModel.onSeasonNumberChange(0, "3")
            viewModel.addSeasonRow()

            assertEquals(
                "4",
                viewModel.uiState.value.seasons[1]
                    .seasonNumber,
            )
        }

    @Test
    fun removeSeasonRow_dropsOnlyThatRow() =
        runTest {
            val viewModel = newViewModel()
            viewModel.addSeasonRow()
            viewModel.onSeasonNumberChange(0, "1")
            viewModel.addSeasonRow()
            viewModel.onSeasonNumberChange(1, "2")

            viewModel.removeSeasonRow(0)

            val remaining = viewModel.uiState.value.seasons
            assertEquals(1, remaining.size)
            assertEquals("2", remaining[0].seasonNumber)
        }

    @Test
    fun save_unreadableReleaseYear_reportsAnErrorAndSavesNothing() =
        runTest {
            val viewModel = newViewModel()
            viewModel.onTitleChange("Show")
            viewModel.onReleaseYearChange("19999999999")

            viewModel.save()

            val state = viewModel.uiState.first { it.saveError != null }
            assertTrue(
                state.saveError!!.contains("release year", ignoreCase = true),
                "the error must name the offending field: ${state.saveError}",
            )
            assertNull(state.savedMediaId)
            assertEquals(
                0,
                db
                    .mediaItemDao()
                    .observeByType(MediaType.TV_SHOW)
                    .first()
                    .size,
            )
        }

    @Test
    fun save_blankEpisodeCountOnASeasonRow_isRefusedNamingThatRow() =
        runTest {
            val viewModel = newViewModel()
            viewModel.onTitleChange("Show")
            viewModel.addSeasonRow()
            viewModel.onSeasonNumberChange(0, "1")
            // episodeCount deliberately left blank -- an unfinished row, not "unknown episodes".

            viewModel.save()

            val state = viewModel.uiState.first { it.saveError != null }
            assertTrue(
                state.saveError!!.contains("Season row 1", ignoreCase = false),
                "the error must name which season row: ${state.saveError}",
            )
            assertNull(state.savedMediaId)
            assertEquals(
                0,
                db
                    .mediaItemDao()
                    .observeByType(MediaType.TV_SHOW)
                    .first()
                    .size,
            )
        }

    @Test
    fun save_unreadableSeasonNumber_isRefusedNamingThatRow() =
        runTest {
            val viewModel = newViewModel()
            viewModel.onTitleChange("Show")
            viewModel.addSeasonRow()
            viewModel.onSeasonNumberChange(0, "not-a-number")
            viewModel.onEpisodeCountChange(0, "10")

            viewModel.save()

            val state = viewModel.uiState.first { it.saveError != null }
            assertTrue(state.saveError!!.contains("Season row 1"), "error: ${state.saveError}")
            assertEquals(
                0,
                db
                    .mediaItemDao()
                    .observeByType(MediaType.TV_SHOW)
                    .first()
                    .size,
            )
        }

    @Test
    fun save_doubleTapped_createsOnlyOneShow() =
        runTest {
            // No StandardTestDispatcher juggling needed here, unlike the concurrency tests in
            // BookDetailViewModelTest: the isSaving flip happens synchronously in save() itself,
            // before the repository call is ever launched -- so the second call below observes it
            // regardless of dispatcher timing. What makes this a genuine test (not one that would
            // pass even with the guard deleted) is that testAppDatabase() dispatches Room's actual
            // suspend work onto a real thread, so the first save()'s launch{} genuinely suspends
            // there and control returns to this test before it finishes -- the second save() call
            // below lands while the first is still in flight, not after.
            val viewModel = newViewModel()
            viewModel.onTitleChange("Show")

            viewModel.save()
            viewModel.save()

            viewModel.uiState.first { it.savedMediaId != null }
            val shows = db.mediaItemDao().observeByType(MediaType.TV_SHOW).first()
            assertEquals(1, shows.size, "a double-tapped save must create exactly one show")
        }

    @Test
    fun reset_clearsTheSaveErrorButLeavesTypedFieldsIntact() =
        runTest {
            val viewModel = newViewModel()
            viewModel.onTitleChange("Show")
            viewModel.onReleaseYearChange("19999999999")
            viewModel.save()
            viewModel.uiState.first { it.saveError != null }

            viewModel.reset()

            val state = viewModel.uiState.value
            assertNull(state.saveError)
            assertEquals("Show", state.title, "this ViewModel owns the field values, unlike AddMovieViewModel")
        }
}
