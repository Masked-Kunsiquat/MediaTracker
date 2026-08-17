import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Demonstrates the authoritative async assertion pattern for MediaTracker ViewModels.
 * 
 * CRITICAL: Never read `.value` immediately after an action (like `save()`).
 * State reaches `uiState` through `combine` -> `stateIn`, which needs a dispatch.
 * Reading `.value` can return the stale state on loaded runners.
 */
class ExampleViewModelTest {

    @Test
    fun save_happyPath_assertsFinalStateCorrectly() = runTest {
        // 1. Setup and trigger the action
        val viewModel = ExampleViewModel(repository)
        viewModel.save(data)

        // 2. DO NOT DO THIS (Flaky):
        // assertEquals(UiState.Saved, viewModel.uiState.value)

        // 3. DO THIS: Await the expected state using .first { ... }
        // This ensures the test waits for the asynchronous dispatch to complete.
        val finalState = viewModel.uiState.first { it is UiState.Saved }
        assertIs<UiState.Saved>(finalState)

        // 4. Verification of database state should also be reactive if possible.
        // Waiting for the repository's observer flow ensures the write has fully
        // propagated through the database and back up to the UI layer.
        val persisted = repository.observeData(id).first { it.updated }
        assertEquals("Expected Value", persisted.value)
    }
}
