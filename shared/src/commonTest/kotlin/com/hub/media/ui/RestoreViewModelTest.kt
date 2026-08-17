package com.hub.media.ui

import com.hub.media.core.util.Resource
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.setGoogleBooksApiKey
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [RestoreViewModel] tests against a hand-rolled [FakeRestoreDatabaseUseCase] and a real
 * [SettingsRepository] backed by [FakeAppSettingsDao] -- no Room database, no file I/O -- mirroring
 * [ExportViewModelTest]'s exact rationale and shape, so this class stays safe to run on the android
 * unit-test variant too.
 *
 * The focus here is [RestoreUiState.AwaitingConfirmation.apiKeyWillBeCleared]: whether staging a
 * restore correctly reports the *current* Google Books API key's presence/absence, read while the
 * live settings are still intact (see that property's KDoc for why it can't be computed later).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreViewModelTest {
    private val viewModels = ViewModelRegistry()
    private lateinit var settingsDao: FakeAppSettingsDao
    private lateinit var settingsRepository: SettingsRepository

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
        settingsDao = FakeAppSettingsDao()
        settingsRepository = SettingsRepository(settingsDao)
    }

    @AfterTest
    fun tearDown() {
        viewModels.clearAll()
        Dispatchers.resetMain()
    }

    private fun newViewModel(restoreDatabaseUseCase: FakeRestoreDatabaseUseCase = FakeRestoreDatabaseUseCase()) =
        viewModels.track(RestoreViewModel(restoreDatabaseUseCase, settingsRepository))

    @Test
    fun validateSelectedFile_keySet_reportsApiKeyWillBeCleared() =
        runTest {
            settingsRepository.setGoogleBooksApiKey("a-real-key")
            val viewModel = newViewModel()

            viewModel.validateSelectedFile("/incoming/backup.sqlite")

            val state = viewModel.uiState.first { it is RestoreUiState.AwaitingConfirmation }
            val awaiting = assertIs<RestoreUiState.AwaitingConfirmation>(state)
            assertTrue(
                awaiting.apiKeyWillBeCleared,
                "a key is currently set, so restoring must warn it will be cleared",
            )
        }

    /**
     * The positive control for the test above: without this, a flag hardcoded to `true` would pass
     * every other test in this class (AGENTS.md §7 "a test that cannot fail is worse than no test").
     */
    @Test
    fun validateSelectedFile_noKeySet_doesNotReportApiKeyWillBeCleared() =
        runTest {
            val viewModel = newViewModel()

            viewModel.validateSelectedFile("/incoming/backup.sqlite")

            val state = viewModel.uiState.first { it is RestoreUiState.AwaitingConfirmation }
            val awaiting = assertIs<RestoreUiState.AwaitingConfirmation>(state)
            assertFalse(
                awaiting.apiKeyWillBeCleared,
                "no key is currently set, so there is nothing to warn about losing",
            )
        }

    @Test
    fun validateSelectedFile_rejectedFile_errorStateCarriesNoApiKeyFlag() =
        runTest {
            settingsRepository.setGoogleBooksApiKey("a-real-key")
            val fake = FakeRestoreDatabaseUseCase(stageResult = Resource.Error("not a MediaTracker backup"))
            val viewModel = newViewModel(fake)

            viewModel.validateSelectedFile("/incoming/not-a-backup.sqlite")

            // RestoreUiState.Error has no apiKeyWillBeCleared property at all -- the sealed class
            // shape itself is what keeps the flag scoped to AwaitingConfirmation; this just confirms
            // a rejected file lands in that state rather than silently becoming AwaitingConfirmation.
            val state = viewModel.uiState.first { it is RestoreUiState.Error }
            val error = assertIs<RestoreUiState.Error>(state)
            assertEquals("not a MediaTracker backup", error.message)
        }

    @Test
    fun uiState_beforeAnyAction_isIdle() {
        val viewModel = newViewModel()
        assertEquals(RestoreUiState.Idle, viewModel.uiState.value)
    }
}
