package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.features.tv.data.TVShowRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [EditTVShowViewModel] against a real in-memory [AppDatabase] (#173), in the shape of
 * [EditMovieViewModelTest]. Room-backed, so `jvmTest`. Every assertion after an action awaits the
 * state it expects with `uiState.first { }` rather than reading `.value` (AGENTS.md section 7).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditTVShowViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: TVShowRepository
    private val viewModels = ViewModelRegistry()

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
        db = testAppDatabase()
        repo = TVShowRepository(db)
    }

    @AfterTest
    fun tearDown() {
        viewModels.clearAll()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun insertShow(
        title: String = "Chernobyl",
        releaseYear: Int? = 2019,
        totalSeasons: Int? = 1,
        purchasePrice: Double? = 19.99,
    ): String {
        val result =
            repo.addShow(
                title = title,
                releaseYear = releaseYear,
                totalSeasons = totalSeasons,
                purchasePrice = purchasePrice,
            )
        assertIs<Resource.Success<String>>(result)
        return result.data
    }

    private suspend fun editing(showId: String): EditTVShowViewModel {
        val vm = viewModels.track(EditTVShowViewModel(showId, repo))
        vm.uiState.first { it is EditTVShowUiState.Editing }
        return vm
    }

    private suspend fun EditTVShowViewModel.awaitForm(predicate: (EditTVShowUiState.Editing) -> Boolean) =
        uiState.first { it is EditTVShowUiState.Editing && predicate(it) } as EditTVShowUiState.Editing

    @Test
    fun load_prefillsTheShowsCurrentValues() =
        runTest {
            val id = insertShow(title = "Chernobyl", releaseYear = 2019, totalSeasons = 1, purchasePrice = 19.99)

            val form = editing(id).awaitForm { true }

            assertEquals("Chernobyl", form.title)
            assertEquals("2019", form.releaseYear)
            assertEquals("1", form.totalSeasons)
            assertEquals("19.99", form.purchasePrice)
        }

    @Test
    fun load_unknownNumbersAreBlankNotNullOrZero() =
        runTest {
            val id = insertShow(releaseYear = null, totalSeasons = null, purchasePrice = null)

            val form = editing(id).awaitForm { true }

            assertEquals("", form.releaseYear)
            assertEquals("", form.totalSeasons)
            assertEquals("", form.purchasePrice)
        }

    @Test
    fun load_unknownShow_isNotFound() =
        runTest {
            val vm = viewModels.track(EditTVShowViewModel("no-such-show", repo))

            assertIs<EditTVShowUiState.NotFound>(vm.uiState.first { it !is EditTVShowUiState.Loading })
        }

    @Test
    fun save_happyPath_persistsAndMarksSaved() =
        runTest {
            val id = insertShow(title = "Chernobil", releaseYear = 2019, totalSeasons = 1)
            val vm = editing(id)

            vm.onTitleChange("Chernobyl")
            vm.onTotalSeasonsChange("2")
            vm.onPurchasePriceChange("9.99")
            vm.save()
            vm.awaitForm { it.saved }

            val item = db.mediaItemDao().getById(id)
            assertEquals("Chernobyl", item?.title)
            assertEquals(9.99, item?.purchasePrice)
            assertEquals(2, db.tvDetailsDao().getByMediaId(id)?.totalSeasons)
        }

    @Test
    fun save_trimsTheTitle() =
        runTest {
            val id = insertShow()
            val vm = editing(id)

            vm.onTitleChange("  Chernobyl  ")
            vm.save()
            vm.awaitForm { it.saved }

            assertEquals("Chernobyl", db.mediaItemDao().getById(id)?.title)
        }

    @Test
    fun save_clearedNumbers_saveAsUnknown() =
        runTest {
            val id = insertShow(releaseYear = 2019, totalSeasons = 1, purchasePrice = 19.99)
            val vm = editing(id)

            vm.onReleaseYearChange("")
            vm.onTotalSeasonsChange("")
            vm.onPurchasePriceChange("")
            vm.save()
            vm.awaitForm { it.saved }

            val item = db.mediaItemDao().getById(id)
            assertNull(item?.releaseYear)
            assertNull(item?.purchasePrice)
            assertNull(db.tvDetailsDao().getByMediaId(id)?.totalSeasons)
        }

    @Test
    fun save_unreadableTotalSeasons_isRefusedAndTheStoredValueKept() =
        runTest {
            // Digits too long for Int parse to nothing; sending that as null would erase the count.
            val id = insertShow(totalSeasons = 3)
            val vm = editing(id)

            vm.onTotalSeasonsChange("99999999999")
            vm.save()
            val form = vm.awaitForm { it.saveError != null }

            assertEquals("Enter a valid total seasons, or clear the field", form.saveError)
            assertEquals(false, form.saved)
            assertEquals(3, db.tvDetailsDao().getByMediaId(id)?.totalSeasons)
        }

    @Test
    fun save_unreadableYear_isRefused() =
        runTest {
            val id = insertShow(releaseYear = 2019)
            val vm = editing(id)

            vm.onReleaseYearChange("19999999999")
            vm.save()
            val form = vm.awaitForm { it.saveError != null }

            assertEquals("Enter a valid release year, or clear the field", form.saveError)
            assertEquals(2019, db.mediaItemDao().getById(id)?.releaseYear)
        }

    @Test
    fun save_zeroTotalSeasons_surfacesTheRepositoryError() =
        runTest {
            // Parses fine, so the ViewModel sends it; TVMetadataValidation is what refuses it.
            val id = insertShow(totalSeasons = 2)
            val vm = editing(id)

            vm.onTotalSeasonsChange("0")
            vm.save()
            val form = vm.awaitForm { it.saveError != null && !it.isSaving }

            assertEquals(false, form.saved)
            assertEquals("0", form.totalSeasons, "the form keeps what was typed")
            assertEquals(2, db.tvDetailsDao().getByMediaId(id)?.totalSeasons)
        }

    @Test
    fun save_showDeletedWhileEditing_reportsError() =
        runTest {
            val id = insertShow()
            val vm = editing(id)
            db.mediaItemDao().deleteById(id)

            vm.onTitleChange("Gone")
            vm.save()
            val form = vm.awaitForm { it.saveError != null && !it.isSaving }

            assertEquals(false, form.saved)
            assertNotNull(form.saveError)
        }

    @Test
    fun consumeSaveError_clearsIt() =
        runTest {
            val id = insertShow()
            val vm = editing(id)
            vm.onReleaseYearChange("19999999999")
            vm.save()
            vm.awaitForm { it.saveError != null }

            vm.consumeSaveError()

            vm.awaitForm { it.saveError == null }
        }

    @Test
    fun save_secondTapWhileTheFirstIsInFlight_isIgnored() =
        runTest {
            val id = insertShow(title = "Original")
            val vm = editing(id)
            // StandardTestDispatcher so the first save's launch only enqueues: the second tap is
            // then guaranteed to land while the first is in flight (AGENTS.md section 7).
            viewModels.installMain(StandardTestDispatcher(testScheduler))

            vm.onTitleChange("First")
            vm.save()
            vm.onTitleChange("Second")
            vm.save()
            runCurrent()
            val form = vm.awaitForm { it.saved }

            assertEquals("First", db.mediaItemDao().getById(id)?.title)
            // The edit made mid-save is refused, so the form never shows a value as saved that was not.
            assertEquals("First", form.title)
        }

    @Test
    fun load_whenTheReadFails_isLoadFailed_andRetryRecovers() =
        runTest {
            // A database read that throws used to escape viewModelScope and crash the app.
            val id = insertShow(title = "Chernobyl")
            db.breakMediaItems()
            val vm = viewModels.track(EditTVShowViewModel(id, repo))

            assertIs<EditTVShowUiState.LoadFailed>(vm.uiState.first { it !is EditTVShowUiState.Loading })

            db.restoreMediaItems()
            vm.retry()

            assertEquals("Chernobyl", vm.awaitForm { true }.title, "Retry loads the form once the read works")
        }

    @Test
    fun editing_snapshotsTheSavedTitleYearAndCover_andTypingLeavesThemAlone() =
        runTest {
            val id = insertShow(title = "Chernobyl", releaseYear = 2019)
            db.mediaItemDao().updateCoverImageHash(id, "poster1")
            val vm = editing(id)

            vm.onTitleChange("Chernobil")

            val form = vm.awaitForm { it.title == "Chernobil" }
            assertEquals("Chernobyl", form.savedTitle)
            assertEquals(2019, form.savedYear)
            assertEquals("poster1", form.coverImageHash)
        }
}
