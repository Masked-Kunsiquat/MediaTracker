package com.hub.media.ui

import com.hub.media.core.util.Resource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * [AddBookViewModel] tests against a hand-rolled [FakeAddBookByIsbnUseCase] — no Ktor engine, no
 * Room database, no disk I/O — so this class is safe to run on the android unit-test variant too
 * (unlike [LibraryViewModelTest], which needs a real [com.hub.media.core.database.AppDatabase]
 * and is excluded there; see shared/build.gradle.kts).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddBookViewModelTest {

    @BeforeTest
    fun setUp() {
        // viewModelScope dispatches on Dispatchers.Main; UnconfinedTestDispatcher runs launched
        // coroutines eagerly so state transitions are observable synchronously without manually
        // pumping a TestCoroutineScheduler.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_isIdle() {
        val viewModel = AddBookViewModel(FakeAddBookByIsbnUseCase())
        assertEquals(AddBookUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun addBook_emitsIdleThenLoadingThenSuccess() = runTest {
        val fake = FakeAddBookByIsbnUseCase(result = Resource.Success("media-1")).apply {
            awaitGate = true
        }
        val viewModel = AddBookViewModel(fake)

        assertEquals(AddBookUiState.Idle, viewModel.uiState.value)

        viewModel.addBook("9780135957059")
        assertEquals(AddBookUiState.Loading, viewModel.uiState.value)

        fake.release()
        val finalState = viewModel.uiState.first { it is AddBookUiState.Success }
        assertEquals(AddBookUiState.Success("media-1"), finalState)
    }

    @Test
    fun addBook_useCaseError_setsErrorState() = runTest {
        val fake = FakeAddBookByIsbnUseCase(result = Resource.Error("Invalid ISBN: 'bad'"))
        val viewModel = AddBookViewModel(fake)

        viewModel.addBook("bad")

        val finalState = viewModel.uiState.value
        assertIs<AddBookUiState.Error>(finalState)
        assertEquals("Invalid ISBN: 'bad'", finalState.message)
    }

    @Test
    fun addBook_concurrentSubmissionWhileLoading_isIgnored() = runTest {
        val fake = FakeAddBookByIsbnUseCase(result = Resource.Success("media-1")).apply {
            awaitGate = true
        }
        val viewModel = AddBookViewModel(fake)

        viewModel.addBook("9780135957059")
        assertEquals(AddBookUiState.Loading, viewModel.uiState.value)

        // Second submission while the first is still in flight must be a no-op.
        viewModel.addBook("9780132350884")
        assertEquals(1, fake.callCount)

        fake.release()
        viewModel.uiState.first { it is AddBookUiState.Success }
        assertEquals(1, fake.callCount)
    }

    @Test
    fun reset_returnsToIdleAfterTerminalState() = runTest {
        val fake = FakeAddBookByIsbnUseCase(result = Resource.Success("media-1"))
        val viewModel = AddBookViewModel(fake)

        viewModel.addBook("9780135957059")
        assertIs<AddBookUiState.Success>(viewModel.uiState.value)

        viewModel.reset()

        assertEquals(AddBookUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun addBook_afterReset_isAcceptedAgain() = runTest {
        val fake = FakeAddBookByIsbnUseCase(result = Resource.Success("media-1"))
        val viewModel = AddBookViewModel(fake)

        viewModel.addBook("9780135957059")
        viewModel.reset()
        viewModel.addBook("9780132350884")

        assertEquals(2, fake.callCount)
        assertIs<AddBookUiState.Success>(viewModel.uiState.value)
    }
}
