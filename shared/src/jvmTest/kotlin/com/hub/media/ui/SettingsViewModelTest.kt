package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.testAppDatabase
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.WeekStartDay
import com.hub.media.features.settings.data.setGoogleBooksApiKey
import com.hub.media.features.settings.data.setWeekStartDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SettingsViewModel] tests against a real in-memory [AppDatabase] (same builder/style as
 * [LibraryViewModelTest]/[StatsViewModelTest]), so the `map` -> `stateIn` wiring over
 * [SettingsRepository.observeWeekStartDay] plus [SettingsViewModel.setWeekStartDay]'s write path
 * are both exercised end to end against the real repository — since [uiState] is sourced directly
 * from [SettingsRepository.observeWeekStartDay]'s Room-backed `Flow` (not a fake/in-memory
 * substitute), a written value being reflected back through [uiState] is itself proof the write
 * reached the database, not just an in-memory shortcut (persistence itself, independent of any
 * ViewModel, is covered directly by [com.hub.media.features.settings.data.WeekStartDayTest]).
 *
 * Room-backed, so this class lives in `jvmTest`, the only source set where `testAppDatabase()` is visible (#81), same as [StatsViewModelTest]/[EditBookViewModelTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: SettingsRepository
    private val viewModels = ViewModelRegistry()

    @BeforeTest
    fun setUp() {
        // viewModelScope dispatches on Dispatchers.Main; UnconfinedTestDispatcher runs launched
        // coroutines eagerly so uiState updates are observable without manually pumping a
        // TestCoroutineScheduler (same convention as LibraryViewModelTest/StatsViewModelTest).
        viewModels.installMain()
        db = testAppDatabase()
        repository = SettingsRepository(db.appSettingsDao())
    }

    @AfterTest
    fun tearDown() {
        // Cancel every ViewModel's viewModelScope (and its stateIn/WhileSubscribed sharing
        // coroutine) before closing the database or resetting Main -- see ViewModelRegistry's
        // KDoc for why this order matters.
        viewModels.clearAll()
        db.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel() = viewModels.track(SettingsViewModel(settingsRepository = repository))

    @Test
    fun uiState_initialValue_defaultsToMonday() {
        val viewModel = newViewModel()

        assertEquals(WeekStartDay.MONDAY, viewModel.uiState.value.weekStartDay)
    }

    @Test
    fun uiState_neverChanged_staysMondayAfterFirstEmission() =
        runTest {
            val viewModel = newViewModel()

            val settled = viewModel.uiState.first()

            assertEquals(
                WeekStartDay.MONDAY,
                settled.weekStartDay,
                "an unset preference must default to MONDAY, matching pre-Phase-B behavior",
            )
        }

    @Test
    fun setWeekStartDay_updatesUiStateReactively() =
        runTest {
            val viewModel = newViewModel()

            viewModel.setWeekStartDay(WeekStartDay.SUNDAY)

            val updated = viewModel.uiState.first { it.weekStartDay == WeekStartDay.SUNDAY }
            assertEquals(WeekStartDay.SUNDAY, updated.weekStartDay)
        }

    @Test
    fun uiState_reflectsValueAlreadyPersistedBeforeViewModelCreation() =
        runTest {
            repository.setWeekStartDay(WeekStartDay.SUNDAY)

            val viewModel = newViewModel()
            val settled = viewModel.uiState.first { it.weekStartDay == WeekStartDay.SUNDAY }

            assertEquals(WeekStartDay.SUNDAY, settled.weekStartDay)
        }

    @Test
    fun googleBooksApiKey_defaultsToNotSet() {
        val viewModel = newViewModel()

        assertFalse(viewModel.uiState.value.googleBooksApiKeySet)
    }

    @Test
    fun setGoogleBooksApiKey_flipsTheKeySetFlagReactively() =
        runTest {
            val viewModel = newViewModel()

            viewModel.setGoogleBooksApiKey("a-key")

            val updated = viewModel.uiState.first { it.googleBooksApiKeySet }
            assertTrue(updated.googleBooksApiKeySet)
        }

    @Test
    fun clearGoogleBooksApiKey_flipsTheFlagBack() =
        runTest {
            repository.setGoogleBooksApiKey("a-key")
            val viewModel = newViewModel()
            viewModel.uiState.first { it.googleBooksApiKeySet }

            viewModel.clearGoogleBooksApiKey()

            val cleared = viewModel.uiState.first { !it.googleBooksApiKeySet }
            assertFalse(cleared.googleBooksApiKeySet)
        }

    @Test
    fun uiState_neverExposesTheKeyItself() =
        runTest {
            // The guarantee SettingsUiState.googleBooksApiKeySet's KDoc makes, asserted rather than
            // trusted to review: the state object is what a future "log the UI state" line would
            // capture, so a key leaking into it is a credential leak with no other symptom.
            repository.setGoogleBooksApiKey("super-secret-key")
            val viewModel = newViewModel()

            val settled = viewModel.uiState.first { it.googleBooksApiKeySet }

            assertFalse(
                "super-secret-key" in settled.toString(),
                "the key must never reach SettingsUiState: $settled",
            )
        }
}
