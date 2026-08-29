package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.WatchStatus
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.features.movies.data.MovieRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [EditMovieViewModel] tests against a real in-memory [AppDatabase], mirroring
 * [EditBookViewModelTest]'s style (same `testAppDatabase()` builder, `Dispatchers.Main` set to an
 * eager test dispatcher via [ViewModelRegistry.installMain]). Room-backed, so in `jvmTest`, the only source set where `testAppDatabase()` is visible (#81) — `:shared:jvmTest` is
 * the authoritative gate.
 *
 * Focused on the one thing the form's own text-to-number conversion can get wrong in a way nothing
 * downstream can detect: a numeric field that cannot be read is not the same claim as a numeric
 * field the user cleared, and only this class is in a position to tell them apart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditMovieViewModelTest {
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
        // Cancel every ViewModel's viewModelScope before closing the database or resetting Main --
        // see ViewModelRegistry's KDoc for why this order matters.
        viewModels.clearAll()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun insertMovie(
        title: String = "Interstellar",
        releaseYear: Int? = 2014,
        purchasePrice: Double? = 14.99,
        runtimeMinutes: Int? = 169,
    ): String {
        val result =
            movieRepository.addMovie(
                title = title,
                releaseYear = releaseYear,
                purchasePrice = purchasePrice,
                runtimeMinutes = runtimeMinutes,
                status = WatchStatus.WATCHLIST,
            )
        assertIs<Resource.Success<String>>(result)
        return result.data
    }

    private suspend fun editingViewModel(movieId: String): EditMovieViewModel {
        val viewModel = viewModels.track(EditMovieViewModel(movieId, movieRepository))
        viewModel.uiState.first { it is EditMovieUiState.Editing }
        return viewModel
    }

    private fun form(viewModel: EditMovieViewModel) = viewModel.uiState.value as EditMovieUiState.Editing

    @Test
    fun uiState_prefillsTheFormWithTheMoviesCurrentValues() =
        runTest {
            val movieId = insertMovie(title = "Interstellar", releaseYear = 2014, runtimeMinutes = 169)

            val viewModel = editingViewModel(movieId)

            val editing = form(viewModel)
            assertEquals("Interstellar", editing.title)
            assertEquals("2014", editing.releaseYear)
            assertEquals("169", editing.runtimeMinutes)
            assertEquals(WatchStatus.WATCHLIST, editing.status)
        }

    @Test
    fun save_unreadableReleaseYear_reportsAnErrorAndLeavesTheStoredYearIntact() =
        runTest {
            // A digits-only input filter does not make this unreachable: a run of digits too long
            // for Int parses to nothing, and toIntOrNull would hand that to the repository as null
            // -- indistinguishable from "the user cleared the year", which would silently erase
            // 2014 instead of complaining.
            val movieId = insertMovie(releaseYear = 2014)
            val viewModel = editingViewModel(movieId)

            viewModel.onReleaseYearChange("19999999999")
            viewModel.save()

            val editing = form(viewModel)
            assertTrue(editing.saveError != null, "an unreadable year must be reported, not saved as unknown")
            assertEquals(false, editing.isSaving)
            assertEquals(false, editing.saved)
            assertEquals(2014, db.mediaItemDao().getById(movieId)?.releaseYear)
        }

    @Test
    fun save_unreadablePurchasePrice_reportsAnErrorAndLeavesTheStoredPriceIntact() =
        runTest {
            val movieId = insertMovie(purchasePrice = 14.99)
            val viewModel = editingViewModel(movieId)

            viewModel.onPurchasePriceChange("14.9.9")
            viewModel.save()

            val editing = form(viewModel)
            assertTrue(editing.saveError != null, "an unreadable price must be reported, not saved as unknown")
            assertEquals(14.99, db.mediaItemDao().getById(movieId)?.purchasePrice)
        }

    @Test
    fun save_clearedNumericFields_stillSavesThemAsUnknown() =
        runTest {
            // The other half of the same rule, and the reason the check above is parseability
            // rather than "non-blank": deliberately emptying a field means "unknown" and must keep
            // working, or the guard would have traded one silent failure for a stuck form.
            val movieId = insertMovie(releaseYear = 2014, purchasePrice = 14.99, runtimeMinutes = 169)
            val viewModel = editingViewModel(movieId)

            viewModel.onReleaseYearChange("")
            viewModel.onRuntimeChange("")
            viewModel.onPurchasePriceChange("")
            viewModel.save()

            viewModel.uiState.first { it is EditMovieUiState.Editing && it.saved }

            val mediaItem = db.mediaItemDao().getById(movieId)
            assertNull(mediaItem?.releaseYear)
            assertNull(mediaItem?.purchasePrice)
            assertNull(db.movieDetailsDao().getByMediaId(movieId)?.runtimeMinutes)
        }

    @Test
    fun save_happyPath_persistsTheEditedValues() =
        runTest {
            val movieId = insertMovie(title = "Intersteller", releaseYear = 2014, runtimeMinutes = 169)
            val viewModel = editingViewModel(movieId)

            viewModel.onTitleChange("Interstellar")
            viewModel.onRuntimeChange("170")
            viewModel.onStatusChange(WatchStatus.WATCHED)
            viewModel.save()

            viewModel.uiState.first { it is EditMovieUiState.Editing && it.saved }

            assertEquals("Interstellar", db.mediaItemDao().getById(movieId)?.title)
            val details = db.movieDetailsDao().getByMediaId(movieId)
            assertEquals(170, details?.runtimeMinutes)
            assertEquals(WatchStatus.WATCHED, details?.status)
        }
}
