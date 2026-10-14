package com.hub.media.ui

import com.hub.media.core.util.Resource
import com.hub.media.features.portability.domain.BackupResult
import com.hub.media.features.portability.domain.DatabaseBackupUseCase
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

/**
 * #162: the staged backup is handed over to the destination picker through the ViewModel, so a
 * screen recreated while the picker is open neither loses it nor launches the picker a second time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val viewModels = ViewModelRegistry()
    private val staged = BackupResult(stagedFilePath = "/data/db.backup-1.tmp", suggestedFileName = "backup.db")

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
    }

    @AfterTest
    fun tearDown() {
        viewModels.clearAll()
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        viewModels.track(
            BackupViewModel(
                object : DatabaseBackupUseCase {
                    override suspend fun execute(): Resource<BackupResult> = Resource.Success(staged)
                },
            ),
        )

    @Test
    fun awaitingDestination_holdsTheStagedResult_andIsNotSuccessAnyMore() =
        runTest {
            val vm = viewModel()
            vm.backupData()
            vm.uiState.first { it is BackupUiState.Success }
            vm.awaitDestination()
            // No longer Success: a recreated screen's effect, which launches the picker on Success,
            // must find nothing to launch.
            val state = assertIs<BackupUiState.AwaitingDestination>(vm.uiState.first { it !is BackupUiState.Success })
            assertEquals(staged, state.result)
        }

    @Test
    fun takePendingResult_handsItOverOnce_andResets() =
        runTest {
            val vm = viewModel()
            vm.backupData()
            vm.uiState.first { it is BackupUiState.Success }
            vm.awaitDestination()
            vm.uiState.first { it is BackupUiState.AwaitingDestination }
            assertEquals(staged, vm.takePendingResult())
            assertEquals(BackupUiState.Idle, vm.uiState.first { it is BackupUiState.Idle })
            assertNull(vm.takePendingResult(), "a second picker result finds nothing to copy")
        }

    @Test
    fun takePendingResult_withNothingAwaiting_isNull() =
        runTest {
            // What a picker result sees after process death: a fresh ViewModel.
            assertNull(viewModel().takePendingResult())
        }
}
