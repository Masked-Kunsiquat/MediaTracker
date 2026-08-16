package com.hub.media.ui

import com.hub.media.core.util.Resource
import com.hub.media.features.portability.domain.DuplicatePolicy
import com.hub.media.features.portability.domain.ImportSummary
import com.hub.media.features.portability.domain.ImportUseCase
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

/**
 * [ImportViewModel] tests against a hand-rolled [FakeImportDataUseCase] -- no Room database, no
 * file I/O -- so this class is safe to run on the android unit-test variant too, mirroring
 * [ExportViewModelTest]'s exact rationale and shape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {
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

    private fun newViewModel(useCase: ImportUseCase) = viewModels.track(ImportViewModel(useCase))

    private val sampleSummary =
        ImportSummary(
            booksImported = 2,
            booksSkipped = 1,
            booksMerged = 0,
            booksReplaced = 0,
            sessionsImported = 3,
            sessionsSkipped = 0,
            sessionsMerged = 0,
            sessionsReplaced = 0,
            rejections = emptyList(),
        )

    @Test
    fun initialState_isIdle() {
        val viewModel = newViewModel(FakeImportDataUseCase())
        assertEquals(ImportUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun importData_emitsIdleThenLoadingThenSuccess() =
        runTest {
            val fake = FakeImportDataUseCase(result = Resource.Success(sampleSummary)).apply { awaitGate = true }
            val viewModel = newViewModel(fake)

            assertEquals(ImportUiState.Idle, viewModel.uiState.value)

            viewModel.importData("library-csv", "logs-csv", DuplicatePolicy.MERGE)
            assertEquals(ImportUiState.Loading, viewModel.uiState.value)

            fake.release()
            val finalState = viewModel.uiState.first { it is ImportUiState.Success }
            assertEquals(ImportUiState.Success(sampleSummary), finalState)
            assertEquals(Triple("library-csv", "logs-csv", DuplicatePolicy.MERGE), fake.lastArgs)
        }

    @Test
    fun importData_useCaseError_setsErrorState() =
        runTest {
            val fake = FakeImportDataUseCase(result = Resource.Error("Import failed: boom"))
            val viewModel = newViewModel(fake)

            viewModel.importData(null, "logs-csv", DuplicatePolicy.SKIP)

            val finalState = viewModel.uiState.value
            assertIs<ImportUiState.Error>(finalState)
            assertEquals("Import failed: boom", finalState.message)
        }

    @Test
    fun importData_concurrentRequestWhileLoading_isIgnored() =
        runTest {
            val fake = FakeImportDataUseCase().apply { awaitGate = true }
            val viewModel = newViewModel(fake)

            viewModel.importData("a", null, DuplicatePolicy.SKIP)
            assertEquals(ImportUiState.Loading, viewModel.uiState.value)

            viewModel.importData("b", null, DuplicatePolicy.REPLACE)
            assertEquals(1, fake.callCount)

            fake.release()
            viewModel.uiState.first { it is ImportUiState.Success }
            assertEquals(1, fake.callCount)
        }

    @Test
    fun reset_returnsToIdleAfterTerminalState() =
        runTest {
            val viewModel = newViewModel(FakeImportDataUseCase(result = Resource.Success(sampleSummary)))

            viewModel.importData("a", null, DuplicatePolicy.SKIP)
            assertIs<ImportUiState.Success>(viewModel.uiState.value)

            viewModel.reset()

            assertEquals(ImportUiState.Idle, viewModel.uiState.value)
        }

    @Test
    fun importData_afterReset_isAcceptedAgain() =
        runTest {
            val fake = FakeImportDataUseCase(result = Resource.Success(sampleSummary))
            val viewModel = newViewModel(fake)

            viewModel.importData("a", null, DuplicatePolicy.SKIP)
            viewModel.reset()
            viewModel.importData("b", null, DuplicatePolicy.SKIP)

            assertEquals(2, fake.callCount)
            assertIs<ImportUiState.Success>(viewModel.uiState.value)
        }

    // ---- importGoodreads (ROADMAP Task 8 Phase D) ----------------------------------------------

    @Test
    fun importGoodreads_emitsIdleThenLoadingThenSuccess() =
        runTest {
            val fake = FakeImportDataUseCase(result = Resource.Success(sampleSummary)).apply { awaitGate = true }
            val viewModel = newViewModel(fake)

            assertEquals(ImportUiState.Idle, viewModel.uiState.value)

            viewModel.importGoodreads("goodreads-csv", DuplicatePolicy.MERGE)
            assertEquals(ImportUiState.Loading, viewModel.uiState.value)

            fake.release()
            val finalState = viewModel.uiState.first { it is ImportUiState.Success }
            assertEquals(ImportUiState.Success(sampleSummary), finalState)
            assertEquals("goodreads-csv" to DuplicatePolicy.MERGE, fake.lastGoodreadsArgs)
            assertEquals(1, fake.goodreadsCallCount)
            assertEquals(0, fake.callCount, "must not have also called the native execute() path")
        }

    @Test
    fun importGoodreads_useCaseError_setsErrorState() =
        runTest {
            val fake = FakeImportDataUseCase(result = Resource.Error("Goodreads import failed: boom"))
            val viewModel = newViewModel(fake)

            viewModel.importGoodreads("goodreads-csv", DuplicatePolicy.SKIP)

            val finalState = viewModel.uiState.value
            assertIs<ImportUiState.Error>(finalState)
            assertEquals("Goodreads import failed: boom", finalState.message)
        }

    @Test
    fun importGoodreads_whileNativeImportLoading_isIgnored() =
        runTest {
            // The two import actions share one Idle/Loading/Success/Error state machine (both write to
            // the same library) -- a Goodreads import cannot start while a native CSV import is still
            // in flight, same double-tap guard as two concurrent importData calls.
            val fake = FakeImportDataUseCase().apply { awaitGate = true }
            val viewModel = newViewModel(fake)

            viewModel.importData("a", null, DuplicatePolicy.SKIP)
            assertEquals(ImportUiState.Loading, viewModel.uiState.value)

            viewModel.importGoodreads("goodreads-csv", DuplicatePolicy.SKIP)
            assertEquals(0, fake.goodreadsCallCount)

            fake.release()
            viewModel.uiState.first { it is ImportUiState.Success }
            assertEquals(1, fake.callCount)
            assertEquals(0, fake.goodreadsCallCount)
        }
}
