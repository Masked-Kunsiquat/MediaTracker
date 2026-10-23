package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.database.testAppDatabase
import com.hub.media.features.movies.data.MovieRepository
import com.hub.media.features.tv.domain.WatchedWhen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * [AddMovieViewModel]'s double-tap guard, against a real in-memory [AppDatabase] — that class takes
 * a concrete [MovieRepository], so there is no seam to fake. Room-backed, so in `jvmTest`, the only source set where `testAppDatabase()` is visible (#81).
 *
 * Narrow on purpose: this class was written when the identical guard in [AddTVShowViewModel] turned
 * out to be half a guard, and covers only that. The rest of [AddMovieViewModel]'s behaviour is
 * exercised through `AddMovieScreenTest` in the app module's instrumented suite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddMovieViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var movieRepository: MovieRepository
    private val viewModels = ViewModelRegistry()

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
        db = testAppDatabase()
        movieRepository = MovieRepository(db)
    }

    @AfterTest
    fun tearDown() {
        viewModels.clearAll()
        db.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(): AddMovieViewModel = viewModels.track(AddMovieViewModel(movieRepository))

    private fun AddMovieViewModel.saveArrival() {
        onTitleChange("Arrival")
        onReleaseYearChange("2016")
        onRuntimeChange("116")
        save()
    }

    private suspend fun movieCount(): Int =
        db
            .mediaItemDao()
            .observeByType(MediaType.MOVIE)
            .first()
            .size

    @Test
    fun save_doubleTapped_createsOnlyOneMovie() =
        runTest {
            // Makes no assumption about whether the first save is still in flight when the second
            // call lands: the guard covers both orderings, which is the point. A version of this
            // test for the TV form that did assume it passed locally and failed on CI.
            val viewModel = newViewModel()

            viewModel.saveArrival()
            viewModel.saveArrival()

            viewModel.uiState.first { it is AddMovieUiState.Saved }
            assertEquals(1, movieCount(), "a double-tapped save must create exactly one movie")
        }

    @Test
    fun save_afterASuccessfulSave_isIgnoredUntilReset() =
        runTest {
            // The half an in-flight flag cannot cover: the write has finished, so nothing but the
            // Saved state stands between a second tap and a duplicate row. The screen navigates
            // away at this point, which hides the window rather than closing it.
            val viewModel = newViewModel()
            viewModel.saveArrival()
            viewModel.uiState.first { it is AddMovieUiState.Saved }

            viewModel.saveArrival()

            assertEquals(1, movieCount(), "a tap after a completed save must not write a second copy")
        }

    @Test
    fun save_afterReset_isArmedAgain() =
        runTest {
            // reset() is what the screen calls once it has navigated on the saved id, so the guard
            // must not be a permanent latch -- a form reused after a reset has to be able to save.
            val viewModel = newViewModel()
            viewModel.saveArrival()
            viewModel.uiState.first { it is AddMovieUiState.Saved }

            viewModel.reset()
            viewModel.saveArrival()
            viewModel.uiState.first { it is AddMovieUiState.Saved }

            assertEquals(2, movieCount(), "after a reset the form must be usable again")
        }

    @Test
    fun save_watchedOnPickedDay_storesThatViewing() =
        runTest {
            val vm = viewModels.track(AddMovieViewModel(MovieRepository(db, zone = TimeZone.UTC)))
            vm.onTitleChange("Heat")
            vm.onStatusChange(WatchStatus.WATCHED)
            vm.form.first { it.pendingWatched }
            vm.confirmWatched(WatchedWhen.On(LocalDate(2024, 3, 3)))
            vm.save()
            val id = (vm.uiState.first { it is AddMovieUiState.Saved } as AddMovieUiState.Saved).mediaId
            assertEquals(
                listOf(Instant.parse("2024-03-03T12:00:00Z")),
                db
                    .watchLogDao()
                    .observeForFilm(id)
                    .first()
                    .map { it.watchedAt },
            )
        }

    /** #214: the form's values live here now, so the save reads them rather than taking arguments. */
    @Test
    fun save_usesTheFormsValues() =
        runTest {
            val vm = newViewModel()
            vm.onTitleChange("  Arrival ")
            vm.onReleaseYearChange("2016")
            vm.onRuntimeChange("116")
            vm.onPurchasePriceChange("9.99")
            vm.save()

            val id = (vm.uiState.first { it is AddMovieUiState.Saved } as AddMovieUiState.Saved).mediaId
            val item = db.mediaItemDao().getById(id)
            assertEquals("Arrival", item?.title)
            assertEquals(2016, item?.releaseYear)
            assertEquals(9.99, item?.purchasePrice)
            assertEquals(116, db.movieDetailsDao().getByMediaId(id)?.runtimeMinutes)
        }

    @Test
    fun save_anUnreadableField_isReportedAndNothingIsWritten() =
        runTest {
            val vm = newViewModel()
            vm.onTitleChange("Arrival")
            vm.onRuntimeChange("1.2")
            vm.save()

            val error = vm.uiState.first { it is AddMovieUiState.Error } as AddMovieUiState.Error
            assertEquals("Enter a valid runtime, or clear the field", error.message)
            assertEquals(0, movieCount())
        }

    @Test
    fun choosingWatched_asksWhenBeforeTheStatusChanges() =
        runTest {
            val vm = newViewModel()
            vm.onStatusChange(WatchStatus.WATCHED)

            val form = vm.form.first { it.pendingWatched }
            assertEquals(WatchStatus.WATCHLIST, form.status)
        }
}
