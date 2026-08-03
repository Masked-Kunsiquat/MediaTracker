package com.hub.media.ui

import com.hub.media.core.util.Resource
import com.hub.media.features.portability.domain.CsvExportBundle
import com.hub.media.features.portability.domain.ExportUseCase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest

/**
 * [ExportViewModel] tests against a hand-rolled [FakeExportDataUseCase] — no Room database, no
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

    private fun newViewModel(useCase: ExportUseCase) =
        viewModels.track(ExportViewModel(useCase))

    @Test
    fun initialState_isIdle() {
        val viewModel = newViewModel(FakeExportDataUseCase())
        assertEquals(ExportUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun exportData_emitsIdleThenLoadingThenSuccess() = runTest {
        val bundle = CsvExportBundle(libraryCsv = "library-csv", readingLogsCsv = "logs-csv")
        val fake = FakeExportDataUseCase(result = Resource.Success(bundle)).apply { awaitGate = true }
        val viewModel = newViewModel(fake)

        assertEquals(ExportUiState.Idle, viewModel.uiState.value)

        viewModel.exportData()
        assertEquals(ExportUiState.Loading, viewModel.uiState.value)

        fake.release()
        val finalState = viewModel.uiState.first { it is ExportUiState.Success }
        assertEquals(ExportUiState.Success(bundle), finalState)
    }

    @Test
    fun exportData_useCaseError_setsErrorState() = runTest {
        val fake = FakeExportDataUseCase(result = Resource.Error("Failed to export data: boom"))
        val viewModel = newViewModel(fake)

        viewModel.exportData()

        val finalState = viewModel.uiState.value
        assertIs<ExportUiState.Error>(finalState)
        assertEquals("Failed to export data: boom", finalState.message)
    }

    @Test
    fun exportData_concurrentRequestWhileLoading_isIgnored() = runTest {
        val fake = FakeExportDataUseCase().apply { awaitGate = true }
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
    fun reset_returnsToIdleAfterTerminalState() = runTest {
        val viewModel = newViewModel(FakeExportDataUseCase())

        viewModel.exportData()
        assertIs<ExportUiState.Success>(viewModel.uiState.value)

        viewModel.reset()

        assertEquals(ExportUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun exportData_afterReset_isAcceptedAgain() = runTest {
        val fake = FakeExportDataUseCase()
        val viewModel = newViewModel(fake)

        viewModel.exportData()
        viewModel.reset()
        viewModel.exportData()

        assertEquals(2, fake.callCount)
        assertIs<ExportUiState.Success>(viewModel.uiState.value)
    }
}
