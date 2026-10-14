package com.hub.media.ui

import com.hub.media.core.util.Resource
import com.hub.media.features.portability.domain.ArchiveExporter
import com.hub.media.features.portability.domain.ExportArchiveFile
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
 * [ExportViewModel] tests against a hand-rolled [FakeArchiveExporter] — no Room database, no
 * file I/O — so this class is safe to run on the android unit-test variant too, mirroring
 * [AddBookViewModelTest]'s exact rationale and shape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest {
    private val viewModels = ViewModelRegistry()

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
    }

    @AfterTest
    fun tearDown() {
        viewModels.clearAll()
        Dispatchers.resetMain()
    }

    private fun newViewModel(exporter: ArchiveExporter) =
        viewModels.track(ExportViewModel(exporter, appVersion = "9.9.9"))

    @Test
    fun initialState_isIdle() {
        val viewModel = newViewModel(FakeArchiveExporter())
        assertEquals(ExportUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun exportData_emitsIdleThenLoadingThenSuccess() =
        runTest {
            val file = ExportArchiveFile(bytes = byteArrayOf(1, 2, 3), suggestedFileName = "x.zip")
            val fake = FakeArchiveExporter(result = Resource.Success(file)).apply { awaitGate = true }
            val viewModel = newViewModel(fake)

            assertEquals(ExportUiState.Idle, viewModel.uiState.value)

            viewModel.exportData()
            assertEquals(ExportUiState.Loading, viewModel.uiState.value)

            fake.release()
            val finalState = viewModel.uiState.first { it is ExportUiState.Success }
            assertEquals(ExportUiState.Success(file), finalState)
        }

    @Test
    fun exportData_useCaseError_setsErrorState() =
        runTest {
            val fake = FakeArchiveExporter(result = Resource.Error("Failed to export data: boom"))
            val viewModel = newViewModel(fake)

            viewModel.exportData()

            val finalState = viewModel.uiState.value
            assertIs<ExportUiState.Error>(finalState)
            assertEquals("Failed to export data: boom", finalState.message)
        }

    @Test
    fun exportData_concurrentRequestWhileLoading_isIgnored() =
        runTest {
            val fake = FakeArchiveExporter().apply { awaitGate = true }
            val viewModel = newViewModel(fake)

            viewModel.exportData()
            assertEquals(ExportUiState.Loading, viewModel.uiState.value)

            // Second request while the first is still in flight must be a no-op.
            viewModel.exportData()
            assertEquals(1, fake.callCount)

            fake.release()
            viewModel.uiState.first { it is ExportUiState.Success }
            assertEquals(1, fake.callCount)
        }

    @Test
    fun reset_returnsToIdleAfterTerminalState() =
        runTest {
            val viewModel = newViewModel(FakeArchiveExporter())

            viewModel.exportData()
            assertIs<ExportUiState.Success>(viewModel.uiState.value)

            viewModel.reset()

            assertEquals(ExportUiState.Idle, viewModel.uiState.value)
        }

    @Test
    fun exportData_afterReset_isAcceptedAgain() =
        runTest {
            val fake = FakeArchiveExporter()
            val viewModel = newViewModel(fake)

            viewModel.exportData()
            viewModel.reset()
            viewModel.exportData()

            assertEquals(2, fake.callCount)
            assertIs<ExportUiState.Success>(viewModel.uiState.value)
        }

    // ---- #196: the archive waits for its destination in the ViewModel ---------------------------

    private suspend fun awaiting(fake: FakeArchiveExporter = FakeArchiveExporter()): ExportViewModel {
        val vm = newViewModel(fake)
        vm.exportData()
        vm.uiState.first { it is ExportUiState.Success }
        vm.awaitDestination()
        return vm
    }

    @Test
    fun exportData_passesTheAppVersion() =
        runTest {
            val fake = FakeArchiveExporter()
            newViewModel(fake).exportData()
            assertEquals(listOf("9.9.9"), fake.appVersions)
        }

    @Test
    fun awaitingDestination_holdsTheFile_andIsNotSuccess() =
        runTest {
            val vm = awaiting()
            // No longer Success: a recreated screen's effect, which opens the dialog on Success, finds nothing.
            val state = assertIs<ExportUiState.AwaitingDestination>(vm.uiState.first { it !is ExportUiState.Success })
            assertEquals("media-tracker-export-2026-10-14.zip", state.file.suggestedFileName)
        }

    @Test
    fun takePendingFile_handsItOverOnce_andResets() =
        runTest {
            val vm = awaiting()
            vm.uiState.first { it is ExportUiState.AwaitingDestination }
            assertEquals("media-tracker-export-2026-10-14.zip", vm.takePendingFile()?.suggestedFileName)
            assertEquals(ExportUiState.Idle, vm.uiState.first { it is ExportUiState.Idle })
            assertNull(vm.takePendingFile(), "a second dialog result finds nothing to write")
        }

    @Test
    fun takePendingFile_withNothingAwaiting_isNull() =
        runTest {
            // What a dialog result sees after process death: a fresh ViewModel.
            assertNull(newViewModel(FakeArchiveExporter()).takePendingFile())
        }
}
